package org.minechestplate.mcpskins.client.gui;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.*;

/**
 * Full-screen standalone skin catalog/inspector, independent of what's currently in the
 * player's hand (unlike {@link org.minechestplate.mcpskins.client.TACZRefitSkinOverlay}).
 * Opened via hotkey ({@link org.minechestplate.mcpskins.client.ArmoryKeybinds}) or the
 * {@code /mcpskins armory} command.
 * <p>
 * Layout has four zones plus a top filter bar:
 * <pre>
 * [ search ....................... ] [All][Owned][Locked] [Has model] [Sort]
 * ┌───────────┬──────────────────────────────┬──────────────────┐
 * │  Weapons   │        3D PODIUM              │  Skins <weapon>  │
 * │ (left      │  (Item3DPodiumWidget -        │  (grid, not      │
 * │  column)   │   drag=rotate, wheel=zoom)    │   carousel)       │
 * └───────────┴──────────────────────────────┴──────────────────┘
 * [ skin name · rarity · collection · [Custom model]        [Equip] ]
 * </pre>
 * <p>
 * Layout is computed proportionally (see {@link #computeLayout()}), unlike
 * {@code TACZRefitSkinOverlay}, which has to match a fixed-pixel third-party background.
 * No vanilla background blur either - see {@link Item3DPodiumWidget} and
 * {@link #renderBackground}.
 */
public class SkinArmoryScreen extends Screen {

    private enum FocusPane { WEAPONS, SKINS }

    private enum StatusFilter { ALL, OWNED, LOCKED }

    private enum SortMode { RARITY, ALPHABETICAL, NEWEST }

    private static final int WEAPON_ROW_HEIGHT = 24;
    // Pill widths come from their actual text (see pillWidthFor), not a fixed number
    private static final int PILL_HEIGHT = 20;
    private static final int PILL_GAP = 6;
    private static final int PILL_TEXT_PADDING = 22;

    // Header layout is fully independent of the three columns below it
    private static final int HEADER_MARGIN = 8;
    private static final int HEADER_GROUP_GAP = 14;
    private static final int HEADER_MIN_SEARCH_WIDTH = 90;
    private static final int PILL_ROW_GAP = 6;
    // Lower than the shared minimum - short labels like "All"/"Owned" need less padding than "Locked"
    private static final int STATUS_PILL_MIN_WIDTH = 30;

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

    /** All geometric zones for this frame - a single source of truth for both render and click handling. */
    private record Layout(int topBarHeight,
                          int leftX, int leftY, int leftWidth, int leftHeight,
                          int podiumX, int podiumY, int podiumWidth, int podiumHeight,
                          int rightX, int rightY, int rightWidth, int rightHeight,
                          int bottomY, int bottomHeight,
                          int gridColumns, int gridCellSize, int gridSpacing,
                          int gridPaddingX, int gridPaddingY) {
    }

    private final List<String> weaponKeys = new ArrayList<>();
    private final Map<String, String> weaponNameCache = new HashMap<>();
    /** Badge results, tagged with the asset generation they were computed at. */
    private record CustomModelResult(int generation, boolean hasModel) {
    }

    private final Map<String, CustomModelResult> customModelCache = new HashMap<>();
    private final Item3DPodiumWidget podium = new Item3DPodiumWidget();

    private String selectedWeapon;
    private List<SkinDataModels.SkinLookupResult> visibleEntries = new ArrayList<>();
    private int selectedSkinIndex = 0;
    private EditBox searchBox;
    private StatusFilter statusFilter = StatusFilter.ALL;
    private boolean customModelOnly = false;
    private SortMode sortMode = SortMode.RARITY;
    private FocusPane focusPane = FocusPane.SKINS;
    private int weaponScrollPixels = 0;
    private int gridScrollPixels = 0;
    private String statusMessage;

    /** Skin id to select+scroll to on open (see {@link #init}), or null for the default. */
    private final String focusSkinId;

    public SkinArmoryScreen() {
        this(null);
    }

