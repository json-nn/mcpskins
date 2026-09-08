package org.minechestplate.mcpskins.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.util.RenderDistance;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.BedrockModelBounds;
import org.minechestplate.mcpskins.client.render.ClientAttachmentIndexPatcher;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.lang.reflect.Field;

/**
 * Mouse-controlled 3D preview of an {@link ItemStack}: drag to rotate, scroll to zoom, with a
 * fallback to a flat icon if rendering throws. {@code FIXED} is the only
 * {@link ItemDisplayContext} that neither clips nor flattens long weapon models here.
 */
public final class Item3DPodiumWidget {

    private static final ItemDisplayContext RENDER_CONTEXT = ItemDisplayContext.FIXED;

    /** Bedrock units to blocks, the scale model bounds are measured in. */
    private static final double CENTER_SCALE = 1 / 16.0;

    /** How much of the panel a fitted model spans, leaving room for it to turn. */
    private static final double FIT_FILL = 0.8;

    private static final int FULL_BRIGHT_PACKED_LIGHT = 0xF000F0;

    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 2.5f;
    private static final float MAX_PITCH = 80f;
    private static final float AUTO_ROTATE_DEG_PER_SEC = 12f;
    private static final float DRAG_SENSITIVITY = 0.5f;
    private static final float ZOOM_STEP = 0.12f;

    private ItemStack stack = ItemStack.EMPTY;
    private int x, y, width, height;

    /** False when the caller frames the podium itself. */
    private boolean chrome = true;

    private float yaw = 25f;
    private float pitch = -12f;
    private float zoom = 1f;
    private boolean dragging = false;
    private boolean userHasInteracted = false;
    private long lastFrameNanos = -1L;

    private boolean renderFailed = false;
    private boolean warnedOnce = false;

    public void setChrome(boolean chrome) {
        this.chrome = chrome;
    }

    public void setStack(ItemStack newStack) {
        this.stack = newStack == null ? ItemStack.EMPTY : newStack;
        this.renderFailed = false;
    }

