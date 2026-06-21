package org.minechestplate.mcpskins.skin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Axis;

public class RenderHelper {

    /**
     * Renders an ItemStack in full 3D inside a GUI.
     */
    public static void render3DItem(GuiGraphics guiGraphics, ItemStack stack, int x, int y, int scale, float rotX, float rotY) {
        if (stack.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();

        pose.pushPose();

        // Position on screen and scale
        pose.translate(x, y, 250.0F); // High Z to prevent clipping with UI backgrounds
        pose.scale(scale, -scale, scale);

        // Apply interactive rotations (Mouse drag)
        pose.mulPose(Axis.XP.rotationDegrees(rotX));
        pose.mulPose(Axis.YP.rotationDegrees(rotY));

        RenderSystem.applyModelViewMatrix();

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        // FIXED display context is best for custom 3D viewers as it ignores the 2D inventory flattening
        minecraft.getItemRenderer().renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                15728880, // Full light
                OverlayTexture.NO_OVERLAY,
                pose,
                bufferSource,
                minecraft.level,
                0
        );

        bufferSource.endBatch();
        pose.popPose();
    }
}