    /**
     * Opens with {@code focusSkinId}'s weapon selected and that skin scrolled into view
     * and highlighted, instead of the usual "currently held weapon" default - used by the
     * clickable skin name in the unlock/fuse chat messages (see {@code SkinUnlockItem}).
     *
     * @param focusSkinId a skin id to jump to, or null for the normal default selection
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

        // Search is its own full-width row above the filter buttons (see
        // computeHeaderLayout()), so the buttons render below it and can never overlap it
        HeaderLayout header = computeHeaderLayout();
        this.searchBox = new EditBox(this.font, header.searchX(), header.searchY(),
                header.searchWidth(), header.searchHeight(),
                Component.translatable("gui.mcpskins.armory.search"));
        this.searchBox.setHint(Component.translatable("gui.mcpskins.armory.search_hint"));
        this.searchBox.setResponder(s -> refreshVisibleEntries());
        this.addRenderableWidget(searchBox);

        rebuildWeaponList();
        SkinDataModels.SkinLookupResult focusLookup = focusSkinId != null ? SkinManager.INSTANCE.findSkin(focusSkinId) : null;
        if (focusLookup != null) {
            selectedWeapon = focusLookup.weapon().baseGun();
        } else if (selectedWeapon == null || !weaponKeys.contains(selectedWeapon)) {
            selectedWeapon = defaultWeaponSelection();
        }
        refreshVisibleEntries();

        if (focusLookup != null) {
            for (int i = 0; i < visibleEntries.size(); i++) {
                if (visibleEntries.get(i).skin().id().equals(focusSkinId)) {
                    selectedSkinIndex = i;
                    updatePodiumStack();
                    break;
                }
            }
            Layout layout = computeLayout();
            scrollWeaponListToSelection(layout);
            scrollGridToSelection(layout);
        }
    }

    /**
     * Recomputes every screen zone from {@code this.width}/{@code this.height} each frame -
     * cheap enough that recomputing on every call is simpler and safer than caching and
     * risking staleness after a window resize.
     */
    private Layout computeLayout() {
        int margin = 6;
        // topBarHeight is the header's actual height (see computeHeaderLayout()), so
        // everything below it shifts down to match
        int topBarHeight = computeHeaderLayout().totalHeight();
        int bottomBarHeight = 46;

        int leftWidth = Mth.clamp(this.width / 6, 140, 230);
        // rightWidth is derived from gridCellSize (fixed for a compact 2-column grid),
        // not the other way around; podiumWidth gets whatever's left between the columns
        int gridPaddingX = 6, gridPaddingY = 6, gridSpacing = 6, gridCellSize = 56;
        int gridColumnsTarget = 2;
        int rightWidth = gridPaddingX * 2 + gridCellSize * gridColumnsTarget + gridSpacing * (gridColumnsTarget - 1);

        int contentTop = topBarHeight + margin;
        int contentBottom = Math.max(contentTop + 20, this.height - bottomBarHeight - margin);
        int contentHeight = contentBottom - contentTop;

        int leftX = margin;
        int rightX = this.width - margin - rightWidth;
        int podiumX = leftX + leftWidth + margin;
        int podiumWidth = Math.max(40, rightX - margin - podiumX);

        // Falls back below gridColumnsTarget only if the screen is too narrow to fit it
        int usableGridWidth = Math.max(gridCellSize, rightWidth - gridPaddingX * 2);
        int gridColumns = Mth.clamp((usableGridWidth + gridSpacing) / (gridCellSize + gridSpacing), 1, gridColumnsTarget);

        return new Layout(topBarHeight,
                leftX, contentTop, leftWidth, contentHeight,
                podiumX, contentTop, podiumWidth, contentHeight,
                rightX, contentTop, rightWidth, contentHeight,
                this.height - bottomBarHeight, bottomBarHeight,
                gridColumns, gridCellSize, gridSpacing, gridPaddingX, gridPaddingY);
    }

    /** Button width sized to its actual (localized) text plus padding, with a minimum floor. */
    private int pillWidthFor(Component label, int minWidth) {
        return Math.max(minWidth, this.font.width(label) + PILL_TEXT_PADDING);
    }

    /**
     * Header layout: a full-width search row, then filter/sort buttons that flow
     * left-to-right and wrap to a new row instead of running off-screen (see
     * {@link #advancePillCursor}). {@code totalHeight()} feeds {@link Layout#topBarHeight}.
     */
    private record HeaderLayout(int searchX, int searchY, int searchWidth, int searchHeight,
                                Rect[] statusRects, Rect customRect, Rect sortRect, int totalHeight) {
    }

    private HeaderLayout computeHeaderLayout() {
        int searchHeight = PILL_HEIGHT;
        int searchY = 4;
        int[] cursorX = {HEADER_MARGIN};
        int[] cursorY = {searchY + searchHeight + PILL_ROW_GAP};

        Rect[] statusRects = new Rect[StatusFilter.values().length];
        boolean first = true;
        for (StatusFilter f : StatusFilter.values()) {
            int width = pillWidthFor(statusFilterLabel(f), STATUS_PILL_MIN_WIDTH);
            statusRects[f.ordinal()] = advancePillCursor(cursorX, cursorY, width, first ? 0 : PILL_GAP);
            first = false;
        }
        int customWidth = pillWidthFor(Component.translatable("gui.mcpskins.armory.filter_custom_model"), 90);
        Rect customRect = advancePillCursor(cursorX, cursorY, customWidth, HEADER_GROUP_GAP);

        int sortWidth = pillWidthFor(sortModeLabel(sortMode), 134);
        Rect sortRect = advancePillCursor(cursorX, cursorY, sortWidth, HEADER_GROUP_GAP);

        int totalHeight = cursorY[0] + PILL_HEIGHT + PILL_ROW_GAP;
        int searchWidth = Math.max(HEADER_MIN_SEARCH_WIDTH, this.width - HEADER_MARGIN * 2);
        return new HeaderLayout(HEADER_MARGIN, searchY, searchWidth, searchHeight,
                statusRects, customRect, sortRect, totalHeight);
    }

    /**
     * Advances the button flow cursor by one button: wraps to a new row instead of
     * running past the right edge if {@code width} (plus {@code gapBefore}) doesn't fit.
     * {@code cursorX}/{@code cursorY} are single-element arrays acting as out-parameters.
     */
    private Rect advancePillCursor(int[] cursorX, int[] cursorY, int width, int gapBefore) {
        int rowRight = this.width - HEADER_MARGIN;
        boolean atRowStart = cursorX[0] == HEADER_MARGIN;
        int x0 = atRowStart ? cursorX[0] : cursorX[0] + gapBefore;
        if (!atRowStart && x0 + width > rowRight) {
            cursorY[0] += PILL_HEIGHT + PILL_ROW_GAP;
            x0 = HEADER_MARGIN;
        }
        Rect rect = new Rect(x0, cursorY[0], x0 + width, cursorY[0] + PILL_HEIGHT);
        cursorX[0] = rect.x1();
        return rect;
    }

    private Rect statusPillRect(Layout layout, StatusFilter filter) {
        return computeHeaderLayout().statusRects()[filter.ordinal()];
    }

    private Rect customModelToggleRect(Layout layout) {
        return computeHeaderLayout().customRect();
    }

    private Rect sortButtonRect(Layout layout) {
        return computeHeaderLayout().sortRect();
    }