    public ItemStack getStack() {
        return stack;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean isInBounds(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void onMouseClicked() {
        dragging = true;
    }

    public void onMouseReleased() {
        dragging = false;
    }

    public void onMouseDragged(double dragDeltaX, double dragDeltaY) {
        if (!dragging) return;
        userHasInteracted = true;
        yaw += (float) dragDeltaX * DRAG_SENSITIVITY;
        pitch = Mth.clamp(pitch - (float) dragDeltaY * DRAG_SENSITIVITY, -MAX_PITCH, MAX_PITCH);
    }

    public void onMouseScrolled(double scrollDeltaY) {
        zoom = Mth.clamp(zoom + (float) scrollDeltaY * ZOOM_STEP, MIN_ZOOM, MAX_ZOOM);
    }

    public void resetView() {
        yaw = 25f;
        pitch = -12f;
        zoom = 1f;
        userHasInteracted = false;
    }

    public void render(GuiGraphics guiGraphics, float partialTick, int accentColor) {
        if (width <= 0 || height <= 0) return;

        if (chrome) {
            renderBackdropFill(guiGraphics);
        }

        if (stack.isEmpty()) {
            lastFrameNanos = -1L;
            if (chrome) {
                renderFrame(guiGraphics, accentColor);
            }
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos < 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        deltaSeconds = Mth.clamp(deltaSeconds, 0f, 0.25f); // caps a lag spike from snapping rotation forward

        if (!dragging && !userHasInteracted) {
            yaw += AUTO_ROTATE_DEG_PER_SEC * deltaSeconds;
        }

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        float baseScale = baseScale();

        guiGraphics.enableScissor(x, y, x + width, y + height);
        try {
            // A long gun scaled up and turned edge-on reaches past the panel behind it and
            // fails the depth test. glClear honours the scissor, so this clears the podium
            // only, and the model keeps its full range for self-occlusion.
            RenderSystem.depthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            if (!renderFailed) {
                try {
                    renderItem3D(guiGraphics, centerX, centerY, baseScale);
                } catch (Throwable t) {
                    renderFailed = true;
                    if (!warnedOnce) {
                        warnedOnce = true;
                        MCPSkins.LOGGER.warn(
                                "[MCPSkins] Armory 3D podium failed to render the item in context '{}' " +
                                        "- falling back to a flat icon.",
                                currentContext(), t);
                    }
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }

        if (renderFailed) {
            renderFlatFallback(guiGraphics, centerX, centerY);
        }

        if (chrome) {
            renderFrame(guiGraphics, accentColor);
        }
    }

    private void renderItem3D(GuiGraphics guiGraphics, int centerX, int centerY, float baseScale) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(centerX, centerY, 150.0);
            float scale = baseScale * zoom;
            // Y is mirrored to keep the item right-side up under FIXED.
            pose.scale(scale, -scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            applyAttachmentCentering(pose);

            Lighting.setupFor3DItems();
            RenderSystem.disableCull(); // the mirror above flips winding order

            // Or TACZ serves the LOD model here: its high-poly check wants a recent GUI render
            // timestamp. GunSmithTableScreen does the same before its own preview.
            RenderDistance.markGuiRenderTimestamp();
            try {
                mc.getItemRenderer().renderStatic(
                        stack,
                        currentContext(),
                        FULL_BRIGHT_PACKED_LIGHT,
                        OverlayTexture.NO_OVERLAY,
                        pose,
                        guiGraphics.bufferSource(),
                        mc.level,
                        0
                );
                guiGraphics.flush(); // avoids buffered draw calls surfacing over the next frame
            } finally {
                closeHighPolyHint();
                RenderSystem.enableCull();
                Lighting.setupForFlatItems();
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * Attachment geometry is authored around its mount point, so spinning it about the item
     * origin swings a long one out of the panel. Applied after the rotations, the space the item
     * renderer receives, so it holds at every angle.
     * <p>
     * The axis swap comes from TACZ's own {@code scale(-1, -1, 1)} plus a turn about Y under
     * {@code FIXED}. These signs were measured, not derived: do not change them without
     * re-measuring where a known offset lands.
     */
    private void applyAttachmentCentering(PoseStack pose) {
        BedrockModelBounds.Bounds bounds = attachmentBounds();
        if (bounds == null) return;
        Vec3 center = bounds.center();
        pose.translate(center.z * CENTER_SCALE, -center.y * CENTER_SCALE, center.x * CENTER_SCALE);
    }

    /**
     * Measured for attachments, so an unusually large or small one still opens fully in frame.
     * Weapons keep the fixed factor, since TACZ scales those per gun in a display config this
     * has no view of. Zoom multiplies whatever comes back either way.
     */
    private float baseScale() {
        float fallback = Math.min(width, height) * 0.55f;
        BedrockModelBounds.Bounds bounds = attachmentBounds();
        if (bounds == null) return fallback;

        Vec3 center = bounds.center();
        double radiusXZ = bounds.horizontalRadius(center) * CENTER_SCALE;
        double radiusY = bounds.verticalRadius(center) * CENTER_SCALE;
        if (radiusXZ <= 0 || radiusY <= 0) return fallback;

        double fit = Math.min(width * FIT_FILL / (2 * radiusXZ), height * FIT_FILL / (2 * radiusY));
        return (float) Mth.clamp(fit, fallback * 0.2, fallback * 6.0);
    }

    private BedrockModelBounds.Bounds attachmentBounds() {
        String attachmentId = TACZSkinHelper.getAttachmentId(stack);
        if (attachmentId == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(attachmentId);
        if (id == null) return null;

        ResourceLocation modelLocation = TimelessAPI.getClientAttachmentIndex(id)
                .map(ClientAttachmentIndexPatcher::getBaseModelLocation).orElse(null);
        return modelLocation == null ? null : BedrockModelBounds.of(modelLocation);
    }

    /**
     * Closes the 100ms window {@link RenderDistance#markGuiRenderTimestamp()} opens, which would
     * otherwise force full geometry for every 16px gun icon too. Optional: if TACZ moves the
     * field, the hint just stays open as it did before.
     */
    private static final Field GUI_RENDER_TIMESTAMP = resolveTimestampField();

    private static Field resolveTimestampField() {
        try {
            Field field = RenderDistance.class.getDeclaredField("GUI_RENDER_TIMESTAMP");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MCPSkins.LOGGER.info("[MCPSkins] TACZ RenderDistance.GUI_RENDER_TIMESTAMP not found; "
                    + "GUI gun icons keep rendering at full detail.");
            return null;
        }
    }

    private static void closeHighPolyHint() {
        if (GUI_RENDER_TIMESTAMP == null) {
            return;
        }
        try {
            GUI_RENDER_TIMESTAMP.setLong(null, -1L);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private void renderFlatFallback(GuiGraphics guiGraphics, int centerX, int centerY) {
        guiGraphics.renderItem(stack, centerX - 8, centerY - 8);
    }

    private void renderBackdropFill(GuiGraphics guiGraphics) {
        int x1 = x + width;
        int y1 = y + height;
        guiGraphics.fillGradient(x, y, x1, y1, 0xE0141414, 0xF2060606);
    }

    /** Drawn last, depth test off, so it stays above long weapon models. */
    private void renderFrame(GuiGraphics guiGraphics, int accentColor) {
        int x1 = x + width;
        int y1 = y + height;
        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.fill(x, y, x1, y + 2, (accentColor & 0xFFFFFF) | 0x90000000);
        guiGraphics.renderOutline(x, y, width, height, 0x40FFFFFF);
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    public ItemDisplayContext currentContext() {
        return RENDER_CONTEXT;
    }
}
