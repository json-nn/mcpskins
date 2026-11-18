package org.minechestplate.mcpskins.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * The skin-mode toggle, shared by {@link TACZRefitSkinOverlay} and the position picker. Drawn on
 * TACZ's {@code refit_slot.png} so it matches the attachment row and follows resource packs.
 */
public final class RefitToggleButtonRenderer {

    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "textures/gui/skin_switch_icon.png");
    private static final int ICON_TEX_SIZE = 64;

    private static final ResourceLocation TACZ_SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot.png");
    // == GunRefitScreen.SLOT_SIZE
    private static final int TACZ_SLOT_TEX_SIZE = 18;

    private RefitToggleButtonRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, int x0, int y0, int size, boolean hovered, boolean active) {
        // Same two states as GunAttachmentSlot: lit shows the ring, resting is inset 1px without it.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        boolean lit = hovered || active;
        if (lit) {
            guiGraphics.blit(TACZ_SLOT_TEXTURE, x0, y0, size, size,
                    0f, 0f, TACZ_SLOT_TEX_SIZE, TACZ_SLOT_TEX_SIZE, TACZ_SLOT_TEX_SIZE, TACZ_SLOT_TEX_SIZE);
        } else {
            int inset = size - 2;
            guiGraphics.blit(TACZ_SLOT_TEXTURE, x0 + 1, y0 + 1, inset, inset,
                    1f, 1f, TACZ_SLOT_TEX_SIZE - 2, TACZ_SLOT_TEX_SIZE - 2, TACZ_SLOT_TEX_SIZE, TACZ_SLOT_TEX_SIZE);
        }

        float iconAlpha = lit ? 1f : 0.8f;
        int iconDraw = Math.round(size * 0.8f);
        int iconX = x0 + (size - iconDraw) / 2;
        int iconY = y0 + (size - iconDraw) / 2;

        RenderSystem.setShaderColor(1f, 1f, 1f, iconAlpha);
        guiGraphics.blit(ICON, iconX, iconY, iconDraw, iconDraw,
                0f, 0f, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}