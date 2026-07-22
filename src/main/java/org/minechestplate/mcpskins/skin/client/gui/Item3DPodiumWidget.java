package org.minechestplate.mcpskins.skin.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.Lighting;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * A mouse-controlled 3D preview of an {@link ItemStack} inside a GUI screen (used by
 * {@code SkinArmoryScreen}): drag to rotate, scroll to zoom, and a fault-tolerant
 * fallback to a flat icon if rendering ever throws.
 * <p>
 * Uses a dark backdrop instead of Minecraft's blur, since that only triggers via
 * {@code Screen#renderBackground(...)}, which {@code SkinArmoryScreen} never calls.
 * Renders with {@link #RENDER_CONTEXT} - {@code FIXED} is the only
 * {@code ItemDisplayContext} that doesn't clip or flatten long weapon models on this podium.
 */
public final class Item3DPodiumWidget {

    // Finalized during development (see class javadoc) - not user-facing/adjustable.
    private static final ItemDisplayContext RENDER_CONTEXT = ItemDisplayContext.FIXED;

    // Full-bright packed light, same as vanilla's GuiGraphics#renderItem uses for icons
    private static final int FULL_BRIGHT_PACKED_LIGHT = 0xF000F0;

    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 2.5f;
    private static final float MAX_PITCH = 80f;
    private static final float AUTO_ROTATE_DEG_PER_SEC = 12f;
    // Degrees of rotation per pixel of mouse movement
    private static final float DRAG_SENSITIVITY = 0.5f;
    private static final float ZOOM_STEP = 0.12f;

    private ItemStack stack = ItemStack.EMPTY;
    private int x, y, width, height;

    private float yaw = 25f;
    private float pitch = -12f;
    private float zoom = 1f;
    private boolean dragging = false;
    // Auto-rotation stops for good once the player drags the podium
    private boolean userHasInteracted = false;
    private long lastFrameNanos = -1L;

    // If rendering throws once, fall back to a flat icon instead of retrying every frame
    private boolean renderFailed = false;
    private boolean warnedOnce = false;

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
        // Pitch is intentionally clamped so drag can't flip the model upside down
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

    /**
     * @param accentColor backdrop accent stripe color (usually the current skin's
     *                    {@code labelColor}) - purely decorative
     */
    public void render(GuiGraphics guiGraphics, float partialTick, int accentColor) {
        if (width <= 0 || height <= 0) return;

        // Backdrop draws first, under the model; the frame draws last (see renderFrame)
        // so long weapon models don't paint over it at default zoom
        renderBackdropFill(guiGraphics);

        if (stack.isEmpty()) {
            lastFrameNanos = -1L;
            renderFrame(guiGraphics, accentColor);
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos < 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        // Caps the delta so a lag spike doesn't snap auto-rotation forward
        deltaSeconds = Mth.clamp(deltaSeconds, 0f, 0.25f);

        if (!dragging && !userHasInteracted) {
            yaw += AUTO_ROTATE_DEG_PER_SEC * deltaSeconds;
        }

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        float baseScale = Math.min(width, height) * 0.55f;

        // Clip to the panel so extreme zoom can't spill into neighboring panels
        guiGraphics.enableScissor(x, y, x + width, y + height);
        try {
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

        // Frame draws after the scene so it always sits on top of the model (see renderFrame)
        renderFrame(guiGraphics, accentColor);
    }

    private void renderItem3D(GuiGraphics guiGraphics, int centerX, int centerY, float baseScale) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(centerX, centerY, 150.0);
            float scale = baseScale * zoom;
            // Y is mirrored to keep the item right-side up under ItemDisplayContext.FIXED
            pose.scale(scale, -scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));

            // 3D lighting instead of flat GUI lighting, or a rotated model would still look flat
            Lighting.setupFor3DItems();
            // The mirror above flips winding order, so the GPU would otherwise cull visible faces
            RenderSystem.disableCull();
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
                // Same flush() vanilla GuiGraphics#renderItem does, so the draw calls
                // don't stay buffered and surface over the next frame out of order
                guiGraphics.flush();
            } finally {
                RenderSystem.enableCull();
                Lighting.setupForFlatItems();
            }
        } finally {
            pose.popPose();
        }
    }

    private void renderFlatFallback(GuiGraphics guiGraphics, int centerX, int centerY) {
        guiGraphics.renderItem(stack, centerX - 8, centerY - 8);
    }

    /** Just the backdrop gradient - the frame/accent stripe is drawn separately, see {@link #renderFrame}. */
    private void renderBackdropFill(GuiGraphics guiGraphics) {
        int x1 = x + width;
        int y1 = y + height;
        // Dark studio backdrop instead of blur (see class javadoc)
        guiGraphics.fillGradient(x, y, x1, y1, 0xE0141414, 0xF2060606);
    }

    /**
     * The viewport border and rarity accent stripe, drawn last so it stays on top of
     * long weapon models that reach the edge of the box at default zoom.
     * <p>
     * The depth test is off and explicitly flushed before re-enabling it: {@code fill}/
     * {@code renderOutline} buffer their vertices until the next flush, and without this
     * the frame would reach the GPU after the depth test was back on and lose to
     * {@link #renderItem3D}'s depth buffer.
     */
    private void renderFrame(GuiGraphics guiGraphics, int accentColor) {
        int x1 = x + width;
        int y1 = y + height;
        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.fill(x, y, x1, y + 2, (accentColor & 0xFFFFFF) | 0x90000000);
        guiGraphics.renderOutline(x, y, width, height, 0x40FFFFFF);
        // Explicit flush() while the depth test is still off - see this method's javadoc
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    public ItemDisplayContext currentContext() {
        return RENDER_CONTEXT;
    }
}