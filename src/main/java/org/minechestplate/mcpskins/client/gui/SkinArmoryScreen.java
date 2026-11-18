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
import org.minechestplate.mcpskins.skin.SkinProgress;
import org.minechestplate.mcpskins.skin.SkinTranslations;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.minechestplate.mcpskins.client.gui.ArmoryLayout.*;

/** The Armory: skin catalog and inspector, independent of the held item. Layout in docs/ai/ui.md. */
public class SkinArmoryScreen extends Screen {

    private enum FocusPane { RAIL, TILES }

    // Cannot occur in a gun or skin id, so composite keys never collide.
    private static final char SEPARATOR = '|';

    // A null skinId is the gun row itself.
    private record RailRow(String gunId, String skinId, int top, int height) {
    }

    private record GunGroup(SkinDataModels.WeaponSkins weapon, List<SkinDataModels.SkinEntry> skins) {
    }

    private record CustomModelResult(int generation, boolean hasModel) {
    }

    private static final int MAX_INFO_ROWS = 4;
    private static final int MAX_VARIANT_DOTS = 6;
    private static final int MAX_LEVEL_PIPS = 10;
    // Above the item layer, so overlays are not hidden behind icons.
    private static final float OVERLAY_Z = 200f;

    private final Map<String, String> weaponNameCache = new HashMap<>();
    private final Map<String, ItemStack> stackCache = new HashMap<>();
    private final Map<String, CustomModelResult> customModelCache = new HashMap<>();
    private final Item3DPodiumWidget podium = new Item3DPodiumWidget();

    private final List<GunGroup> groups = new ArrayList<>();
    private final List<RailRow> railRows = new ArrayList<>();

    private String selectedGun;
    // The tile: a plain skin or a variant root, never a variant.
    private String selectedSkinId;
    // A variant of the selected tile being previewed, or null for the tile itself.
    private String selectedVariantId;
    private EditBox searchBox;
    private StatusFilter statusFilter = StatusFilter.ALL;
    private boolean customModelOnly = false;
    private SortMode sortMode = SortMode.RARITY;
    private FocusPane focusPane = FocusPane.TILES;
    private int railScroll = 0;
    private int tileScroll = 0;
    private String statusMessage;

    private final String focusSkinId;

    public SkinArmoryScreen() {
        this(null);
    }

    public SkinArmoryScreen(String focusSkinId) {
        super(Component.translatable("gui.mcpskins.armory.title"));
        this.focusSkinId = focusSkinId;
    }

    @Override
    protected void init() {
        podium.resetView();
        podium.setChrome(false);

        Layout layout = layout();
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
        // Not renderable: renderHeader draws it in a scissor, since EditBox draws its hint unclipped.
        this.addWidget(searchBox);

        SkinDataModels.SkinLookupResult focus = focusSkinId != null
                ? SkinManager.INSTANCE.findSkin(focusSkinId) : null;
        if (focus != null) {
            selectedGun = focus.weapon().baseGun();
            if (focus.skin().isVariant()) {
                selectedSkinId = focus.skin().rootId();
                selectedVariantId = focus.skin().id();
            } else {
                selectedSkinId = focus.skin().id();
            }
        } else if (selectedGun == null) {
            selectedGun = defaultWeaponSelection();
        }

        rebuild();
        scrollRailToSelection(layout());
    }

