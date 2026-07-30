package org.minechestplate.mcpskins.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Draws the skin-mode toggle button. Shared by {@link TACZRefitSkinOverlay} and the
 * button-position picker so both render the same button.
 * <p>
 * Uses TACZ's own {@code refit_slot.png} for the box instead of a custom texture, so the
 * button reads as one more slot in the attachment row and picks up resource pack retextures
 * automatically.
 */
public final class RefitToggleButtonRenderer {

    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "textures/gui/skin_switch_icon.png");
    private static final int ICON_TEX_SIZE = 64;

    private static final ResourceLocation TACZ_SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("tacz", "textures/gui/refit_slot.png");
    // Native size of refit_slot.png (== GunRefitScreen.SLOT_SIZE)
    private static final int TACZ_SLOT_TEX_SIZE = 18;

    private RefitToggleButtonRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, int x0, int y0, int size, boolean hovered, boolean active) {
        // Lit state shows the full 18x18 box with its highlight ring; resting state is
        // inset by 1px with no ring - matches GunAttachmentSlot's own two states
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