    private Rect equipButtonRect(Layout layout) {
        int width = 150, height = 26;
        int x1 = this.width - 12;
        int y0 = layout.bottomY() + (layout.bottomHeight() - height) / 2;
        return new Rect(x1 - width, y0, x1, y0 + height);
    }

    // -----------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Our own dark background - no vanilla "Menu Background Blurriness" needed
        guiGraphics.fillGradient(0, 0, this.width, this.height, 0xD8101010, 0xF2060606);

        Layout layout = computeLayout();

        renderTopBar(guiGraphics, layout, mouseX, mouseY);
        renderWeaponList(guiGraphics, layout, mouseX, mouseY);

        podium.setBounds(layout.podiumX(), layout.podiumY(), layout.podiumWidth(), layout.podiumHeight());
        podium.render(guiGraphics, partialTick, currentAccentColor());

        renderSkinGrid(guiGraphics, layout, mouseX, mouseY);
        renderBottomBar(guiGraphics, layout, mouseX, mouseY);

        // Renders real widgets (the search box) over our hand-drawn top bar. This also
        // calls renderBackground(...) internally - overridden below as a no-op, or it'd
        // trigger vanilla's blur over everything already drawn here.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        renderHoverTooltip(guiGraphics, layout, mouseX, mouseY);
        renderWeaponHoverTooltip(guiGraphics, layout, mouseX, mouseY);
    }

    /** No-op - avoids vanilla's background blur, which {@code Screen#render(...)} always triggers. */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    private void renderTopBar(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        guiGraphics.fill(0, 0, this.width, layout.topBarHeight(), 0xE8181818);
        guiGraphics.fill(0, layout.topBarHeight() - 1, this.width, layout.topBarHeight(), 0x405FD3FF);

        for (StatusFilter filter : StatusFilter.values()) {
            Rect rect = statusPillRect(layout, filter);
            boolean active = statusFilter == filter;
            boolean hovered = rect.contains(mouseX, mouseY);
            int bg = active ? 0xE02A4A5A : hovered ? 0xE02A2A2A : 0xC0151515;
            int border = active ? 0x5FD3FF : hovered ? 0xAAAAAA : 0x3A3A3A;
            guiGraphics.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), bg);
            guiGraphics.renderOutline(rect.x0(), rect.y0(), rect.width(), rect.height(), 0xFF000000 | border);
            int textY = rect.y0() + (rect.height() - this.font.lineHeight) / 2;
            guiGraphics.drawCenteredString(this.font, statusFilterLabel(filter), (rect.x0() + rect.x1()) / 2, textY, 0xFFFFFFFF);
        }

        Rect customRect = customModelToggleRect(layout);
        boolean customHovered = customRect.contains(mouseX, mouseY);
        int customBg = customModelOnly ? 0xE0553A1A : customHovered ? 0xE02A2A2A : 0xC0151515;
        int customBorder = customModelOnly ? 0xFFB347 : customHovered ? 0xAAAAAA : 0x3A3A3A;
        guiGraphics.fill(customRect.x0(), customRect.y0(), customRect.x1(), customRect.y1(), customBg);
        guiGraphics.renderOutline(customRect.x0(), customRect.y0(), customRect.width(), customRect.height(), 0xFF000000 | customBorder);
        guiGraphics.drawCenteredString(this.font, Component.translatable("gui.mcpskins.armory.filter_custom_model"),
                (customRect.x0() + customRect.x1()) / 2, customRect.y0() + (customRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);

        Rect sortRect = sortButtonRect(layout);
        boolean sortHovered = sortRect.contains(mouseX, mouseY);
        guiGraphics.fill(sortRect.x0(), sortRect.y0(), sortRect.x1(), sortRect.y1(), sortHovered ? 0xE02A2A2A : 0xC0151515);
        guiGraphics.renderOutline(sortRect.x0(), sortRect.y0(), sortRect.width(), sortRect.height(), 0xFF000000 | (sortHovered ? 0xAAAAAA : 0x3A3A3A));
        guiGraphics.drawCenteredString(this.font, sortModeLabel(sortMode),
                (sortRect.x0() + sortRect.x1()) / 2, sortRect.y0() + (sortRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);
    }

    private void renderWeaponList(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int panelX0 = layout.leftX() - 4, panelY0 = layout.leftY() - 2;
        int panelX1 = layout.leftX() + layout.leftWidth() + 2, panelY1 = layout.leftY() + layout.leftHeight() + 2;
        guiGraphics.fill(panelX0, panelY0, panelX1, panelY1, 0xB0101010);

        guiGraphics.enableScissor(panelX0, panelY0, panelX1, panelY1);
        try {
            int rowY = layout.leftY() - weaponScrollPixels;
            for (String baseGun : weaponKeys) {
                if (rowY + WEAPON_ROW_HEIGHT >= layout.leftY() && rowY <= layout.leftY() + layout.leftHeight()) {
                    boolean selected = baseGun.equals(selectedWeapon);
                    boolean hovered = mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                            && mouseY >= rowY && mouseY < rowY + WEAPON_ROW_HEIGHT;
                    boolean heldNow = isWeaponCurrentlyHeld(baseGun);

                    if (selected) {
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + layout.leftWidth(), rowY + WEAPON_ROW_HEIGHT, 0xE02A3A4A);
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + 2, rowY + WEAPON_ROW_HEIGHT, 0xFF5FD3FF);
                    } else if (hovered) {
                        guiGraphics.fill(layout.leftX(), rowY, layout.leftX() + layout.leftWidth(), rowY + WEAPON_ROW_HEIGHT, 0xE0242424);
                    }

                    ItemStack icon = TACZSkinHelper.createGunStack(baseGun);
                    guiGraphics.renderItem(icon, layout.leftX() + 4, rowY + (WEAPON_ROW_HEIGHT - 16) / 2);

                    // Truncated with "..." to fit the panel; the full name is still
                    // available via tooltip (see renderWeaponHoverTooltip)
                    String fullName = weaponDisplayName(baseGun);
                    int nameTextX = layout.leftX() + 26;
                    int availableNameWidth = layout.leftX() + layout.leftWidth() - nameTextX - 4;
                    String displayName = truncateToWidth(fullName, availableNameWidth);
                    Component name = Component.literal(displayName);
                    int textColor = heldNow ? 0x5FD3FF : 0xFFFFFF;
                    guiGraphics.drawString(this.font, name, nameTextX,
                            rowY + (WEAPON_ROW_HEIGHT - this.font.lineHeight) / 2, textColor, false);
                }
                rowY += WEAPON_ROW_HEIGHT;
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderSkinGrid(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int panelX0 = layout.rightX() - 4, panelY0 = layout.rightY() - 2;
        int panelX1 = layout.rightX() + layout.rightWidth() + 2, panelY1 = layout.rightY() + layout.rightHeight() + 2;
        guiGraphics.fill(panelX0, panelY0, panelX1, panelY1, 0xB0101010);

        guiGraphics.enableScissor(panelX0, panelY0, panelX1, panelY1);
        try {
            Player player = Minecraft.getInstance().player;
            int cell = layout.gridCellSize();
            int spacing = layout.gridSpacing();
            int columns = layout.gridColumns();
            int startX = layout.rightX() + layout.gridPaddingX();
            int startY = layout.rightY() + layout.gridPaddingY() - gridScrollPixels;

            for (int i = 0; i < visibleEntries.size(); i++) {
                int col = i % columns;
                int row = i / columns;
                int cellX = startX + col * (cell + spacing);
                int cellY = startY + row * (cell + spacing);
                if (cellY + cell < layout.rightY() || cellY > layout.rightY() + layout.rightHeight()) continue;

                SkinDataModels.SkinLookupResult lookup = visibleEntries.get(i);
                SkinDataModels.SkinEntry entry = lookup.skin();
                boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
                boolean selected = i == selectedSkinIndex;
                boolean equippedNow = isSkinCurrentlyEquipped(lookup);
                boolean hovered = mouseX >= cellX && mouseX < cellX + cell && mouseY >= cellY && mouseY < cellY + cell;

                guiGraphics.fill(cellX, cellY, cellX + cell, cellY + cell, selected ? 0xF02A2A2A : 0xF01B1B1B);
                int borderRgb = equippedNow ? 0x5FD3FF : selected ? 0xFFFFFF : hovered ? 0xAAAAAA : (entry.labelColor() & 0xFFFFFF);
                guiGraphics.renderOutline(cellX, cellY, cell, cell, 0xFF000000 | borderRgb);
                guiGraphics.fill(cellX, cellY, cellX + cell, cellY + 2, 0xFF000000 | (entry.labelColor() & 0xFFFFFF));

                ItemStack thumb = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), entry.id());
                guiGraphics.renderItem(thumb, cellX + cell / 2 - 8, cellY + cell / 2 - 8);

                if (!unlocked) {
                    guiGraphics.fill(cellX, cellY, cellX + cell, cellY + cell, 0x80000000);
                    int lockSize = 8;
                    int lx = cellX + cell - lockSize - 3, ly = cellY + cell - lockSize - 3;
                    guiGraphics.renderOutline(lx + 1, ly, lockSize - 2, 4, 0xFFE8E8E8);
                    guiGraphics.fill(lx, ly + 3, lx + lockSize, ly + lockSize, 0xFFE8E8E8);
                }
                if (entry.isNew()) {
                    guiGraphics.fill(cellX, cellY, cellX + 20, cellY + 9, 0xFF3A8F3A);
                    guiGraphics.drawString(this.font, Component.translatable("gui.mcpskins.armory.badge_new"),
                            cellX + 2, cellY + 1, 0xFFFFFFFF, false);
                }
                if (hasCustomModel(lookup.weapon(), entry)) {
                    guiGraphics.fill(cellX + cell - 20, cellY, cellX + cell, cellY + 9, 0xFF3A5A8F);
                    guiGraphics.drawString(this.font, Component.translatable("gui.mcpskins.armory.badge_model"),
                            cellX + cell - 18, cellY + 1, 0xFFFFFFFF, false);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void renderBottomBar(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        guiGraphics.fill(0, layout.bottomY(), this.width, this.height, 0xE8181818);
        guiGraphics.fill(0, layout.bottomY(), this.width, layout.bottomY() + 1, 0x405FD3FF);

        if (!visibleEntries.isEmpty()) {
            SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
            SkinDataModels.SkinEntry entry = lookup.skin();

            Component nameLine = Component.literal(entry.name()).withStyle(s -> s.withColor(entry.labelColor()).withBold(true));
            guiGraphics.drawString(this.font, nameLine, 10, layout.bottomY() + 5, 0xFFFFFFFF, false);

            // Rarity's own accent color, not static gray, so it reads visually distinct
            MutableComponent detail = Component.translatable("gui.mcpskins.armory.rarity_" + entry.rarity().name().toLowerCase(Locale.ROOT))
                    .withStyle(s -> s.withColor(entry.rarity().accentColor));
            if (entry.hasCollection()) {
                detail = detail.copy().append(Component.literal("  \u2022  " + entry.collection()).withStyle(ChatFormatting.GRAY));
            }
            if (hasCustomModel(lookup.weapon(), entry)) {
                detail = detail.copy().append(Component.literal("  \u2022  ")
                        .append(Component.translatable("gui.mcpskins.armory.badge_model_full")).withStyle(ChatFormatting.AQUA));
            }
            guiGraphics.drawString(this.font, detail, 10, layout.bottomY() + 5 + this.font.lineHeight + 2, 0xA0FFFFFF, false);

            String thirdLine = statusMessage != null ? statusMessage : entry.hasDescription() ? entry.description() : null;
            if (thirdLine != null) {
                int color = statusMessage != null ? 0xFFFF8080 : 0x80FFFFFF;
                guiGraphics.drawString(this.font, thirdLine, 10, layout.bottomY() + 5 + (this.font.lineHeight + 2) * 2, color, false);
            }
        }

        Rect equipRect = equipButtonRect(layout);
        boolean hovered = equipRect.contains(mouseX, mouseY);
        boolean enabled = canEquipSelected();
        int bg = !enabled ? 0x80303030 : hovered ? 0xE02A5A2A : 0xE01B3A1B;
        guiGraphics.fill(equipRect.x0(), equipRect.y0(), equipRect.x1(), equipRect.y1(), bg);
        guiGraphics.renderOutline(equipRect.x0(), equipRect.y0(), equipRect.width(), equipRect.height(), enabled ? 0xFF5FD3FF : 0xFF555555);
        guiGraphics.drawCenteredString(this.font, equipButtonLabel(),
                (equipRect.x0() + equipRect.x1()) / 2, equipRect.y0() + (equipRect.height() - this.font.lineHeight) / 2, 0xFFFFFFFF);
    }

    /**
     * Shows the full weapon name on hover, but only when the panel actually truncated it
     * (see {@link #truncateToWidth}) - otherwise this would just duplicate visible text.
     */
    private void renderWeaponHoverTooltip(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        if (mouseX < layout.leftX() || mouseX >= layout.leftX() + layout.leftWidth()
                || mouseY < layout.leftY() || mouseY >= layout.leftY() + layout.leftHeight()) {
            return;
        }
        int relativeY = (int) (mouseY - layout.leftY() + weaponScrollPixels);
        int index = relativeY / WEAPON_ROW_HEIGHT;
        if (index < 0 || index >= weaponKeys.size()) return;

        String fullName = weaponDisplayName(weaponKeys.get(index));
        int nameTextX = layout.leftX() + 26;
        int availableNameWidth = layout.leftX() + layout.leftWidth() - nameTextX - 4;
        if (this.font.width(fullName) <= availableNameWidth) return;

        guiGraphics.renderTooltip(this.font, Component.literal(fullName), mouseX, mouseY);
    }

    /** Truncates {@code text} to {@code maxWidth} pixels, adding "..." if truncated. */
    private String truncateToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        int fitWidth = Math.max(0, maxWidth - ellipsisWidth);
        return this.font.plainSubstrByWidth(text, fitWidth) + ellipsis;
    }

    private void renderHoverTooltip(GuiGraphics guiGraphics, Layout layout, int mouseX, int mouseY) {
        int index = gridIndexAt(layout, mouseX, mouseY);
        if (index < 0) return;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(index);
        SkinDataModels.SkinEntry entry = lookup.skin();
        Player player = Minecraft.getInstance().player;
        boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.name()).withStyle(s -> s.withColor(entry.labelColor())));
        lines.add(Component.literal(weaponDisplayName(lookup.weapon().baseGun())).withStyle(ChatFormatting.GRAY));
        // Same rarity-accent-color fix as renderBottomBar()
        lines.add(Component.translatable("gui.mcpskins.armory.rarity_" + entry.rarity().name().toLowerCase(Locale.ROOT))
                .withStyle(s -> s.withColor(entry.rarity().accentColor)));
        if (!unlocked) {
            lines.add(Component.translatable("gui.mcpskins.armory.status_locked").withStyle(ChatFormatting.RED));
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
            if (statusPillRect(layout, filter).contains(mouseX, mouseY)) {
                statusFilter = filter;
                playClick();
                refreshVisibleEntries();
                return true;
            }
        }
        if (customModelToggleRect(layout).contains(mouseX, mouseY)) {
            customModelOnly = !customModelOnly;
            playClick();
            refreshVisibleEntries();
            return true;
        }
        if (sortButtonRect(layout).contains(mouseX, mouseY)) {
            sortMode = nextSortMode(sortMode);
            playClick();
            refreshVisibleEntries();
            return true;
        }

        if (mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                && mouseY >= layout.leftY() && mouseY < layout.leftY() + layout.leftHeight()) {
            int relativeY = (int) (mouseY - layout.leftY() + weaponScrollPixels);
            int index = relativeY / WEAPON_ROW_HEIGHT;
            if (index >= 0 && index < weaponKeys.size()) {
                focusPane = FocusPane.WEAPONS;
                selectWeapon(weaponKeys.get(index));
                playClick();
            }
            return true;
        }

        if (podium.isInBounds(mouseX, mouseY)) {
            podium.onMouseClicked();
            return true;
        }

        if (mouseX >= layout.rightX() && mouseX < layout.rightX() + layout.rightWidth()
                && mouseY >= layout.rightY() && mouseY < layout.rightY() + layout.rightHeight()) {
            int index = gridIndexAt(layout, mouseX, mouseY);
            if (index >= 0) {
                selectedSkinIndex = index;
                focusPane = FocusPane.SKINS;
                updatePodiumStack();
                playClick();
            }
            return true;
        }

        if (equipButtonRect(layout).contains(mouseX, mouseY)) {
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
        if (mouseX >= layout.rightX() && mouseX < layout.rightX() + layout.rightWidth()
                && mouseY >= layout.rightY() && mouseY < layout.rightY() + layout.rightHeight()) {
            gridScrollPixels = Mth.clamp(gridScrollPixels - (int) (scrollY * 24), 0, maxGridScroll(layout));
            return true;
        }
        if (mouseX >= layout.leftX() && mouseX < layout.leftX() + layout.leftWidth()
                && mouseY >= layout.leftY() && mouseY < layout.leftY() + layout.leftHeight()) {
            int maxScroll = Math.max(0, weaponKeys.size() * WEAPON_ROW_HEIGHT - layout.leftHeight());
            weaponScrollPixels = Mth.clamp(weaponScrollPixels - (int) (scrollY * 24), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
            focusPane = focusPane == FocusPane.WEAPONS ? FocusPane.SKINS : FocusPane.WEAPONS;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            handleNavigation(keyCode);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focusPane == FocusPane.SKINS) {
                equipSelected();
            } else {
                focusPane = FocusPane.SKINS;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void handleNavigation(int keyCode) {
        if (focusPane == FocusPane.WEAPONS) {
            if (weaponKeys.isEmpty()) return;
            int index = Math.max(0, weaponKeys.indexOf(selectedWeapon));
            if (keyCode == GLFW.GLFW_KEY_UP) index = Math.max(0, index - 1);
            else if (keyCode == GLFW.GLFW_KEY_DOWN) index = Math.min(weaponKeys.size() - 1, index + 1);
            selectWeapon(weaponKeys.get(index));
        } else {
            if (visibleEntries.isEmpty()) return;
            Layout layout = computeLayout();
            int columns = layout.gridColumns();
            int index = selectedSkinIndex;
            if (keyCode == GLFW.GLFW_KEY_LEFT) index -= 1;
            else if (keyCode == GLFW.GLFW_KEY_RIGHT) index += 1;
            else if (keyCode == GLFW.GLFW_KEY_UP) index -= columns;
            else if (keyCode == GLFW.GLFW_KEY_DOWN) index += columns;
            selectedSkinIndex = Mth.clamp(index, 0, visibleEntries.size() - 1);
            updatePodiumStack();
            scrollGridToSelection(layout);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    // -----------------------------------------------------------------------------------
    // State / data
    // -----------------------------------------------------------------------------------

    private void rebuildWeaponList() {
        weaponKeys.clear();
        weaponKeys.addAll(SkinManager.INSTANCE.getRegistry().keySet());
        weaponKeys.sort(Comparator.comparing(this::weaponDisplayName, String.CASE_INSENSITIVE_ORDER));
    }

    private String defaultWeaponSelection() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            String heldMain = TACZSkinHelper.getGunId(player.getMainHandItem());
            if (heldMain != null && weaponKeys.contains(heldMain)) return heldMain;
            String heldOff = TACZSkinHelper.getGunId(player.getOffhandItem());
            if (heldOff != null && weaponKeys.contains(heldOff)) return heldOff;
        }
        return weaponKeys.isEmpty() ? null : weaponKeys.get(0);
    }

    private void selectWeapon(String baseGun) {
        this.selectedWeapon = baseGun;
        this.selectedSkinIndex = 0;
        this.gridScrollPixels = 0;
        refreshVisibleEntries();
    }

    /** Left-column analogue of {@link #scrollGridToSelection} - scrolls {@link #selectedWeapon}'s row into view. */
    private void scrollWeaponListToSelection(Layout layout) {
        if (selectedWeapon == null) return;
        int index = weaponKeys.indexOf(selectedWeapon);
        if (index < 0) return;

        int rowTop = index * WEAPON_ROW_HEIGHT;
        int rowBottom = rowTop + WEAPON_ROW_HEIGHT;
        int viewHeight = layout.leftHeight();

        if (rowTop < weaponScrollPixels) {
            weaponScrollPixels = rowTop;
        } else if (rowBottom > weaponScrollPixels + viewHeight) {
            weaponScrollPixels = rowBottom - viewHeight;
        }
        int maxScroll = Math.max(0, weaponKeys.size() * WEAPON_ROW_HEIGHT - viewHeight);
        weaponScrollPixels = Mth.clamp(weaponScrollPixels, 0, maxScroll);
    }

    /**
     * Rebuilds {@link #visibleEntries} from the current search/filters/sort. Search, when
     * non-empty, is intentionally global across all weapons rather than just the selected
     * one; an empty search returns the grid to "skins of the selected weapon" mode.
     */
    private void refreshVisibleEntries() {
        List<SkinDataModels.SkinLookupResult> result = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        String query = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        boolean globalSearch = !query.isEmpty();

        Collection<SkinDataModels.WeaponSkins> weaponsToScan;
        if (globalSearch) {
            weaponsToScan = SkinManager.INSTANCE.getRegistry().values();
        } else if (selectedWeapon != null && SkinManager.INSTANCE.getRegistry().containsKey(selectedWeapon)) {
            weaponsToScan = List.of(SkinManager.INSTANCE.getRegistry().get(selectedWeapon));
        } else {
            weaponsToScan = List.of();
        }

        for (SkinDataModels.WeaponSkins weapon : weaponsToScan) {
            for (SkinDataModels.SkinEntry entry : weapon.skins()) {
                boolean unlocked = player != null && SkinAttachment.isOwnedOrDefault(player, entry.id());
                if (statusFilter == StatusFilter.OWNED && !unlocked) continue;
                if (statusFilter == StatusFilter.LOCKED && unlocked) continue;

                if (globalSearch) {
                    boolean nameMatch = entry.name().toLowerCase(Locale.ROOT).contains(query);
                    boolean weaponMatch = weaponDisplayName(weapon.baseGun()).toLowerCase(Locale.ROOT).contains(query);
                    if (!nameMatch && !weaponMatch) continue;
                }
                if (customModelOnly && !hasCustomModel(weapon, entry)) continue;

                result.add(new SkinDataModels.SkinLookupResult(weapon, entry));
            }
        }

        Comparator<SkinDataModels.SkinLookupResult> byName =
                Comparator.comparing(r -> r.skin().name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<SkinDataModels.SkinLookupResult> sortModeComparator = switch (sortMode) {
            case ALPHABETICAL -> byName;
            case NEWEST -> Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> r.skin().isNew() ? 0 : 1).thenComparing(byName);
            case RARITY -> Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> r.skin().rarity().ordinal()).reversed().thenComparing(byName);
        };
        // The weapon's default (stock, no custom model) skin always sorts first in the
        // right panel, regardless of sort mode - a primary sort key that dominates the rest
        Comparator<SkinDataModels.SkinLookupResult> comparator =
                Comparator.<SkinDataModels.SkinLookupResult>comparingInt(r -> isDefaultSkin(r) ? 0 : 1)
                        .thenComparing(sortModeComparator);
        result.sort(comparator);

        this.visibleEntries = result;
        this.selectedSkinIndex = visibleEntries.isEmpty() ? 0 : Mth.clamp(selectedSkinIndex, 0, visibleEntries.size() - 1);
        this.statusMessage = null;
        updatePodiumStack();
    }

    private void updatePodiumStack() {
        if (visibleEntries.isEmpty()) {
            podium.setStack(ItemStack.EMPTY);
            return;
        }
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        podium.setStack(TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id()));
        // A global search may select a skin belonging to a different weapon than the one
        // highlighted in the left column - keep them in sync with what's actually shown
        this.selectedWeapon = lookup.weapon().baseGun();
    }

    private void scrollGridToSelection(Layout layout) {
        if (visibleEntries.isEmpty()) return;
        int columns = layout.gridColumns();
        int cell = layout.gridCellSize();
        int spacing = layout.gridSpacing();
        int row = selectedSkinIndex / columns;
        int cellTop = row * (cell + spacing);
        int cellBottom = cellTop + cell;
        int viewHeight = layout.rightHeight() - layout.gridPaddingY() * 2;

        if (cellTop < gridScrollPixels) {
            gridScrollPixels = cellTop;
        } else if (cellBottom > gridScrollPixels + viewHeight) {
            gridScrollPixels = cellBottom - viewHeight;
        }
        gridScrollPixels = Mth.clamp(gridScrollPixels, 0, maxGridScroll(layout));
    }

    private int maxGridScroll(Layout layout) {
        int columns = layout.gridColumns();
        int rows = (int) Math.ceil(visibleEntries.size() / (double) columns);
        int contentHeight = rows * (layout.gridCellSize() + layout.gridSpacing());
        int viewHeight = layout.rightHeight() - layout.gridPaddingY() * 2;
        return Math.max(0, contentHeight - viewHeight);
    }

    private int gridIndexAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < layout.rightX() || mouseX >= layout.rightX() + layout.rightWidth()) return -1;
        if (mouseY < layout.rightY() || mouseY >= layout.rightY() + layout.rightHeight()) return -1;

        int cell = layout.gridCellSize();
        int spacing = layout.gridSpacing();
        int columns = layout.gridColumns();
        int startX = layout.rightX() + layout.gridPaddingX();
        int startY = layout.rightY() + layout.gridPaddingY() - gridScrollPixels;

        int relX = (int) (mouseX - startX);
        int relY = (int) (mouseY - startY);
        if (relX < 0 || relY < 0) return -1;

        int col = relX / (cell + spacing);
        int row = relY / (cell + spacing);
        if (col >= columns) return -1;
        if (relX - col * (cell + spacing) > cell) return -1;
        if (relY - row * (cell + spacing) > cell) return -1;

        int index = row * columns + col;
        return index >= 0 && index < visibleEntries.size() ? index : -1;
    }

    // -----------------------------------------------------------------------------------
    // Equipping
    // -----------------------------------------------------------------------------------

    /**
     * Unlike {@code TACZRefitSkinOverlay}, browsing here doesn't require holding the
     * weapon - the podium shows a synthetic preview, not the real item. Holding it is
     * only required to actually equip, since the server applies the skin to whichever
     * hand holds it; if it isn't in hand, the status line explains why.
     */
    private void equipSelected() {
        if (visibleEntries.isEmpty()) return;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!SkinAttachment.isOwnedOrDefault(player, lookup.skin().id())) {
            statusMessage = Component.translatable("gui.mcpskins.armory.status_locked").getString();
            playFail();
            return;
        }

        InteractionHand hand = resolveHand(player, lookup.weapon().baseGun());
        if (hand == null) {
            statusMessage = Component.translatable("gui.mcpskins.armory.status_need_hand",
                    weaponDisplayName(lookup.weapon().baseGun())).getString();
            playFail();
            return;
        }

        // Same optimistic client-side update as TACZRefitSkinOverlay - set the skin
        // component locally right away rather than waiting for the server response, which
        // arrives shortly after with the authoritative value anyway
        ItemStack held = player.getItemInHand(hand);
        ItemStack optimistic = TACZSkinHelper.applySkin(held, lookup.skin().id());
        if (!optimistic.isEmpty()) {
            player.setItemInHand(hand, optimistic);
        }
        PacketDistributor.sendToServer(isDefaultSkin(lookup)
                ? ApplySkinPayload.removeSkin()
                : ApplySkinPayload.equip(lookup.skin().id()));
        statusMessage = null;
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.4f);
    }

    private boolean canEquipSelected() {
        if (visibleEntries.isEmpty()) return false;
        SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (!SkinAttachment.isOwnedOrDefault(player, lookup.skin().id())) return false;
        return resolveHand(player, lookup.weapon().baseGun()) != null;
    }

    private Component equipButtonLabel() {
        if (!visibleEntries.isEmpty()) {
            SkinDataModels.SkinLookupResult lookup = visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1));
            if (isSkinCurrentlyEquipped(lookup)) {
                return Component.translatable("gui.mcpskins.armory.equipped");
            }
        }
        return Component.translatable("gui.mcpskins.armory.equip");
    }

    private InteractionHand resolveHand(Player player, String baseGun) {
        if (baseGun.equals(TACZSkinHelper.getGunId(player.getMainHandItem()))) return InteractionHand.MAIN_HAND;
        if (baseGun.equals(TACZSkinHelper.getGunId(player.getOffhandItem()))) return InteractionHand.OFF_HAND;
        return null;
    }

    private boolean isWeaponCurrentlyHeld(String baseGun) {
        Player player = Minecraft.getInstance().player;
        return player != null && resolveHand(player, baseGun) != null;
    }

    private boolean isSkinCurrentlyEquipped(SkinDataModels.SkinLookupResult lookup) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        InteractionHand hand = resolveHand(player, lookup.weapon().baseGun());
        if (hand == null) return false;
        ItemStack held = player.getItemInHand(hand);
        String equippedSkinId = TACZSkinHelper.getSkinId(held);
        String normalizedEquipped = equippedSkinId == null ? lookup.weapon().baseGun() : equippedSkinId;
        return normalizedEquipped.equals(TACZSkinHelper.bareSkinId(lookup.skin().id()));
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private String weaponDisplayName(String baseGun) {
        return weaponNameCache.computeIfAbsent(baseGun, key -> {
            ItemStack stack = TACZSkinHelper.createGunStack(key);
            return stack.isEmpty() ? key : stack.getHoverName().getString();
        });
    }

    /**
     * The weapon's default skin - {@code bareSkinId(id)} equals the weapon's own
     * {@code baseGun}. Forced to the front of the right panel (see {@link #refreshVisibleEntries}).
     */
    private boolean isDefaultSkin(SkinDataModels.SkinLookupResult lookup) {
        return TACZSkinHelper.bareSkinId(lookup.skin().id()).equals(lookup.weapon().baseGun());
    }

    /**
     * "Custom model" badge check, using the same path as the actual render
     * ({@code SkinAssetResolver.resolveModel}) so it's never wrong. Cached for the
     * screen's session - {@code TimelessAPI.getGunDisplay(...)} isn't cheap enough to
     * call every frame for every visible cell.
     * <p>
     * The cache is tagged with {@link ClientSkinAssetCache#generation()} rather than being
     * held for the screen's whole session. The first call for a skin usually lands while its
     * geo-model is still in flight, which resolves to false - pinning that answer meant the
     * badge stayed off, and the "custom model only" filter stayed wrong, for as long as the
     * screen stayed open. Re-checking when the generation moves lets the answer correct
     * itself once the asset lands, and costs nothing while nothing is arriving.
     */
    private boolean hasCustomModel(SkinDataModels.WeaponSkins weapon, SkinDataModels.SkinEntry entry) {
        String bare = TACZSkinHelper.bareSkinId(entry.id());
        if (bare.equals(weapon.baseGun())) return false;

        int generation = ClientSkinAssetCache.generation();

        String cacheKey = weapon.baseGun() + '\u0000' + bare;
        CustomModelResult cached = customModelCache.get(cacheKey);
        if (cached != null && cached.generation() == generation) return cached.hasModel();

        boolean result = false;
        try {
            ItemStack bareStack = TACZSkinHelper.createGunStack(weapon.baseGun());
            Optional<GunDisplayInstance> base = TimelessAPI.getGunDisplay(bareStack);
            if (base.isPresent()) {
                ResourceLocation baseModelLocation = GunModelPatcher.getBaseModelLocation(base.get());
                result = baseModelLocation != null && SkinAssetResolver.resolveModel(baseModelLocation, bare) != null;
            }
        } catch (RuntimeException e) {
            // The badge just doesn't show; the screen stays up. Debug level because this runs
            // per visible cell, and a real breakage would repeat on every generation bump.
            MCPSkins.LOGGER.debug("[MCPSkins] Custom-model badge check failed for '{}'.", cacheKey, e);
        }
        customModelCache.put(cacheKey, new CustomModelResult(generation, result));
        return result;
    }

    private int currentAccentColor() {
        if (visibleEntries.isEmpty()) return 0x5FD3FF;
        return visibleEntries.get(Math.min(selectedSkinIndex, visibleEntries.size() - 1)).skin().labelColor();
    }

    private Component statusFilterLabel(StatusFilter filter) {
        return switch (filter) {
            case ALL -> Component.translatable("gui.mcpskins.armory.filter_all");
            case OWNED -> Component.translatable("gui.mcpskins.armory.filter_owned");
            case LOCKED -> Component.translatable("gui.mcpskins.armory.filter_locked");
        };
    }

    private Component sortModeLabel(SortMode mode) {
        return switch (mode) {
            case RARITY -> Component.translatable("gui.mcpskins.armory.sort_rarity");
            case ALPHABETICAL -> Component.translatable("gui.mcpskins.armory.sort_alphabetical");
            case NEWEST -> Component.translatable("gui.mcpskins.armory.sort_newest");
        };
    }

    private SortMode nextSortMode(SortMode mode) {
        SortMode[] values = SortMode.values();
        return values[(mode.ordinal() + 1) % values.length];
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