    // A non-empty search is global: every matching gun expands, not only the selected one.
    private void rebuild() {
        groups.clear();
        railRows.clear();

        Player player = Minecraft.getInstance().player;
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        boolean searching = !query.isEmpty();

        // Guns first, so attachments form their own section.
        List<SkinDataModels.WeaponSkins> weapons = new ArrayList<>(SkinManager.INSTANCE.getRegistry().values());
        weapons.sort(Comparator.comparing(SkinDataModels.WeaponSkins::isAccessory)
                .thenComparing(w -> weaponDisplayName(w.baseGun()), String.CASE_INSENSITIVE_ORDER));

        for (SkinDataModels.WeaponSkins weapon : weapons) {
            boolean weaponMatches = !searching
                    || weaponDisplayName(weapon.baseGun()).toLowerCase(Locale.ROOT).contains(query);
            List<SkinDataModels.SkinEntry> matched = new ArrayList<>();
            for (SkinDataModels.SkinEntry entry : weapon.skins()) {
                // Variants have no tile: their root stands for the whole family.
                if (isTiledVariant(weapon, entry)) continue;
                boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
                if (statusFilter == StatusFilter.OWNED && !unlocked) continue;
                if (statusFilter == StatusFilter.LOCKED && unlocked) continue;
                if (customModelOnly && !familyHasCustomModel(weapon, entry)) continue;
                if (searching && !weaponMatches && !familyMatches(weapon, entry, query)) {
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
        SkinDataModels.SkinEntry tile = selectedSkin();
        if (selectedVariantId != null && (tile == null || !tile.variants().contains(selectedVariantId))) {
            selectedVariantId = null;
        }

        int top = 0;
        for (GunGroup group : groups) {
            boolean expanded = searching || group.weapon().baseGun().equals(selectedGun);
            railRows.add(new RailRow(group.weapon().baseGun(), null, top, tierFor(this.width).gunRowHeight));
            top += tierFor(this.width).gunRowHeight;
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

    private SkinDataModels.SkinEntry displayedSkin() {
        SkinDataModels.SkinEntry tile = selectedSkin();
        if (tile == null || selectedVariantId == null) return tile;
        SkinDataModels.SkinEntry variant = entryInWeapon(findGroup(selectedGun).weapon(), selectedVariantId);
        return variant != null ? variant : tile;
    }

    private List<SkinDataModels.SkinEntry> family(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry tile) {
        List<SkinDataModels.SkinEntry> family = new ArrayList<>();
        family.add(tile);
        for (String variantId : tile.variants()) {
            SkinDataModels.SkinEntry variant = entryInWeapon(weapon, variantId);
            if (variant != null) family.add(variant);
        }
        return family;
    }

    // A variant whose root is on this weapon, and so is reached through the root's tile.
    private boolean isTiledVariant(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        return entry.isVariant() && entryInWeapon(weapon, entry.rootId()) != null;
    }

    private boolean familyMatches(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry tile, String query) {
        for (SkinDataModels.SkinEntry member : family(weapon, tile)) {
            if (SkinTranslations.name(member).toLowerCase(Locale.ROOT).contains(query)
                    || member.author().toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private boolean familyHasCustomModel(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry tile) {
        for (SkinDataModels.SkinEntry member : family(weapon, tile)) {
            if (hasCustomModel(weapon, member)) return true;
        }
        return false;
    }

    private static SkinDataModels.SkinEntry entryInWeapon(SkinDataModels.WeaponSkins weapon, String skinId) {
        if (skinId == null) return null;
        for (SkinDataModels.SkinEntry entry : weapon.skins()) {
            if (entry.id().equals(skinId)) return entry;
        }
        return null;
    }

    private void updatePodiumStack() {
        SkinDataModels.SkinEntry entry = displayedSkin();
        podium.setStack(entry == null ? ItemStack.EMPTY : previewStack(selectedGun, entry.id()));
    }

    private Layout layout() {
        return compute(this.width, this.height, this.font, sortMode, detailContent());
    }

    private DetailContent detailContent() {
        SkinDataModels.SkinEntry tile = selectedSkin();
        GunGroup group = findGroup(selectedGun);
        if (tile == null || group == null) return DetailContent.PLAIN;
        List<SkinDataModels.SkinEntry> family = family(group.weapon(), tile);
        int swatches = tile.hasVariants() ? family.size() : 0;
        int progressRows = 0;
        if (!tile.levels().isEmpty()) {
            SkinDataModels.SkinLevel next = nextLevel(tile);
            progressRows = 1 + (next == null ? 0 : next.requires().size());
        }
        // Sized for the tallest member, so flipping between swatches never moves the column.
        int infoRows = 0;
        for (SkinDataModels.SkinEntry member : family) {
            infoRows = Math.max(infoRows, infoRows(member).size());
        }
        return new DetailContent(Math.min(MAX_INFO_ROWS, infoRows), swatches, progressRows);
    }

    private SkinDataModels.SkinLevel nextLevel(SkinDataModels.SkinEntry root) {
        int index = SkinProgress.levelFor(root, progressOf(root)) - 1;
        return index < root.levels().size() ? root.levels().get(index) : null;
    }

    private SkinProgress.Counters progressOf(SkinDataModels.SkinEntry root) {
        Player player = Minecraft.getInstance().player;
        return player == null ? SkinProgress.Counters.ZERO : SkinProgress.get(player, root.id());
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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xC00A0A0C, 0xE0050506);

        Layout layout = layout();
        Rect panel = layout.panel();

        ArmoryTheme.sprite(guiGraphics, ArmoryTheme.PANEL, panel.x0(), panel.y0(), panel.width(), panel.height());

        renderHeader(guiGraphics, layout, mouseX, mouseY);
        renderRail(guiGraphics, layout, mouseX, mouseY);
        renderStage(guiGraphics, layout);
        renderTiles(guiGraphics, layout, mouseX, mouseY);
        renderVariants(guiGraphics, layout, mouseX, mouseY);
        renderProgress(guiGraphics, layout);
        renderInfo(guiGraphics, layout);
        renderEquip(guiGraphics, layout, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        renderTooltips(guiGraphics, layout, mouseX, mouseY);
    }

    // No-op: skips vanilla's background blur.
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

        if (searchBox != null) {
            guiGraphics.enableScissor(search.x0(), search.y0(), search.x1(), search.y1());
            try {
                searchBox.render(guiGraphics, mouseX, mouseY, 0f);
            } finally {
                guiGraphics.disableScissor();
            }
        }

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

        // A colour spine, not coloured text, so a dim pack colour stays legible.
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

        SkinDataModels.SkinEntry entry = displayedSkin();
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
        // The tile area shrinks when a skin with variants or levels is selected.
        tileScroll = Mth.clamp(tileScroll, 0, maxTileScroll(layout));

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
                boolean equipped = isFamilyCurrentlyEquipped(group.weapon(), entry);
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
                if (entry.limited()) {
                    renderLimitedBadge(guiGraphics, x + 3, y + (entry.isNew() ? 12 : 4));
                }
                if (entry.hasVariants()) {
                    renderVariantDots(guiGraphics, group.weapon(), entry, player, x + tileW / 2, y + TILE_H - 4);
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

    private void renderLimitedBadge(GuiGraphics guiGraphics, int x, int y) {
        String label = Component.translatable("gui.mcpskins.armory.badge_limited").getString();
        int w = ArmoryTheme.scaledWidth(this.font, label, ArmoryTheme.SMALL);
        guiGraphics.pose().pushPose();
        try {
            guiGraphics.pose().translate(0f, 0f, OVERLAY_Z);
            ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, x - 1, y - 1, w + 3, 8, ArmoryTheme.LIMITED_PLATE);
            ArmoryTheme.text(guiGraphics, this.font, label, x + 1, y + 1, ArmoryTheme.SMALL, ArmoryTheme.LIMITED);
        } finally {
            guiGraphics.pose().popPose();
        }
    }

    private void renderVariantDots(GuiGraphics guiGraphics, SkinDataModels.WeaponSkins weapon,
                                   SkinDataModels.SkinEntry tile, Player player, int centerX, int y) {
        List<SkinDataModels.SkinEntry> family = family(weapon, tile);
        int count = Math.min(MAX_VARIANT_DOTS, family.size() - 1);
        if (count <= 0) return;
        int x = centerX - (count * 4 - 2) / 2;
        for (int i = 0; i < count; i++) {
            boolean owned = player != null && SkinAttachment.hasSkin(player, family.get(i + 1).id());
            guiGraphics.fill(x + i * 4, y, x + i * 4 + 2, y + 2, owned ? ArmoryTheme.TEXT_72 : ArmoryTheme.TEXT_28);
        }
    }

    private void renderVariants(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        Rect strip = layout.variants();
        SkinDataModels.SkinEntry tile = selectedSkin();
        GunGroup group = findGroup(selectedGun);
        if (strip.height() <= 0 || tile == null || group == null) return;

        ArmoryTheme.text(guiGraphics, this.font,
                Component.translatable("gui.mcpskins.armory.variants").getString(),
                strip.x0(), strip.y0() + 2, ArmoryTheme.SMALL, ArmoryTheme.TEXT_35);
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, strip.x0(), strip.y0() + SECTION_H - 3,
                strip.width(), 1, ArmoryTheme.RULE);

        Player player = Minecraft.getInstance().player;
        SkinDataModels.SkinEntry shown = displayedSkin();
        List<SkinDataModels.SkinEntry> family = family(group.weapon(), tile);
        int level = tile.levels().isEmpty() ? 1 : SkinProgress.levelFor(tile, progressOf(tile));
        for (int i = 0; i < family.size(); i++) {
            Rect swatch = swatchRect(layout, i);
            if (swatch == null) break;
            SkinDataModels.SkinEntry member = family.get(i);
            boolean owned = player != null && SkinAttachment.isOwnedOrDefault(player, member.id());
            boolean selected = shown != null && member.id().equals(shown.id());
            boolean equipped = isSkinCurrentlyEquipped(group.weapon(), member);
            boolean hovered = swatch.contains(mouseX, mouseY);

            ArmoryTheme.sprite(guiGraphics, ArmoryTheme.TILE, swatch.x0(), swatch.y0(), swatch.width(), swatch.height());
            guiGraphics.renderItem(previewStack(group.weapon().baseGun(), member.id()), swatch.x0() + 1, swatch.y0() + 1);

            guiGraphics.pose().pushPose();
            try {
                guiGraphics.pose().translate(0f, 0f, OVERLAY_Z);
                if (!owned) {
                    ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.ROW, swatch.x0() + 1, swatch.y0() + 1,
                            swatch.width() - 2, swatch.height() - 2, ArmoryTheme.LOCKED_DIM);
                }
                int ring = selected ? ArmoryTheme.TEXT
                        : equipped ? ArmoryTheme.ACCENT
                        : hovered ? ArmoryTheme.TEXT_50
                        : ArmoryTheme.withAlpha(ArmoryTheme.readable(member.labelColor()), 0.45f);
                ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.TILE_RING, swatch.x0(), swatch.y0(),
                        swatch.width(), swatch.height(), ring);
                int memberLevel = tile.levelOf(member.id());
                if (memberLevel > 0) {
                    ArmoryTheme.text(guiGraphics, this.font, String.valueOf(memberLevel), swatch.x0() + 2,
                            swatch.y0() + 2, ArmoryTheme.SMALL,
                            memberLevel <= level ? ArmoryTheme.ACCENT_LIFT : ArmoryTheme.TEXT_50);
                }
            } finally {
                guiGraphics.pose().popPose();
            }
        }
    }

    // Null past the rows the strip has room for.
    private Rect swatchRect(Layout layout, int index) {
        Rect strip = layout.variants();
        int perRow = swatchesPerRow(strip.width());
        int row = index / perRow;
        if (row >= MAX_SWATCH_ROWS) return null;
        int x = strip.x0() + (index % perRow) * (SWATCH + SWATCH_GAP);
        int y = strip.y0() + SECTION_H + row * (SWATCH + SWATCH_GAP);
        if (y + SWATCH > strip.y1()) return null;
        return new Rect(x, y, x + SWATCH, y + SWATCH);
    }

    private SkinDataModels.SkinEntry swatchAt(Layout layout, double mouseX, double mouseY) {
        SkinDataModels.SkinEntry tile = selectedSkin();
        GunGroup group = findGroup(selectedGun);
        if (tile == null || group == null || layout.variants().height() <= 0
                || !layout.variants().contains(mouseX, mouseY)) {
            return null;
        }
        List<SkinDataModels.SkinEntry> family = family(group.weapon(), tile);
        for (int i = 0; i < family.size(); i++) {
            Rect swatch = swatchRect(layout, i);
            if (swatch == null) break;
            if (swatch.contains(mouseX, mouseY)) return family.get(i);
        }
        return null;
    }

    private void renderProgress(GuiGraphics guiGraphics, Layout layout) {
        Rect block = layout.progress();
        SkinDataModels.SkinEntry tile = selectedSkin();
        if (block.height() <= 0 || tile == null || tile.levels().isEmpty()) return;
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, block.x0(), block.y0() - 3,
                block.width(), 1, ArmoryTheme.RULE);

        SkinProgress.Counters counters = progressOf(tile);
        int level = SkinProgress.levelFor(tile, counters);
        int max = SkinProgress.maxLevel(tile);
        String caption = (level >= max
                ? Component.translatable("gui.mcpskins.armory.level_max", level)
                : Component.translatable("gui.mcpskins.armory.level", level, max)).getString().toUpperCase(Locale.ROOT);

        int pipsW = max <= MAX_LEVEL_PIPS ? max * 5 - 1 : 0;
        ArmoryTheme.text(guiGraphics, this.font,
                ArmoryTheme.truncate(this.font, caption, block.width() - pipsW - GAP_TIGHT, ArmoryTheme.SMALL),
                block.x0(), block.y0(), ArmoryTheme.SMALL, level >= max ? ArmoryTheme.ACCENT_LIFT : ArmoryTheme.TEXT_72);
        if (pipsW > 0) {
            int x = block.x1() - pipsW;
            for (int i = 1; i <= max; i++) {
                guiGraphics.fill(x, block.y0() + 1, x + 4, block.y0() + 5,
                        i <= level ? ArmoryTheme.ACCENT : ArmoryTheme.TEXT_28);
                x += 5;
            }
        }

        SkinDataModels.SkinLevel next = nextLevel(tile);
        if (next == null) return;
        int row = 1;
        for (Map.Entry<SkinDataModels.LevelCounter, Integer> condition : next.requires().entrySet()) {
            if (row >= layout.progressRows()) break;
            int y = block.y0() + row * PROGRESS_ROW_H;
            int have = Math.min(counters.get(condition.getKey()), condition.getValue());
            String value = have + " / " + condition.getValue();
            int valueW = ArmoryTheme.scaledWidth(this.font, value, ArmoryTheme.SMALL);
            String label = Component.translatable("gui.mcpskins.armory.counter." + condition.getKey().key())
                    .getString().toUpperCase(Locale.ROOT);
            ArmoryTheme.text(guiGraphics, this.font,
                    ArmoryTheme.truncate(this.font, label, block.width() - valueW - GAP_TIGHT, ArmoryTheme.SMALL),
                    block.x0(), y, ArmoryTheme.SMALL, ArmoryTheme.TEXT_28);
            ArmoryTheme.textRight(guiGraphics, this.font, value, block.x1(), y, ArmoryTheme.SMALL, ArmoryTheme.TEXT_72);

            int barY = y + 7;
            guiGraphics.fill(block.x0(), barY, block.x1(), barY + 1, ArmoryTheme.RULE);
            int fill = Math.round(block.width() * (have / (float) condition.getValue()));
            if (fill > 0) {
                guiGraphics.fill(block.x0(), barY, block.x0() + fill, barY + 1, ArmoryTheme.ACCENT);
            }
            row++;
        }
    }

    private void renderInfo(GuiGraphics guiGraphics, Layout layout) {
        SkinDataModels.SkinEntry entry = displayedSkin();
        if (entry == null) return;
        Rect info = layout.info();
        ArmoryTheme.spriteTinted(guiGraphics, ArmoryTheme.RULE_FADE, info.x0(), info.y0() - 3,
                info.width(), 1, ArmoryTheme.RULE);

        List<String[]> rows = infoRows(entry);

        int shown = Math.min(layout.infoRows(), rows.size());
        int labelW = 0;
        for (int i = 0; i < shown; i++) {
            if (rows.get(i)[0] == null) continue;
            labelW = Math.max(labelW, ArmoryTheme.scaledWidth(this.font,
                    rows.get(i)[0].toUpperCase(Locale.ROOT), ArmoryTheme.SMALL));
        }
        labelW = Math.min(labelW + GAP_TIGHT, info.width() / 2);

        for (int i = 0; i < shown; i++) {
            if (rows.get(i)[0] == null) {
                ArmoryTheme.text(guiGraphics, this.font,
                        ArmoryTheme.truncate(this.font, rows.get(i)[1], info.width(), ArmoryTheme.SMALL),
                        info.x0(), info.y0() + i * INFO_ROW_H, ArmoryTheme.SMALL, ArmoryTheme.TEXT_50);
            } else {
                infoRow(guiGraphics, info, i, labelW, rows.get(i)[0], rows.get(i)[1]);
            }
        }
    }

    // In priority order: a short column cuts the tail. A null caption marks the subtitle.
    private List<String[]> infoRows(SkinDataModels.SkinEntry entry) {
        List<String[]> rows = new ArrayList<>();
        if (entry == null) return rows;
        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
        if (!unlocked && entry.hasLockedText()) {
            rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_unlock").getString(),
                    SkinTranslations.lockedText(entry)});
        }
        if (entry.hasSubtitle()) {
            rows.add(new String[]{null, SkinTranslations.subtitle(entry)});
        }
        if (entry.hasCollection()) {
            rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_set").getString(),
                    SkinTranslations.collection(entry)});
        }
        if (entry.hasAuthor()) {
            rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_author").getString(),
                    entry.author()});
        }
        rows.add(new String[]{Component.translatable("gui.mcpskins.armory.info_rarity").getString(),
                RarityManager.INSTANCE.get(entry.rarityId()).label().getString()});
        return rows;
    }

    // labelW spans the whole block, so a long caption widens the column instead of overlapping.
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
        SkinDataModels.SkinEntry entry = displayedSkin();
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
            // Every row, not only truncated ones: a conditional tooltip feels arbitrary.
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

        SkinDataModels.SkinEntry swatch = swatchAt(layout, mouseX, mouseY);
        if (swatch != null) {
            renderSwatchTooltip(guiGraphics, swatch, mouseX, mouseY);
            return;
        }

        SkinDataModels.SkinEntry hovered = tileAt(layout, mouseX, mouseY);
        if (hovered == null) return;
        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, hovered.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(SkinTranslations.name(hovered)).withStyle(s -> s.withColor(hovered.labelColor())));
        lines.add(RarityManager.INSTANCE.get(hovered.rarityId()).label());
        if (hovered.limited()) {
            lines.add(Component.translatable("gui.mcpskins.armory.limited_edition")
                    .withStyle(s -> s.withColor(ArmoryTheme.LIMITED & 0xFFFFFF)));
        }
        if (hovered.hasAuthor()) {
            lines.add(Component.translatable("gui.mcpskins.armory.tooltip_author", hovered.author())
                    .withStyle(ChatFormatting.GRAY));
        }
        if (hovered.hasVariants()) {
            GunGroup group = findGroup(selectedGun);
            int variants = group == null ? hovered.variants().size() : family(group.weapon(), hovered).size() - 1;
            lines.add(Component.translatable("gui.mcpskins.armory.variant_count", variants)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!unlocked) {
            lines.add(hovered.hasLockedText()
                    ? Component.literal(SkinTranslations.lockedText(hovered)).withStyle(ChatFormatting.GRAY)
                    : Component.translatable("gui.mcpskins.armory.status_locked").withStyle(ChatFormatting.RED));
        }
        guiGraphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    private void renderSwatchTooltip(GuiGraphics guiGraphics, SkinDataModels.SkinEntry member, int mouseX, int mouseY) {
        SkinDataModels.SkinEntry tile = selectedSkin();
        Player player = Minecraft.getInstance().player;
        boolean owned = player != null && SkinAttachment.isOwnedOrDefault(player, member.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(SkinTranslations.name(member)).withStyle(s -> s.withColor(member.labelColor())));
        int memberLevel = tile == null ? 0 : tile.levelOf(member.id());
        if (tile != null && member.id().equals(tile.id())) {
            lines.add(Component.translatable("gui.mcpskins.armory.variant_base").withStyle(ChatFormatting.GRAY));
        } else if (memberLevel > 0) {
            boolean reached = SkinProgress.levelFor(tile, progressOf(tile)) >= memberLevel;
            lines.add(Component.translatable(reached
                            ? "gui.mcpskins.armory.level_reached" : "gui.mcpskins.armory.level_requires", memberLevel)
                    .withStyle(reached ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        }
        if (!owned) {
            lines.add(member.hasLockedText()
                    ? Component.literal(SkinTranslations.lockedText(member)).withStyle(ChatFormatting.GRAY)
                    : Component.translatable("gui.mcpskins.armory.status_locked").withStyle(ChatFormatting.RED));
        }
        guiGraphics.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) return false;

        Layout layout = layout();

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

        SkinDataModels.SkinEntry swatch = swatchAt(layout, mouseX, mouseY);
        if (swatch != null) {
            focusPane = FocusPane.TILES;
            selectedVariantId = swatch.id().equals(selectedSkinId) ? null : swatch.id();
            statusMessage = null;
            updatePodiumStack();
            playClick();
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
        Layout layout = layout();
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
        Layout layout = layout();
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
            scrollRailToSelection(layout());
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

    // Needs the gun in hand: the server applies the skin to whichever hand holds it.
    private void equipSelected() {
        SkinDataModels.SkinEntry entry = displayedSkin();
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

        // Optimistic local set; the server's echo confirms it.
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
        SkinDataModels.SkinEntry entry = displayedSkin();
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

    private boolean isFamilyCurrentlyEquipped(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry tile) {
        if (isSkinCurrentlyEquipped(weapon, tile)) return true;
        for (String variantId : tile.variants()) {
            SkinDataModels.SkinEntry variant = entryInWeapon(weapon, variantId);
            if (variant != null && isSkinCurrentlyEquipped(weapon, variant)) return true;
        }
        return false;
    }

    private SkinDataModels.SkinEntry skinById(String gunId, String skinId) {
        GunGroup group = findGroup(gunId);
        int index = indexOfSkin(group, skinId);
        return index < 0 ? null : group.skins().get(index);
    }

    // Cached for the screen's life: the stack depends only on the two ids, never on streamed assets.
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

    private boolean isDefaultSkin(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        return TACZSkinHelper.bareSkinId(entry.id()).equals(weapon.baseGun());
    }

    // Uses the real render path so the badge cannot disagree; keyed on generation() since the geo-model usually arrives late.
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
            // Debug level: this runs per visible tile.
            MCPSkins.LOGGER.debug("[MCPSkins] Custom-model badge check failed for '{}'.", cacheKey, e);
        }
        customModelCache.put(cacheKey, new CustomModelResult(generation, result));
        return result;
    }

    // Null for non-TACZ items: geometry replacement only works through TACZ's model registry.
    private ResourceLocation baseModelLocation(SkinDataModels.WeaponSkins weapon) {
        if (weapon.target() != SkinDataModels.SkinTarget.GUN
                && weapon.target() != SkinDataModels.SkinTarget.ATTACHMENT) {
            return null;
        }
        if (weapon.isAttachment()) {
            ResourceLocation id = ResourceLocation.tryParse(weapon.baseGun());
            return id == null ? null : TimelessAPI.getClientAttachmentIndex(id)
                    .map(ClientAttachmentIndexPatcher::getBaseModelLocation).orElse(null);
        }
        return TimelessAPI.getGunDisplay(previewStack(weapon.baseGun(), null))
                .map(GunModelPatcher::getBaseModelLocation).orElse(null);
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
