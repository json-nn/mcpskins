package org.minechestplate.mcpskins.client.gui;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.ClientAttachmentIndexPatcher;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.SkinTranslations;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Standalone skin catalog and inspector, independent of what is in the player's hand. Opened
 * by hotkey or {@code /mcpskins armory}.
 * <pre>
 * ┌ search ──────────────────┐ [All][Owned][Locked][Custom] [Sort]
 * ├───────────┬──────────────────────────┬──────────────────┐
 * │ Arsenal   │        3D stage          │  skin tiles      │
 * │  gun      │                          ├──────────────────┤
 * │   skin    │  name, rarity, lore      │  set / unlock    │
 * │   skin    │                          │  [ Equip ]       │
 * └───────────┴──────────────────────────┴──────────────────┘
 * </pre>
 * The panel is centred and capped, picking one of three tiers from its own width so a high
 * GUI scale drops a tier rather than crushing the columns.
 */
public class SkinArmoryScreen extends Screen {

    /** Column widths and type sizes per panel width. */
    private enum Tier {
        WIDE(132, 128, 2.0f, 17, true),
        MID(112, 112, 1.5f, 17, false),
        COMPACT(88, 88, 1.25f, 16, false);

        final int railWidth;
        final int detailWidth;
        final float nameScale;
        final int gunRowHeight;
        final boolean lore;

        Tier(int railWidth, int detailWidth, float nameScale, int gunRowHeight, boolean lore) {
            this.railWidth = railWidth;
            this.detailWidth = detailWidth;
            this.nameScale = nameScale;
            this.gunRowHeight = gunRowHeight;
            this.lore = lore;
        }
    }

    private enum FocusPane { RAIL, TILES }

    private enum StatusFilter { ALL, OWNED, LOCKED }

    private enum SortMode { RARITY, ALPHABETICAL, NEWEST }

    private static final int PANEL_MIN_W = 320;
    private static final int PANEL_MAX_W = 640;
    private static final int PANEL_MIN_H = 240;
    private static final int PANEL_MAX_H = 360;
    private static final int PANEL_MARGIN = 8;

    private static final int TIER_WIDE_MIN = 560;
    private static final int TIER_MID_MIN = 440;

    private static final int PAD = 6;
    private static final int GAP = 6;
    private static final int GAP_TIGHT = 4;
    private static final int CONTROL_H = 15;
    private static final int SECTION_H = 11;
    private static final int SKIN_ROW_H = 12;
    private static final int SKIN_ROW_INDENT = 10;
    private static final int TILE_H = 40;
    private static final int INFO_ROW_H = 9;
    private static final int EQUIP_H = 17;
    private static final int ICON = 8;
    /** Past the stage frame, so a zoomed model cannot paint over its border. */
    private static final int STAGE_INSET = 3;
    private static final int CHIP_PAD = 8;
    private static final int SEARCH_MIN_W = 46;
    /** Enough for an ellipsis plus a glyph, so a squeezed chip still reads as a button. */
    private static final int MIN_CHIP_W = 18;

    /** A pipe cannot occur in a gun or skin id, so composite keys never collide. */
    private static final char SEPARATOR = '|';

