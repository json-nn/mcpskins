package org.minechestplate.mcpskins.client.gui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a stand-in for the attachment slot row {@code GunRefitScreen} renders at its
 * top-right corner (GRIP/LASER/MUZZLE/SCOPE/STOCK/EXTENDED_MAG), using TACZ's own textures
 * at their real position - so the button-position picker can line the toggle button up
 * against the row exactly instead of relying on a background screenshot.
 * <p>
 * Every slot is drawn "disallowed" since there's no real weapon here to ask what it
 * supports - only the row's position matters for this screen.
 * <p>
 * Constants below are copied from {@code GunRefitScreen} (an internal, non-api class)
 * rather than imported, to keep this preview free of a compile-time TACZ dependency.
 */
public final class TACZAttachmentRowPreview {

    private static final ResourceLocation SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot.png");
    private static final ResourceLocation ICONS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot_icons.png");

    // == GunRefitScreen.SLOT_SIZE / ICON_UV_SIZE
    private static final int SLOT_SIZE = 18;
    private static final int ICON_UV_SIZE = 32;
    // 7 icons packed left-to-right: GRIP, LASER, MUZZLE, SCOPE, STOCK, EXTENDED_MAG, disallowed
    private static final int ICONS_TEXTURE_WIDTH = ICON_UV_SIZE * 7;
    private static final int ICONS_TEXTURE_HEIGHT = ICON_UV_SIZE;
    private static final int DISALLOWED_ICON_U = ICON_UV_SIZE * 6;

    // == GunRefitScreen.addAttachmentTypeButtons(): startX = width - 30, startY = 10,
    // one slot per attachment type, each slot SLOT_SIZE further left
    private static final int ROW_START_X_INSET = 30;
    private static final int ROW_START_Y = 10;
    private static final int SLOT_COUNT = 6;

    private TACZAttachmentRowPreview() {
    }

    /** Draws the reference row against the right edge of a screen {@code screenWidth} wide. */
    public static void render(GuiGraphics guiGraphics, int screenWidth) {
        int slotX = screenWidth - ROW_START_X_INSET;
        for (int i = 0; i < SLOT_COUNT; i++) {
            drawSlot(guiGraphics, slotX, ROW_START_Y);
            slotX -= SLOT_SIZE;
        }
    }

    private static void drawSlot(GuiGraphics guiGraphics, int x0, int y0) {
        int inset = SLOT_SIZE - 2;
        guiGraphics.blit(SLOT_TEXTURE, x0 + 1, y0 + 1, inset, inset,
                1f, 1f, SLOT_SIZE - 2, SLOT_SIZE - 2, SLOT_SIZE, SLOT_SIZE);

        int iconDraw = Math.round(SLOT_SIZE * 0.8f);
        int iconX = x0 + (SLOT_SIZE - iconDraw) / 2;
        int iconY = y0 + (SLOT_SIZE - iconDraw) / 2;
        guiGraphics.blit(ICONS_TEXTURE, iconX, iconY, iconDraw, iconDraw,
                DISALLOWED_ICON_U, 0f, ICON_UV_SIZE, ICON_UV_SIZE, ICONS_TEXTURE_WIDTH, ICONS_TEXTURE_HEIGHT);
    }
}