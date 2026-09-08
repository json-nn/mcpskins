package org.minechestplate.mcpskins.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Geometry for {@link SkinArmoryScreen}: it decides where every zone sits for one frame, so
 * drawing and hit-testing read the same numbers instead of each computing their own.
 */
final class ArmoryLayout {

    /** Column widths and type sizes per panel width. */
    enum Tier {
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

    enum StatusFilter { ALL, OWNED, LOCKED }

    enum SortMode { RARITY, ALPHABETICAL, NEWEST }

    static final int PANEL_MIN_W = 320;
    static final int PANEL_MAX_W = 640;
    static final int PANEL_MIN_H = 240;
    static final int PANEL_MAX_H = 360;
    static final int PANEL_MARGIN = 8;

    static final int TIER_WIDE_MIN = 560;
    static final int TIER_MID_MIN = 440;

    static final int PAD = 6;
    static final int GAP = 6;
    static final int GAP_TIGHT = 4;
    static final int CONTROL_H = 15;
    static final int SECTION_H = 11;
    static final int SKIN_ROW_H = 12;
    static final int SKIN_ROW_INDENT = 10;
    static final int TILE_H = 40;
    static final int INFO_ROW_H = 9;
    static final int EQUIP_H = 17;
    static final int ICON = 8;
    /** Past the stage frame, so a zoomed model cannot paint over its border. */
    static final int STAGE_INSET = 3;
    static final int CHIP_PAD = 8;
    static final int SEARCH_MIN_W = 46;

    /**
     * Smallest share of the header the search field keeps. A fixed floor was not enough: the
     * panel is capped in width, so at a high resolution a set of long translated captions ate
     * everything down to that floor and left the field unusable.
     */
    static final float SEARCH_MIN_SHARE = 0.30f;
    /** Enough for an ellipsis plus a glyph, so a squeezed chip still reads as a button. */
    static final int MIN_CHIP_W = 18;

    private ArmoryLayout() {
    }

    record Rect(int x0, int y0, int x1, int y1) {
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

    /** The chip captions as measured, so what is drawn is exactly what was laid out. */
    record HeaderLabels(String[] filters, String custom, String sort,
                        int filterW, int customW, int sortW) {

        int totalWidth() {
            int count = StatusFilter.values().length;
            return filterW * count + GAP_TIGHT * count + customW + GAP + sortW;
        }
    }

    /** Every zone for this frame. */
    record Layout(Tier tier, HeaderLabels labels, Rect panel, Rect search, Rect[] filters, Rect custom, Rect sort,
                  Rect rail, Rect railList, Rect stage, Rect tiles, Rect info, Rect equip) {
    }

    static Tier tierFor(int screenWidth) {
        int panelW = Mth.clamp(screenWidth - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        return panelW >= TIER_WIDE_MIN ? Tier.WIDE : panelW >= TIER_MID_MIN ? Tier.MID : Tier.COMPACT;
    }

    /** Recomputed every frame; cheaper than caching and risking staleness after a resize. */
    static Layout compute(int screenWidth, int screenHeight, Font font, SortMode sortMode) {
        int panelW = Mth.clamp(screenWidth - PANEL_MARGIN * 2, PANEL_MIN_W, PANEL_MAX_W);
        int panelH = Mth.clamp(screenHeight - PANEL_MARGIN * 2, PANEL_MIN_H, PANEL_MAX_H);
        int px = (screenWidth - panelW) / 2;
        int py = (screenHeight - panelH) / 2;
        Rect panel = new Rect(px, py, px + panelW, py + panelH);
        Tier tier = tierFor(screenWidth);

        int innerX0 = panel.x0() + PAD;
        int innerX1 = panel.x1() - PAD;
        int headerY = panel.y0() + PAD;

        // Laid out from the right edge inward, and measured rather than assumed: a translated
        // caption can be far wider than the English one.
        int headerW = innerX1 - innerX0;
        int searchMin = Math.max(SEARCH_MIN_W, Math.round(headerW * SEARCH_MIN_SHARE));

        HeaderLabels labels = headerLabels(font, tier, sortMode);
        if (tier != Tier.COMPACT && labels.totalWidth() + searchMin + GAP > headerW) {
            labels = headerLabels(font, Tier.COMPACT, sortMode);
        }
        int chipRoom = headerW - searchMin - GAP;
        if (labels.totalWidth() > chipRoom) {
            labels = squeeze(font, labels, chipRoom);
        }

        int chipsX = Math.max(innerX0 + searchMin + GAP, innerX1 - labels.totalWidth());

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

    private static HeaderLabels headerLabels(Font font, Tier tier, SortMode sortMode) {
        String[] filters = new String[StatusFilter.values().length];
        int filterW = 0;
        for (StatusFilter f : StatusFilter.values()) {
            filters[f.ordinal()] = statusFilterLabel(f, tier).getString();
            filterW = Math.max(filterW, chipWidth(font, filters[f.ordinal()]));
        }
        String custom = customLabel(tier).getString();
        String sort = sortModeLabel(sortMode, tier).getString();
        return new HeaderLabels(filters, custom, sort, filterW, chipWidth(font, custom), chipWidth(font, sort));
    }

    /**
     * Last resort when even the short captions overrun the header: shrink every chip by the same
     * factor and cut its caption to match, rather than let the row overlap the search field.
     */
    private static HeaderLabels squeeze(Font font, HeaderLabels labels, int available) {
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
            filters[i] = ArmoryTheme.truncate(font, labels.filters()[i], filterW - CHIP_PAD, ArmoryTheme.BASE);
        }
        return new HeaderLabels(filters,
                ArmoryTheme.truncate(font, labels.custom(), customW - CHIP_PAD, ArmoryTheme.BASE),
                ArmoryTheme.truncate(font, labels.sort(), sortW - CHIP_PAD, ArmoryTheme.BASE),
                filterW, customW, sortW);
    }

    private static int chipWidth(Font font, String label) {
        return font.width(label) + CHIP_PAD * 2;
    }

    private static Component statusFilterLabel(StatusFilter filter, Tier tier) {
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

    private static Component customLabel(Tier tier) {
        return Component.translatable(tier == Tier.COMPACT
                ? "gui.mcpskins.armory.filter_custom_model_short"
                : "gui.mcpskins.armory.filter_custom_model");
    }

    private static Component sortModeLabel(SortMode mode, Tier tier) {
        Component value = switch (mode) {
            case RARITY -> Component.translatable("gui.mcpskins.armory.sort_rarity");
            case ALPHABETICAL -> Component.translatable("gui.mcpskins.armory.sort_alphabetical");
            case NEWEST -> Component.translatable("gui.mcpskins.armory.sort_newest");
        };
        return tier == Tier.COMPACT ? value : Component.translatable("gui.mcpskins.armory.sort", value);
    }
}