    private record Rect(int x0, int y0, int x1, int y1) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        }

        int width() {
            return x1 - x0;
        }

        int height() {
            return y1 - y0;
        }
    }

    /** Every zone for this frame, so render and hit-testing read the same numbers. */
    /** The chip captions as measured, so what is drawn is exactly what was laid out. */
    private record HeaderLabels(String[] filters, String custom, String sort,
                                int filterW, int customW, int sortW) {

        int totalWidth() {
            int count = StatusFilter.values().length;
            return filterW * count + GAP_TIGHT * count + customW + GAP + sortW;
        }
    }

    private record Layout(Tier tier, HeaderLabels labels, Rect panel, Rect search, Rect[] filters, Rect custom, Rect sort,
                          Rect rail, Rect railList, Rect stage, Rect tiles, Rect info, Rect equip) {
    }

    /** One line in the rail. A null {@code skinId} means the gun row itself. */
    private record RailRow(String gunId, String skinId, int top, int height) {
    }

    private record GunGroup(SkinDataModels.WeaponSkins weapon, List<SkinDataModels.SkinEntry> skins) {
    }

    /** Tagged with the asset generation it was computed at. */
    private record CustomModelResult(int generation, boolean hasModel) {
    }

    private final Map<String, String> weaponNameCache = new HashMap<>();
    /** Preview stacks, built once per gun/skin pair instead of per frame. */
    private final Map<String, ItemStack> stackCache = new HashMap<>();
    private final Map<String, CustomModelResult> customModelCache = new HashMap<>();
    private final Item3DPodiumWidget podium = new Item3DPodiumWidget();

    private final List<GunGroup> groups = new ArrayList<>();
    private final List<RailRow> railRows = new ArrayList<>();

    private String selectedGun;
    private String selectedSkinId;
    private EditBox searchBox;
    private StatusFilter statusFilter = StatusFilter.ALL;
    private boolean customModelOnly = false;
    private SortMode sortMode = SortMode.RARITY;
    private FocusPane focusPane = FocusPane.TILES;
    private int railScroll = 0;
    private int tileScroll = 0;
    private String statusMessage;

    /** Skin id to select and scroll to on open, or null for the normal default. */
    private final String focusSkinId;

    public SkinArmoryScreen() {
        this(null);
    }

    /**
     * Opens on {@code focusSkinId} instead of the held weapon, for the clickable skin name in
     * unlock and fuse chat messages.
     */
    public SkinArmoryScreen(String focusSkinId) {
        super(Component.translatable("gui.mcpskins.armory.title"));
        this.focusSkinId = focusSkinId;
    }

    // -----------------------------------------------------------------------------------
    // Init / layout
    // -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        podium.resetView();
        podium.setChrome(false);

        Layout layout = computeLayout();
        String previous = searchBox != null ? searchBox.getValue() : "";

        int textX = layout.search().x0() + PAD + ICON + GAP_TIGHT;
        this.searchBox = new EditBox(this.font, textX,
                layout.search().y0() + (CONTROL_H - ICON) / 2,
                Math.max(8, layout.search().x1() - PAD - textX), ICON,
                Component.translatable("gui.mcpskins.armory.search"));
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(ArmoryTheme.TEXT);
        this.searchBox.setMaxLength(64);
        this.searchBox.setValue(previous);
        this.searchBox.setHint(Component.translatable("gui.mcpskins.armory.search_hint"));
        this.searchBox.setResponder(value -> {
            railScroll = 0;
            tileScroll = 0;
            rebuild();
        });
        this.addRenderableWidget(searchBox);

        SkinDataModels.SkinLookupResult focus = focusSkinId != null
                ? SkinManager.INSTANCE.findSkin(focusSkinId) : null;
        if (focus != null) {
            selectedGun = focus.weapon().baseGun();
            selectedSkinId = focus.skin().id();
        } else if (selectedGun == null) {
            selectedGun = defaultWeaponSelection();
        }

        rebuild();
        scrollRailToSelection(computeLayout());
    }

    /** Recomputed every frame; cheaper than caching and risking staleness after a resize. */
    private Layout computeLayout() {
        int panelW = Mth.clamp(this.width - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        int panelH = Mth.clamp(this.height - PANEL_MARGIN * 2, PANEL_MIN_H, PANEL_MAX_H);
        int px = (this.width - panelW) / 2;
        int py = (this.height - panelH) / 2;
        Rect panel = new Rect(px, py, px + panelW, py + panelH);

        Tier tier = panelW >= TIER_WIDE_MIN ? Tier.WIDE : panelW >= TIER_MID_MIN ? Tier.MID : Tier.COMPACT;

        int innerX0 = panel.x0() + PAD;
        int innerX1 = panel.x1() - PAD;
        int headerY = panel.y0() + PAD;

        // Laid out from the right edge inward, so the search field absorbs the slack. Chip
        // widths are measured rather than assumed, because a translated caption can be far
        // wider than the English one and used to run over the search field.
        int headerW = innerX1 - innerX0;
        HeaderLabels labels = headerLabels(tier);
        if (tier != Tier.COMPACT && labels.totalWidth() + SEARCH_MIN_W + GAP > headerW) {
            labels = headerLabels(Tier.COMPACT); // the short captions, before squeezing anything
        }
        int chipRoom = headerW - SEARCH_MIN_W - GAP;
        if (labels.totalWidth() > chipRoom) {
            labels = squeeze(labels, chipRoom);
        }

        int chipsX = Math.max(innerX0 + SEARCH_MIN_W + GAP, innerX1 - labels.totalWidth());

        Rect[] filters = new Rect[StatusFilter.values().length];
        int cursor = chipsX;
        for (StatusFilter f : StatusFilter.values()) {
            filters[f.ordinal()] = new Rect(cursor, headerY, cursor + labels.filterW(), headerY + CONTROL_H);
            cursor += labels.filterW() + GAP_TIGHT;
        }
        Rect custom = new Rect(cursor, headerY, cursor + labels.customW(), headerY + CONTROL_H);
        cursor += labels.customW() + GAP;
        Rect sort = new Rect(cursor, headerY, cursor + labels.sortW(), headerY + CONTROL_H);
        Rect search = new Rect(innerX0, headerY, chipsX - GAP, headerY + CONTROL_H);

        int contentY0 = headerY + CONTROL_H + GAP;
        int contentY1 = panel.y1() - PAD;

        Rect rail = new Rect(innerX0, contentY0, innerX0 + tier.railWidth, contentY1);
        Rect railList = new Rect(rail.x0(), rail.y0() + SECTION_H, rail.x1(), rail.y1());
        Rect detail = new Rect(innerX1 - tier.detailWidth, contentY0, innerX1, contentY1);
        Rect stage = new Rect(rail.x1() + GAP, contentY0, detail.x0() - GAP, contentY1);

        // Stacked bottom-up: Equip pinned to the floor, info above it, tiles take the rest.
        Rect equip = new Rect(detail.x0(), detail.y1() - EQUIP_H, detail.x1(), detail.y1());
        int infoRows = 2;
        Rect info = new Rect(detail.x0(), equip.y0() - GAP_TIGHT - infoRows * INFO_ROW_H,
                detail.x1(), equip.y0() - GAP_TIGHT);
        Rect tiles = new Rect(detail.x0(), detail.y0() + SECTION_H,
                detail.x1(), Math.max(detail.y0() + SECTION_H + TILE_H, info.y0() - GAP_TIGHT));

        return new Layout(tier, labels, panel, search, filters, custom, sort, rail, railList, stage, tiles, info, equip);
    }

    private int chipWidth(String label) {
        return this.font.width(label) + CHIP_PAD * 2;
    }

    private HeaderLabels headerLabels(Tier tier) {
        String[] filters = new String[StatusFilter.values().length];
        int filterW = 0;
        for (StatusFilter f : StatusFilter.values()) {
            filters[f.ordinal()] = statusFilterLabel(f, tier).getString();
            filterW = Math.max(filterW, chipWidth(filters[f.ordinal()]));
        }
        String custom = customLabel(tier).getString();
        String sort = sortModeLabel(sortMode, tier).getString();
        return new HeaderLabels(filters, custom, sort, filterW, chipWidth(custom), chipWidth(sort));
    }

    /**
     * Last resort when even the short captions overrun the header: shrink every chip by the
     * same factor and cut its caption to match, so the row stays inside the panel instead of
     * overlapping the search field.
     */
    private HeaderLabels squeeze(HeaderLabels labels, int available) {
        int count = StatusFilter.values().length;
        int gaps = GAP_TIGHT * count + GAP;
        int textRoom = Math.max(0, available - gaps);
        int current = Math.max(1, labels.totalWidth() - gaps);
        double factor = Math.min(1.0, textRoom / (double) current);

        int filterW = Math.max(MIN_CHIP_W, (int) (labels.filterW() * factor));
        int customW = Math.max(MIN_CHIP_W, (int) (labels.customW() * factor));
        int sortW = Math.max(MIN_CHIP_W, (int) (labels.sortW() * factor));

        String[] filters = new String[count];
        for (int i = 0; i < count; i++) {
            filters[i] = ArmoryTheme.truncate(this.font, labels.filters()[i], filterW - CHIP_PAD, ArmoryTheme.BASE);
        }
        return new HeaderLabels(filters,
                ArmoryTheme.truncate(this.font, labels.custom(), customW - CHIP_PAD, ArmoryTheme.BASE),
                ArmoryTheme.truncate(this.font, labels.sort(), sortW - CHIP_PAD, ArmoryTheme.BASE),
                filterW, customW, sortW);
    }

    // -----------------------------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------------------------

    /**
     * Rebuilds the rail from the current search, filters and sort. A non-empty search is global,
     * so every matching gun expands rather than only the selected one.
     */
    private void rebuild() {
        groups.clear();
        railRows.clear();

        Player player = Minecraft.getInstance().player;
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        boolean searching = !query.isEmpty();

        // Guns first, then attachments, so attachments read as their own section instead of
        // scattering through the weapon list by name.
        List<SkinDataModels.WeaponSkins> weapons = new ArrayList<>(SkinManager.INSTANCE.getRegistry().values());
        weapons.sort(Comparator.comparing(SkinDataModels.WeaponSkins::isAttachment)
                .thenComparing(w -> weaponDisplayName(w.baseGun()), String.CASE_INSENSITIVE_ORDER));

        for (SkinDataModels.WeaponSkins weapon : weapons) {
            boolean weaponMatches = !searching
                    || weaponDisplayName(weapon.baseGun()).toLowerCase(Locale.ROOT).contains(query);
            List<SkinDataModels.SkinEntry> matched = new ArrayList<>();
            for (SkinDataModels.SkinEntry entry : weapon.skins()) {
                boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
                if (statusFilter == StatusFilter.OWNED && !unlocked) continue;
                if (statusFilter == StatusFilter.LOCKED && unlocked) continue;
                if (customModelOnly && !hasCustomModel(weapon, entry)) continue;
                if (searching && !weaponMatches
                        && !SkinTranslations.name(entry).toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                matched.add(entry);
            }
            if (!matched.isEmpty()) {
                matched.sort(skinComparator(weapon));
                groups.add(new GunGroup(weapon, matched));
            }
        }

        if (groups.isEmpty()) {
            selectedSkinId = null;
            podium.setStack(ItemStack.EMPTY);
            return;
        }

        if (findGroup(selectedGun) == null) {
            selectedGun = groups.get(0).weapon().baseGun();
            selectedSkinId = null;
        }
        GunGroup selected = findGroup(selectedGun);
        if (selectedSkinId == null || indexOfSkin(selected, selectedSkinId) < 0) {
            selectedSkinId = selected.skins().get(0).id();
        }

        int top = 0;
        for (GunGroup group : groups) {
            boolean expanded = searching || group.weapon().baseGun().equals(selectedGun);
            railRows.add(new RailRow(group.weapon().baseGun(), null, top, layoutTier().gunRowHeight));
            top += layoutTier().gunRowHeight;
            if (expanded) {
                for (SkinDataModels.SkinEntry entry : group.skins()) {
                    railRows.add(new RailRow(group.weapon().baseGun(), entry.id(), top, SKIN_ROW_H));
                    top += SKIN_ROW_H;
                }
            }
        }

        statusMessage = null;
        updatePodiumStack();
    }

    /** The weapon's stock skin pins first, whatever the sort mode. */
    private Comparator<SkinDataModels.SkinEntry> skinComparator(SkinDataModels.WeaponSkins weapon) {
        Comparator<SkinDataModels.SkinEntry> byName =
                Comparator.comparing(SkinDataModels.SkinEntry::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<SkinDataModels.SkinEntry> mode = switch (sortMode) {
            case ALPHABETICAL -> byName;
            case NEWEST -> Comparator.<SkinDataModels.SkinEntry>comparingInt(e -> e.isNew() ? 0 : 1).thenComparing(byName);
            case RARITY -> Comparator.<SkinDataModels.SkinEntry>comparingInt(
                    e -> RarityManager.INSTANCE.get(e.rarityId()).order()).reversed().thenComparing(byName);
        };
        return Comparator.<SkinDataModels.SkinEntry>comparingInt(
                e -> isDefaultSkin(weapon, e) ? 0 : 1).thenComparing(mode);
    }

    private Tier layoutTier() {
        int panelW = Mth.clamp(this.width - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        return panelW >= TIER_WIDE_MIN ? Tier.WIDE : panelW >= TIER_MID_MIN ? Tier.MID : Tier.COMPACT;
    }

    private GunGroup findGroup(String gunId) {
        if (gunId == null) return null;
        for (GunGroup group : groups) {
            if (group.weapon().baseGun().equals(gunId)) return group;
        }
        return null;
    }

    private int indexOfSkin(GunGroup group, String skinId) {
        if (group == null || skinId == null) return -1;
        for (int i = 0; i < group.skins().size(); i++) {
            if (group.skins().get(i).id().equals(skinId)) return i;
        }
        return -1;
    }

    private SkinDataModels.SkinEntry selectedSkin() {
        GunGroup group = findGroup(selectedGun);
        int index = indexOfSkin(group, selectedSkinId);
        return index < 0 ? null : group.skins().get(index);
    }

    private void updatePodiumStack() {
        SkinDataModels.SkinEntry entry = selectedSkin();
        podium.setStack(entry == null ? ItemStack.EMPTY : previewStack(selectedGun, entry.id()));
    }

    private String defaultWeaponSelection() {
        Player player = Minecraft.getInstance().player;
        Map<String, SkinDataModels.WeaponSkins> registry = SkinManager.INSTANCE.getRegistry();
        if (player != null) {
            String heldMain = TACZSkinHelper.getTaczId(player.getMainHandItem());
            if (heldMain != null && registry.containsKey(heldMain)) return heldMain;
            String heldOff = TACZSkinHelper.getTaczId(player.getOffhandItem());
            if (heldOff != null && registry.containsKey(heldOff)) return heldOff;
        }
        return registry.isEmpty() ? null : registry.keySet().iterator().next();
    }

    // -----------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC00A0A0C, 0xE0050506);

        Layout layout = computeLayout();
        Rect panel = layout.panel();

        ArmoryTheme.sprite(guiGraphics, ArmoryTheme.PANEL, panel.x0(), panel.y0(), panel.width(), panel.height());

        renderHeader(guiGraphics, layout, mouseX, mouseY);
        renderRail(guiGraphics, layout, mouseX, mouseY);
        renderStage(guiGraphics, layout);
        renderTiles(guiGraphics, layout, mouseX, mouseY);
        renderInfo(guiGraphics, layout);
        renderEquip(guiGraphics, layout, mouseX, mouseY);

        // Draws the search box over the chrome; calls renderBackground(), hence the no-op.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        renderTooltips(guiGraphics, layout, mouseX, mouseY);
    }

    /** No-op: skips vanilla's background blur, which {@code Screen#render} always triggers. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    private void renderHeader(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        Rect search = layout.search();
        boolean focused = searchBox != null && searchBox.isFocused();
        ArmoryTheme.sprite(guiGraphics, focused ? ArmoryTheme.INSET_FOCUS : ArmoryTheme.INSET,
                search.x0(), search.y0(), search.width(), search.height());
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_SEARCH, search.x0() + PAD,
                search.y0() + (CONTROL_H - ICON) / 2, ICON, ICON,
                focused ? ArmoryTheme.ACCENT : ArmoryTheme.TEXT_35);

        for (StatusFilter filter : StatusFilter.values()) {
            renderChip(guiGraphics, layout.filters()[filter.ordinal()],
                    layout.labels().filters()[filter.ordinal()], statusFilter == filter, mouseX, mouseY);
        }
        renderChip(guiGraphics, layout.custom(), layout.labels().custom(), customModelOnly, mouseX, mouseY);
        renderChip(guiGraphics, layout.sort(), layout.labels().sort(), false, mouseX, mouseY);
    }

    private void renderChip(GuiGraphics guiGraphics, Rect rect, String label, boolean on, int mouseX, int mouseY) {
        ArmoryTheme.sprite(guiGraphics, on ? ArmoryTheme.CHIP_ON : ArmoryTheme.CHIP,
                rect.x0(), rect.y0(), rect.width(), rect.height());
        if (!on && rect.contains(mouseX, mouseY)) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW,
                    rect.x0(), rect.y0(), rect.width(), rect.height(), ArmoryTheme.ROW_HOVER);
        }
        guiGraphics.drawCenteredString(this.font, label, (rect.x0() + rect.x1()) / 2,
                rect.y0() + (rect.height() - this.font.lineHeight) / 2 + 1,
                on ? ArmoryTheme.TEXT : ArmoryTheme.TEXT_72);
    }

    private void renderRail(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        Rect rail = layout.rail();
        ArmoryTheme.text(guiGraphics, this.font,
                Component.translatable("gui.mcpskins.armory.arsenal").getString(),
                rail.x0(), rail.y0() + 2, ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, rail.x0(), rail.y0() + SECTION_H - 3,
                rail.width(), 1, ArmoryTheme.RULE);

        Rect list = layout.railList();
        if (groups.isEmpty()) {
            ArmoryTheme.text(guiGraphics, this.font,
                    Component.translatable("gui.mcpskins.armory.no_matches").getString(),
                    list.x0(), list.y0() + 4, ArmoryTheme.SMALL, ArmoryTheme.TEXT_50);
            return;
        }

        guiGraphics.enableScissor(list.x0(), list.y0(), list.x1(), list.y1());
        try {
            for (RailRow row : railRows) {
                int y = list.y0() + row.top() - railScroll;
                if (y + row.height() < list.y0() || y > list.y1()) continue;
                boolean hovered = mouseX >= list.x0() && mouseX < list.x1()
                        && mouseY >= y && mouseY < y + row.height();
                if (row.skinId() == null) {
                    renderGunRow(guiGraphics, list, row, y, hovered);
                } else {
                    renderSkinRow(guiGraphics, list, row, y, hovered);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderGunRow(GuiGraphics guiGraphics, Rect list, RailRow row, int y, boolean hovered) {
        boolean selected = row.gunId().equals(selectedGun);
        if (selected) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, list.x0(), y,
                    list.width(), row.height(), ArmoryTheme.ROW_SELECTED);
        } else if (hovered) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, list.x0(), y,
                    list.width(), row.height(), ArmoryTheme.ROW_HOVER);
        }

        ItemStack icon = previewStack(row.gunId(), null);
        guiGraphics.renderItem(icon, list.x0() + 2, y + (row.height() - 16) / 2);

        GunGroup group = findGroup(row.gunId());
        String count = group == null ? "" : String.valueOf(group.skins().size());
        int countW = ArmoryTheme.scaledWidth(this.font, count, ArmoryTheme.SMALL);
        int nameX = list.x0() + 20;
        int nameRoom = list.x1() - nameX - countW - GAP_TIGHT;

        boolean held = isWeaponCurrentlyHeld(row.gunId());
        String name = ArmoryTheme.truncate(this.font, weaponDisplayName(row.gunId()), nameRoom, ArmoryTheme.BASE);
        guiGraphics.drawString(this.font, name, nameX, y + (row.height() - this.font.lineHeight) / 2,
                held ? ArmoryTheme.ACCENT_LIFT : selected ? ArmoryTheme.TEXT : ArmoryTheme.TEXT_72, false);
        ArmoryTheme.textRight(guiGraphics, this.font, count, list.x1(), y + (row.height() - 6) / 2,
                ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
    }

    private void renderSkinRow(GuiGraphics guiGraphics, Rect list, RailRow row, int y, boolean hovered) {
        SkinDataModels.SkinEntry entry = skinById(row.gunId(), row.skinId());
        if (entry == null) return;
        boolean selected = row.skinId().equals(selectedSkinId) && row.gunId().equals(selectedGun);
        int x0 = list.x0() + SKIN_ROW_INDENT;

        if (selected) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, x0, y,
                    list.x1() - x0, row.height(), ArmoryTheme.ROW_SELECTED);
        } else if (hovered) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, x0, y,
                    list.x1() - x0, row.height(), ArmoryTheme.ROW_HOVER);
        }

        // A spine rather than a coloured label, so a dim datapack colour costs no legibility.
        guiGraphics.fill(x0, y + 1, x0 + 2, y + row.height() - 1, ArmoryTheme.readable(entry.labelColor()));

        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
        int lockRoom = unlocked ? 0 : ICON + GAP_TIGHT;
        int nameX = x0 + 6;
        String name = ArmoryTheme.truncate(this.font, SkinTranslations.name(entry),
                list.x1() - nameX - lockRoom - 2, ArmoryTheme.SMALL);
        ArmoryTheme.text(guiGraphics, this.font, name, nameX, y + 3, ArmoryTheme.SMALL,
                selected ? ArmoryTheme.TEXT : unlocked ? ArmoryTheme.TEXT_72 : ArmoryTheme.TEXT_50);
        if (!unlocked) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_LOCK,
                    list.x1() - ICON - 1, y + 2, ICON, ICON, ArmoryTheme.TEXT_35);
        }
    }

    private void renderStage(GuiGraphics guiGraphics, Layout layout) {
        Rect stage = layout.stage();
        ArmoryTheme.sprite(guiGraphics, ArmoryTheme.STAGE, stage.x0(), stage.y0(), stage.width(), stage.height());

        SkinDataModels.SkinEntry entry = selectedSkin();
        if (entry == null) {
            ArmoryTheme.text(guiGraphics, this.font,
                    Component.translatable("gui.mcpskins.armory.no_matches").getString(),
                    stage.x0() + PAD, stage.y0() + PAD, ArmoryTheme.BASE, ArmoryTheme.TEXT_50);
            ArmoryTheme.text(guiGraphics, this.font,
                    Component.translatable("gui.mcpskins.armory.no_matches_hint").getString(),
                    stage.x0() + PAD, stage.y0() + PAD + 12, ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
            return;
        }

        int accent = ArmoryTheme.readable(entry.labelColor());
        int textBlockH = Math.round(8f * layout.tier().nameScale) + 12 + (layout.tier().lore ? 20 : 0);
        int podiumH = Math.max(40, stage.height() - textBlockH - PAD * 2);

        // Floor pool, tinted by rarity, so the model does not read as floating.
        int glowW = Math.min(stage.width() - 2, 160);
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.GLOW,
                stage.x0() + (stage.width() - glowW) / 2, stage.y0() + podiumH - 18, glowW, 24,
                ArmoryTheme.withAlpha(accent, 0.22f));

        // The podium scissors to these bounds, so this has to clear the frame.
        podium.setBounds(stage.x0() + STAGE_INSET, stage.y0() + STAGE_INSET,
                stage.width() - STAGE_INSET * 2, podiumH - STAGE_INSET);
        podium.render(guiGraphics, 0f, accent);

        int textY = stage.y0() + podiumH + GAP_TIGHT;
        String collection = entry.hasCollection() ? SkinTranslations.collection(entry).toUpperCase(Locale.ROOT) : "";
        if (!collection.isEmpty()) {
            ArmoryTheme.text(guiGraphics, this.font, collection, stage.x0() + PAD, textY,
                    ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
        }
        int nameY = textY + (collection.isEmpty() ? 0 : 8);
        ArmoryTheme.text(guiGraphics, this.font,
                ArmoryTheme.truncate(this.font, SkinTranslations.name(entry), stage.width() - PAD * 2, layout.tier().nameScale),
                stage.x0() + PAD, nameY, layout.tier().nameScale, ArmoryTheme.TEXT);

        int rarityY = nameY + Math.round(8f * layout.tier().nameScale) + 3;
        String rarity = RarityManager.INSTANCE.get(entry.rarityId()).label().getString();
        guiGraphics.fill(stage.x0() + PAD, rarityY + 2, stage.x0() + PAD + 3, rarityY + 5, accent);
        ArmoryTheme.text(guiGraphics, this.font, rarity, stage.x0() + PAD + 6, rarityY,
                ArmoryTheme.SMALL, accent);

        if (layout.tier().lore && entry.hasDescription()) {
            ArmoryTheme.text(guiGraphics, this.font,
                    ArmoryTheme.truncate(this.font, SkinTranslations.description(entry), stage.width() - PAD * 2, ArmoryTheme.SMALL),
                    stage.x0() + PAD, rarityY + 10, ArmoryTheme.SMALL, ArmoryTheme.TEXT_50);
        }

        if (statusMessage != null) {
            ArmoryTheme.text(guiGraphics, this.font,
                    ArmoryTheme.truncate(this.font, statusMessage, stage.width() - PAD * 2, ArmoryTheme.SMALL),
                    stage.x0() + PAD, stage.y1() - 10, ArmoryTheme.SMALL, 0xFFFF8080);
        } else if (layout.tier().lore) {
            ArmoryTheme.textRight(guiGraphics, this.font,
                    Component.translatable("gui.mcpskins.armory.stage_hint").getString(),
                    stage.x1() - PAD, stage.y1() - 9, ArmoryTheme.SMALL, ArmoryTheme.TEXT_28);
        }
    }

    private void renderTiles(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        Rect detail = new Rect(layout.tiles().x0(), layout.tiles().y0() - SECTION_H,
                layout.tiles().x1(), layout.tiles().y1());
        ArmoryTheme.text(guiGraphics, this.font,
                Component.translatable("gui.mcpskins.armory.skins").getString(),
                detail.x0(), detail.y0() + 2, ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, detail.x0(), detail.y0() + SECTION_H - 3,
                detail.width(), 1, ArmoryTheme.RULE);

        GunGroup group = findGroup(selectedGun);
        if (group == null) return;

        Rect tiles = layout.tiles();
        int tileW = (tiles.width() - GAP_TIGHT) / 2;
        Player player = Minecraft.getInstance().player;

        guiGraphics.enableScissor(tiles.x0(), tiles.y0(), tiles.x1(), tiles.y1());
        try {
            List<SkinDataModels.SkinEntry> skins = group.skins();
            for (int i = 0; i < skins.size(); i++) {
                int col = i % 2;
                int rowIndex = i / 2;
                int x = tiles.x0() + col * (tileW + GAP_TIGHT);
                int y = tiles.y0() + rowIndex * (TILE_H + GAP_TIGHT) - tileScroll;
                if (y + TILE_H < tiles.y0() || y > tiles.y1()) continue;

                SkinDataModels.SkinEntry entry = skins.get(i);
                boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
                boolean selected = entry.id().equals(selectedSkinId);
                boolean equipped = isSkinCurrentlyEquipped(group.weapon(), entry);
                boolean hovered = mouseX >= x && mouseX < x + tileW && mouseY >= y && mouseY < y + TILE_H;

                ArmoryTheme.sprite(guiGraphics, ArmoryTheme.TILE, x, y, tileW, TILE_H);
                ItemStack thumb = previewStack(group.weapon().baseGun(), entry.id());
                guiGraphics.renderItem(thumb, x + tileW / 2 - 8, y + TILE_H / 2 - 8);

                if (!unlocked) {
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, x + 1, y + 1,
                            tileW - 2, TILE_H - 2, ArmoryTheme.LOCKED_DIM);
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_LOCK,
                            x + tileW - ICON - 3, y + TILE_H - ICON - 3, ICON, ICON, ArmoryTheme.TEXT_50);
                }

                int ring = selected ? ArmoryTheme.TEXT
                        : equipped ? ArmoryTheme.ACCENT
                        : hovered ? ArmoryTheme.TEXT_50
                        : ArmoryTheme.withAlpha(ArmoryTheme.readable(entry.labelColor()), 0.45f);
                ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.TILE_RING, x, y, tileW, TILE_H, ring);

                if (equipped) {
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_CHECK,
                            x + 3, y + TILE_H - ICON - 3, ICON, ICON, ArmoryTheme.ACCENT);
                }
                if (entry.isNew()) {
                    ArmoryTheme.text(guiGraphics, this.font,
                            Component.translatable("gui.mcpskins.armory.badge_new").getString(),
                            x + 3, y + 4, ArmoryTheme.SMALL, ArmoryTheme.ACCENT_LIFT);
                }
                if (hasCustomModel(group.weapon(), entry)) {
                    ArmoryTheme.textRight(guiGraphics, this.font,
                            Component.translatable("gui.mcpskins.armory.badge_model").getString(),
                            x + tileW - 3, y + 4, ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderInfo(GuiGraphics guiGraphics, Layout layout) {
        SkinDataModels.SkinEntry entry = selectedSkin();
        if (entry == null) return;
        Rect info = layout.info();
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, info.x0(), info.y0() - 3,
                info.width(), 1, ArmoryTheme.RULE);

        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());

        // Built, not fixed, so a skin with no collection leaves no placeholder row.
        List<String[]> rows = new ArrayList<>();
        if (!unlocked && entry.hasLockedText()) {
            rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_unlock").getString(),
                    SkinTranslations.lockedText(entry)});
        }
        if (entry.hasCollection()) {
            rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_set").getString(),
                    SkinTranslations.collection(entry)});
        }
        rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_rarity").getString(),
                RarityManager.INSTANCE.get(entry.rarityId()).label().getString()});

        // One column for every caption on show, so the values line up instead of stepping.
        int shown = Math.min(2, rows.size());
        int labelW = 0;
        for (int i = 0; i < shown; i++) {
            labelW = Math.max(labelW, ArmoryTheme.scaledWidth(this.font,
                    rows.get(i)[0].toUpperCase(Locale.ROOT), ArmoryTheme.SMALL));
        }
        labelW = Math.min(labelW + GAP_TIGHT, info.width() / 2);

        for (int i = 0; i < shown; i++) {
            infoRow(guiGraphics, info, i, labelW, rows.get(i)[0], rows.get(i)[1]);
        }
    }

    /** {@code labelW} is measured across the whole block, so a longer caption in one language
     *  widens the column rather than running into its own value. */
    private void infoRow(GuiGraphics guiGraphics, Rect info, int index, int labelW, String label, String value) {
        int y = info.y0() + index * INFO_ROW_H;
        ArmoryTheme.text(guiGraphics, this.font,
                ArmoryTheme.truncate(this.font, label.toUpperCase(Locale.ROOT), labelW - GAP_TIGHT, ArmoryTheme.SMALL),
                info.x0(), y, ArmoryTheme.SMALL, ArmoryTheme.TEXT_28);
        ArmoryTheme.text(guiGraphics, this.font,
                ArmoryTheme.truncate(this.font, value, info.width() - labelW, ArmoryTheme.SMALL),
                info.x0() + labelW, y, ArmoryTheme.SMALL, ArmoryTheme.TEXT_72);
    }

    private void renderEquip(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        Rect equip = layout.equip();
        SkinDataModels.SkinEntry entry = selectedSkin();
        Player player = Minecraft.getInstance().player;
        boolean unlocked = entry != null && player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
        boolean equipped = entry != null && isSkinCurrentlyEquipped(findGroup(selectedGun).weapon(), entry);
        boolean enabled = canEquipSelected();
        boolean hovered = equip.contains(mouseX, mouseY);

        ArmoryTheme.sprite(guiGraphics, enabled || equipped ? ArmoryTheme.CHIP_ON : ArmoryTheme.CHIP,
                equip.x0(), equip.y0(), equip.width(), equip.height());
        if (enabled && hovered) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW,
                    equip.x0(), equip.y0(), equip.width(), equip.height(), ArmoryTheme.ROW_HOVER);
        }

        Component label = !unlocked ? Component.translatable("gui.mcpskins.armory.locked")
                : equipped ? Component.translatable("gui.mcpskins.armory.equipped")
                : Component.translatable("gui.mcpskins.armory.equip");
        int color = !unlocked ? ArmoryTheme.TEXT_35 : enabled || equipped ? ArmoryTheme.TEXT : ArmoryTheme.TEXT_50;

        int labelW = this.font.width(label);
        int iconRoom = !unlocked || equipped ? ICON + GAP_TIGHT : 0;
        int startX = (equip.x0() + equip.x1() - labelW - iconRoom) / 2;
        if (!unlocked) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_LOCK, startX,
                    equip.y0() + (equip.height() - ICON) / 2, ICON, ICON, color);
        } else if (equipped) {
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ICON_CHECK, startX,
                    equip.y0() + (equip.height() - ICON) / 2, ICON, ICON, ArmoryTheme.ACCENT);
        }
        guiGraphics.drawString(this.font, label, startX + iconRoom,
                equip.y0() + (equip.height() - this.font.lineHeight) / 2 + 1, color, false);
    }

    private void renderTooltips(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        RailRow row = railRowAt(layout, mouseX, mouseY);
        if (row != null && row.skinId() == null) {
            // Every row, not only truncated ones: conditional tooltips read as arbitrary, and
            // the count keeps it useful when the name already fits.
            GunGroup group = findGroup(row.gunId());
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(weaponDisplayName(row.gunId())));
            if (group != null) {
                lines.add(Component.translatable("gui.mcpskins.armory.skin_count",
                        group.skins().size()).withStyle(ChatFormatting.GRAY));
            }
            guiGraphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
            return;
        }

        SkinDataModels.SkinEntry hovered = tileAt(layout, mouseX, mouseY);
        if (hovered == null) return;
        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, hovered.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(SkinTranslations.name(hovered)).withStyle(s -> s.withColor(hovered.labelColor())));
        lines.add(RarityManager.INSTANCE.get(hovered.rarityId()).label());
        if (!unlocked) {
            lines.add(hovered.hasLockedText()
                    ? Component.literal(SkinTranslations.lockedText(hovered)).withStyle(ChatFormatting.GRAY)
                    : Component.translatable("gui.mcpskins.armory.status_locked").withStyle(ChatFormatting.RED));
        }
        guiGraphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    // -----------------------------------------------------------------------------------
    // Mouse input
    // -----------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) return false;

        Layout layout = computeLayout();

        for (StatusFilter filter : StatusFilter.values()) {
            if (layout.filters()[filter.ordinal()].contains(mouseX, mouseY)) {
                statusFilter = filter;
                railScroll = 0;
                tileScroll = 0;
                playClick();
                rebuild();
                return true;
            }
        }
        if (layout.custom().contains(mouseX, mouseY)) {
            customModelOnly = !customModelOnly;
            railScroll = 0;
            tileScroll = 0;
            playClick();
            rebuild();
            return true;
        }
        if (layout.sort().contains(mouseX, mouseY)) {
            SortMode[] values = SortMode.values();
            sortMode = values[(sortMode.ordinal() + 1) % values.length];
            playClick();
            rebuild();
            return true;
        }

        RailRow row = railRowAt(layout, mouseX, mouseY);
        if (row != null) {
            focusPane = FocusPane.RAIL;
            if (row.skinId() == null) {
                selectGun(row.gunId());
            } else {
                selectedGun = row.gunId();
                selectedSkinId = row.skinId();
                statusMessage = null;
                updatePodiumStack();
                rebuild();
            }
            playClick();
            return true;
        }

        if (podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseClicked();
            return true;
        }

        SkinDataModels.SkinEntry tile = tileAt(layout, mouseX, mouseY);
        if (tile != null) {
            focusPane = FocusPane.TILES;
            selectedSkinId = tile.id();
            statusMessage = null;
            updatePodiumStack();
            rebuild();
            playClick();
            return true;
        }

        if (layout.equip().contains(mouseX, mouseY)) {
            equipSelected();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseDragged(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            podium.onMouseReleased();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Layout layout = computeLayout();
        if (podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseScrolled(scrollY);
            return true;
        }
        if (layout.railList().contains(mouseX, mouseY)) {
            railScroll = Mth.clamp(railScroll - (int) (scrollY * 18), 0, maxRailScroll(layout));
            return true;
        }
        if (layout.tiles().contains(mouseX, mouseY)) {
            tileScroll = Mth.clamp(tileScroll - (int) (scrollY * 18), 0, maxTileScroll(layout));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private RailRow railRowAt(Layout layout, double mouseX, double mouseY) {
        Rect list = layout.railList();
        if (!list.contains(mouseX, mouseY)) return null;
        int relative = (int) (mouseY - list.y0() + railScroll);
        for (RailRow row : railRows) {
            if (relative >= row.top() && relative < row.top() + row.height()) return row;
        }
        return null;
    }

    private SkinDataModels.SkinEntry tileAt(Layout layout, double mouseX, double mouseY) {
        Rect tiles = layout.tiles();
        if (!tiles.contains(mouseX, mouseY)) return null;
        GunGroup group = findGroup(selectedGun);
        if (group == null) return null;

        int tileW = (tiles.width() - GAP_TIGHT) / 2;
        int relX = (int) (mouseX - tiles.x0());
        int relY = (int) (mouseY - tiles.y0() + tileScroll);
        int col = relX / (tileW + GAP_TIGHT);
        int row = relY / (TILE_H + GAP_TIGHT);
        if (col < 0 || col > 1 || row < 0) return null;
        if (relX - col * (tileW + GAP_TIGHT) > tileW) return null;
        if (relY - row * (TILE_H + GAP_TIGHT) > TILE_H) return null;

        int index = row * 2 + col;
        return index < group.skins().size() ? group.skins().get(index) : null;
    }

    private int maxRailScroll(Layout layout) {
        if (railRows.isEmpty()) return 0;
        RailRow last = railRows.get(railRows.size() - 1);
        return Math.max(0, last.top() + last.height() - layout.railList().height());
    }

    private int maxTileScroll(Layout layout) {
        GunGroup group = findGroup(selectedGun);
        if (group == null) return 0;
        int rows = (group.skins().size() + 1) / 2;
        return Math.max(0, rows * (TILE_H + GAP_TIGHT) - GAP_TIGHT - layout.tiles().height());
    }

    private void selectGun(String gunId) {
        this.selectedGun = gunId;
        this.selectedSkinId = null;
        this.tileScroll = 0;
        this.statusMessage = null;
        rebuild();
    }

    private void scrollRailToSelection(Layout layout) {
        for (RailRow row : railRows) {
            boolean match = row.skinId() == null
                    ? row.gunId().equals(selectedGun)
                    : row.skinId().equals(selectedSkinId);
            if (!match) continue;
            int viewHeight = layout.railList().height();
            if (row.top() < railScroll) {
                railScroll = row.top();
            } else if (row.top() + row.height() > railScroll + viewHeight) {
                railScroll = row.top() + row.height() - viewHeight;
            }
            railScroll = Mth.clamp(railScroll, 0, maxRailScroll(layout));
            if (row.skinId() != null) return;
        }
    }

    private void scrollTilesToSelection(Layout layout) {
        GunGroup group = findGroup(selectedGun);
        int index = indexOfSkin(group, selectedSkinId);
        if (index < 0) return;
        int top = (index / 2) * (TILE_H + GAP_TIGHT);
        int viewHeight = layout.tiles().height();
        if (top < tileScroll) {
            tileScroll = top;
        } else if (top + TILE_H > tileScroll + viewHeight) {
            tileScroll = top + TILE_H - viewHeight;
        }
        tileScroll = Mth.clamp(tileScroll, 0, maxTileScroll(layout));
    }

    // -----------------------------------------------------------------------------------
    // Keyboard input
    // -----------------------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            focusPane = focusPane == FocusPane.RAIL ? FocusPane.TILES : FocusPane.RAIL;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            handleNavigation(keyCode);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focusPane == FocusPane.TILES) {
                equipSelected();
            } else {
                focusPane = FocusPane.TILES;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void handleNavigation(int keyCode) {
        Layout layout = computeLayout();
        if (focusPane == FocusPane.RAIL) {
            if (groups.isEmpty()) return;
            int index = 0;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).weapon().baseGun().equals(selectedGun)) {
                    index = i;
                    break;
                }
            }
            if (keyCode == GLFW.GLFW_KEY_UP) index = Math.max(0, index - 1);
            else if (keyCode == GLFW.GLFW_KEY_DOWN) index = Math.min(groups.size() - 1, index + 1);
            selectGun(groups.get(index).weapon().baseGun());
            scrollRailToSelection(computeLayout());
            return;
        }

        GunGroup group = findGroup(selectedGun);
        if (group == null || group.skins().isEmpty()) return;
        int index = Math.max(0, indexOfSkin(group, selectedSkinId));
        if (keyCode == GLFW.GLFW_KEY_LEFT) index -= 1;
        else if (keyCode == GLFW.GLFW_KEY_RIGHT) index += 1;
        else if (keyCode == GLFW.GLFW_KEY_UP) index -= 2;
        else if (keyCode == GLFW.GLFW_KEY_DOWN) index += 2;
        index = Mth.clamp(index, 0, group.skins().size() - 1);
        selectedSkinId = group.skins().get(index).id();
        statusMessage = null;
        updatePodiumStack();
        rebuild();
        scrollTilesToSelection(layout);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // -----------------------------------------------------------------------------------
    // Equipping
    // -----------------------------------------------------------------------------------

    /**
     * Browsing needs no weapon in hand; the stage is a synthetic preview. Equipping does, since
     * the server applies the skin to whichever hand holds the gun.
     */
    private void equipSelected() {
        SkinDataModels.SkinEntry entry = selectedSkin();
        GunGroup group = findGroup(selectedGun);
        Player player = Minecraft.getInstance().player;
        if (entry == null || group == null || player == null) return;

        if (!SkinAttachment.isOwnedOrDefault(player, entry.id())) {
            statusMessage = entry.hasLockedText()
                    ? SkinTranslations.lockedText(entry)
                    : Component.translatable("gui.mcpskins.armory.status_locked").getString();
            playFail();
            return;
        }

        InteractionHand hand = resolveHand(player, group.weapon().baseGun());
        if (hand == null) {
            statusMessage = Component.translatable("gui.mcpskins.armory.status_need_hand",
                    weaponDisplayName(group.weapon().baseGun())).getString();
            playFail();
            return;
        }

        // Optimistic: set the component locally rather than waiting for the server echo.
        ItemStack held = player.getItemInHand(hand);
        ItemStack optimistic = TACZSkinHelper.applySkin(held, entry.id());
        if (!optimistic.isEmpty()) {
            player.setItemInHand(hand, optimistic);
        }
        PacketDistributor.sendToServer(isDefaultSkin(group.weapon(), entry)
                ? ApplySkinPayload.removeSkin()
                : ApplySkinPayload.equip(entry.id()));
        statusMessage = null;
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
    }

    private boolean canEquipSelected() {
        SkinDataModels.SkinEntry entry = selectedSkin();
        GunGroup group = findGroup(selectedGun);
        Player player = Minecraft.getInstance().player;
        if (entry == null || group == null || player == null) return false;
        if (!SkinAttachment.isOwnedOrDefault(player, entry.id())) return false;
        return resolveHand(player, group.weapon().baseGun()) != null;
    }

    private InteractionHand resolveHand(Player player, String baseGun) {
        if (baseGun.equals(TACZSkinHelper.getTaczId(player.getMainHandItem()))) return InteractionHand.MAIN_HAND;
        if (baseGun.equals(TACZSkinHelper.getTaczId(player.getOffhandItem()))) return InteractionHand.OFF_HAND;
        return null;
    }

    private boolean isWeaponCurrentlyHeld(String baseGun) {
        Player player = Minecraft.getInstance().player;
        return player != null && resolveHand(player, baseGun) != null;
    }

    private boolean isSkinCurrentlyEquipped(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        InteractionHand hand = resolveHand(player, weapon.baseGun());
        if (hand == null) return false;
        String equippedSkinId = TACZSkinHelper.getSkinId(player.getItemInHand(hand));
        String normalized = equippedSkinId == null ? weapon.baseGun() : equippedSkinId;
        return normalized.equals(TACZSkinHelper.bareSkinId(entry.id()));
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private SkinDataModels.SkinEntry skinById(String gunId, String skinId) {
        GunGroup group = findGroup(gunId);
        int index = indexOfSkin(group, skinId);
        return index < 0 ? null : group.skins().get(index);
    }

    /**
     * Cached for the life of the screen. The stack depends only on the two ids, not on whether
     * the skin's assets have streamed in, so it never goes stale.
     */
    private ItemStack previewStack(String baseGun, String skinId) {
        String key = baseGun + SEPARATOR + (skinId == null ? "" : skinId);
        return stackCache.computeIfAbsent(key, ignored ->
                TACZSkinHelper.createStack(baseGun, skinId, SkinManager.INSTANCE.targetOf(baseGun)));
    }

    private String weaponDisplayName(String baseGun) {
        return weaponNameCache.computeIfAbsent(baseGun, key -> {
            ItemStack stack = previewStack(key, null);
            return stack.isEmpty() ? key : stack.getHoverName().getString();
        });
    }

    /** The weapon's default skin - {@code bareSkinId(id)} equals the weapon's own baseGun. */
    private boolean isDefaultSkin(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        return TACZSkinHelper.bareSkinId(entry.id()).equals(weapon.baseGun());
    }

    /**
     * Resolved through the real render path so the badge cannot disagree with it. Keyed on
     * {@link ClientSkinAssetCache#generation()}: the first check usually runs while the
     * geo-model is still in flight, and pinning that {@code false} would stick.
     */
    private boolean hasCustomModel(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        String bare = TACZSkinHelper.bareSkinId(entry.id());
        if (bare.equals(weapon.baseGun())) return false;

        int generation = ClientSkinAssetCache.generation();
        String cacheKey = weapon.baseGun() + SEPARATOR + bare;
        CustomModelResult cached = customModelCache.get(cacheKey);
        if (cached != null && cached.generation() == generation) return cached.hasModel();

        boolean result = false;
        try {
            ResourceLocation baseModelLocation = baseModelLocation(weapon);
            result = baseModelLocation != null && SkinAssetResolver.resolveModel(baseModelLocation, bare) != null;
        } catch (RuntimeException e) {
            // Badge just doesn't show. Debug level - this runs per visible tile.
            MCPSkins.LOGGER.debug("[MCPSkins] Custom-model badge check failed for '{}'.", cacheKey, e);
        }
        customModelCache.put(cacheKey, new CustomModelResult(generation, result));
        return result;
    }

    /** Where a skin's replacement geometry would have to sit, for either kind of base item. */
    private ResourceLocation baseModelLocation(SkinDataModels.WeaponSkins weapon) {
        if (weapon.isAttachment()) {
            ResourceLocation id = ResourceLocation.tryParse(weapon.baseGun());
            return id == null ? null : TimelessAPI.getClientAttachmentIndex(id)
                    .map(ClientAttachmentIndexPatcher::getBaseModelLocation).orElse(null);
        }
        return TimelessAPI.getGunDisplay(previewStack(weapon.baseGun(), null))
                .map(GunModelPatcher::getBaseModelLocation).orElse(null);
    }

    private Component statusFilterLabel(StatusFilter filter, Tier tier) {
        boolean shortLabel = tier == Tier.COMPACT;
        return switch (filter) {
            case ALL -> Component.translatable(shortLabel
                    ? "gui.mcpskins.armory.filter_all_short" : "gui.mcpskins.armory.filter_all");
            case OWNED -> Component.translatable(shortLabel
                    ? "gui.mcpskins.armory.filter_owned_short" : "gui.mcpskins.armory.filter_owned");
            case LOCKED -> Component.translatable(shortLabel
                    ? "gui.mcpskins.armory.filter_locked_short" : "gui.mcpskins.armory.filter_locked");
        };
    }

    private Component customLabel(Tier tier) {
        return Component.translatable(tier == Tier.COMPACT
                ? "gui.mcpskins.armory.filter_custom_model_short"
                : "gui.mcpskins.armory.filter_custom_model");
    }

    private Component sortModeLabel(SortMode mode, Tier tier) {
        Component value = switch (mode) {
            case RARITY -> Component.translatable("gui.mcpskins.armory.sort_rarity");
            case ALPHABETICAL -> Component.translatable("gui.mcpskins.armory.sort_alphabetical");
            case NEWEST -> Component.translatable("gui.mcpskins.armory.sort_newest");
        };
        return tier == Tier.COMPACT ? value
                : Component.translatable("gui.mcpskins.armory.sort", value);
    }

    private void playClick() {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
    }

    private void playFail() {
        Player player = Minecraft.getInstance().player;
        if (player != null) player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 0.7f);
    }
}
