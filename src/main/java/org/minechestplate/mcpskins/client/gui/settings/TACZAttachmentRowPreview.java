package org.minechestplate.mcpskins.client.gui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Stand-in for {@code GunRefitScreen}'s attachment slot row, drawn with TACZ's textures at its
 * real position so the picker can align against it. Every slot shows "disallowed" (no gun to ask).
 * Constants are copied from {@code GunRefitScreen}, which is internal; keep them in step.
 */
public final class TACZAttachmentRowPreview {

    private static final ResourceLocation SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot.png");
    private static final ResourceLocation ICONS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot_icons.png");

    // == GunRefitScreen.SLOT_SIZE / ICON_UV_SIZE
    private static final int SLOT_SIZE = 18;
    private static final int ICON_UV_SIZE = 32;
    // Left to right: GRIP, LASER, MUZZLE, SCOPE, STOCK, EXTENDED_MAG, disallowed
    private static final int ICONS_TEXTURE_WIDTH = ICON_UV_SIZE * 7;
    private static final int ICONS_TEXTURE_HEIGHT = ICON_UV_SIZE;
    private static final int DISALLOWED_ICON_U = ICON_UV_SIZE * 6;

    // == GunRefitScreen.addAttachmentTypeButtons(): from (width - 30, 10), each slot SLOT_SIZE further left
    private static final int ROW_START_X_INSET = 30;
    private static final int ROW_START_Y = 10;
    private static final int SLOT_COUNT = 6;

    private TACZAttachmentRowPreview() {
    }

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