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
 * {@code SkinArmoryScreen}): drag to rotate, scroll to zoom, a dark studio backdrop
 * instead of blur, and a fault-tolerant fallback to a flat icon on any render error.
 * <p>
 * <b>Why a dark backdrop instead of blur:</b> vanilla's "Menu Background Blurriness"
 * setting only triggers via {@code Screen#renderBackground(...)}, which
 * {@code SkinArmoryScreen} deliberately never calls (it draws its own background via
 * {@link #renderBackdropFill} instead), so that setting can never affect this screen
 * regardless of the player's options. Third-party client blur mods are a separate
 * concern outside this mod's control - if one affects {@code SkinArmoryScreen} for a
 * player, the fix is adding it to that mod's own exclusion list, the same way TACZ's
 * author had to do for {@code GunRefitScreen}.
 * <p>
 * <b>Choosing an {@code ItemDisplayContext} and Y-axis direction:</b> rather than
 * hardcoding a guess, this widget provides an in-game testing tool - the {@code C} key
 * cycles through {@link #CONTEXT_CANDIDATES} and {@code V} toggles the Y-scale sign
 * (needed because GUI coordinates grow downward while most non-GUI item transforms
 * likely assume a normal, Y-up world coordinate system). The current combination is
 * shown as small text in the podium's corner on hover; once a context/flip combination
 * renders the weapon cleanly and right-side up, no further code changes are needed
 * beyond optionally updating {@link #DEFAULT_CONTEXT_INDEX}/{@link #DEFAULT_Y_FLIP}.
 */
public final class Item3DPodiumWidget {

    // ---- ItemDisplayContext candidates for the C key (see class javadoc) ------------------
    private static final ItemDisplayContext[] CONTEXT_CANDIDATES = {
            ItemDisplayContext.FIXED,
            ItemDisplayContext.GROUND,
            ItemDisplayContext.NONE,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            ItemDisplayContext.HEAD,
            ItemDisplayContext.GUI
    };

    /** Starting index into {@link #CONTEXT_CANDIDATES} - adjust once you've picked one. */
    private static final int DEFAULT_CONTEXT_INDEX = 0; // FIXED
    /** Starting Y-flip state - adjust once you've picked one (see class javadoc). */
    private static final boolean DEFAULT_Y_FLIP = true;

    // Full-bright packed light (blockLight=15, skyLight=15) - same value vanilla's
    // GuiGraphics#renderItem uses for GUI icons, so the model isn't shaded as if it were
    // standing in a dark cave
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
    // Once the player touches the podium with the mouse, auto-rotation stops permanently
    // for this item, so it doesn't interfere with examining a chosen angle
    private boolean userHasInteracted = false;
    private long lastFrameNanos = -1L;

    private int contextIndex = DEFAULT_CONTEXT_INDEX;
    private boolean yFlip = DEFAULT_Y_FLIP;

    // Fault tolerance: if the 3D render for the CURRENT item throws once, it isn't
    // retried every frame - falls back to a flat icon until the item changes or the
    // user tries another context via the C key
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

    /** See the C key in the class javadoc. */
    public void cycleContext() {
        contextIndex = (contextIndex + 1) % CONTEXT_CANDIDATES.length;
        renderFailed = false; // a new context deserves its own attempt
    }

    /** See the V key in the class javadoc. */
    public void toggleYFlip() {
        yFlip = !yFlip;
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, int accentColor) {
        if (width <= 0 || height <= 0) return;

        // Only the backdrop fill draws before the 3D scene, since it needs to sit under
        // the model. The frame/accent stripe and debug label draw AFTER the scene instead
        // (see renderFrame/renderDebugLabel) - long weapon models often reach the edge of
        // the box at default zoom, and drawing the frame first let the model's geometry
        // paint over it.
        renderBackdropFill(guiGraphics);

        if (stack.isEmpty()) {
            lastFrameNanos = -1L;
            renderFrame(guiGraphics, accentColor);
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos < 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        // Guards against one huge "frame" after a lag spike/pause/long load, which would
        // otherwise snap auto-rotation forward by dozens of degrees in a single tick
        deltaSeconds = Mth.clamp(deltaSeconds, 0f, 0.25f);

        if (!dragging && !userHasInteracted) {
            yaw += AUTO_ROTATE_DEG_PER_SEC * deltaSeconds;
        }

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        float baseScale = Math.min(width, height) * 0.55f;

        // Clips the 3D scene strictly within its panel, so extreme zoom can't spill
        // into neighboring catalog panels
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
                                        "(y-flip={}) - falling back to a flat icon. Try another " +
                                        "ItemDisplayContext with the C key (or the Y-axis flip with V) " +
                                        "while hovering the podium.",
                                currentContext(), yFlip, t);
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
        renderDebugLabel(guiGraphics, mouseX, mouseY);
    }

    private void renderItem3D(GuiGraphics guiGraphics, int centerX, int centerY, float baseScale) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(centerX, centerY, 150.0);
            float scale = baseScale * zoom;
            // yFlip: GUI coordinates grow downward, while most non-GUI ItemDisplayContext
            // transforms likely assume a normal Y-up world coordinate system
            pose.scale(scale, yFlip ? -scale : scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));

            // 3D world-style lighting rather than flat GUI lighting, or a rotated model
            // would still look like a flat card
            Lighting.setupFor3DItems();
            // Why yFlip made the model partially transparent: scale(scale, -scale, scale)
            // above is a mirror reflection on the Y axis. A mirror (unlike a rotation)
            // flips the winding order of every face, and the GPU culls faces whose winding
            // ends up "wrong" after the transform by default - so a mirrored model loses a
            // chunk of its faces to culling rather than rendering as literally transparent.
            // Disabling backface culling only while rendering the flipped scene fixes it;
            // the non-flipped scene keeps culling enabled as normal.
            boolean cullDisabledForFlip = false;
            if (yFlip) {
                RenderSystem.disableCull();
                cullDisabledForFlip = true;
            }
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
                // Same flush() vanilla GuiGraphics#renderItem does - without it the item's
                // draw calls could stay buffered and surface over the next frame out of order
                guiGraphics.flush();
            } finally {
                if (cullDisabledForFlip) {
                    RenderSystem.enableCull();
                }
                Lighting.setupForFlatItems();
            }
        } finally {
            pose.popPose();
        }
    }

    private void renderFlatFallback(GuiGraphics guiGraphics, int centerX, int centerY) {
        guiGraphics.renderItem(stack, centerX - 8, centerY - 8);
    }

    /**
     * Just the backdrop gradient - drawn first, before the 3D scene, since it's the
     * "canvas" the model stands on. The frame/accent stripe is intentionally excluded
     * here - see {@link #renderFrame}.
     */
    private void renderBackdropFill(GuiGraphics guiGraphics) {
        int x1 = x + width;
        int y1 = y + height;
        // Dark studio backdrop instead of blur (see class javadoc) - a vertical gradient,
        // slightly lighter at center, matching TACZRefitSkinOverlay's visual language
        guiGraphics.fillGradient(x, y, x1, y1, 0xE0141414, 0xF2060606);
    }

    /**
     * The viewport border and rarity accent stripe above it.
     * <p>
     * <b>Why this draws last, after the 3D model:</b> long weapon models (rifles, sniper
     * rifles) often have a bounding box under many {@code ItemDisplayContext}s that
     * reaches the edge of the box at default zoom - normal behavior, not an edge case. If
     * the frame were drawn first, the model's geometry would paint over the already-drawn
     * frame line. Drawing the frame last guarantees it always sits visibly on top,
     * regardless of how large the model is.
     * <p>
     * The depth test is disabled and flushed explicitly before re-enabling, for the same
     * reason as {@link #renderDebugLabel} - see its javadoc.
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
        // and renderDebugLabel
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    /**
     * Debug label ("Ctx: ... Y-flip: ... [C / V]") in the podium's corner.
     * <p>
     * <b>Why an explicit {@code guiGraphics.flush()} is required here:</b>
     * {@code GuiGraphics#drawString} doesn't send text vertices to the GPU immediately -
     * they sit in an internal buffer until the next {@code flush()}. Without a flush here
     * before re-enabling the depth test, the text would only reach the GPU after the test
     * was back on, and would then compare against the depth buffer written by
     * {@link #renderItem3D} - causing long weapon models to render over the label instead
     * of under it. Flushing here, while the depth test is still off, guarantees the label
     * reaches the GPU without a depth comparison and stays visible over any model.
     */
    private void renderDebugLabel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!isInBounds(mouseX, mouseY)) return;
        Minecraft mc = Minecraft.getInstance();
        String text = "Ctx: " + currentContext().name() + "  Y-flip: " + (yFlip ? "on" : "off") + "  [C / V]";

        RenderSystem.disableDepthTest();
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.drawString(mc.font, text, x + 4, y + height - mc.font.lineHeight - 4, 0x80FFFFFF, false);
        guiGraphics.flush();
        guiGraphics.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    public ItemDisplayContext currentContext() {
        return CONTEXT_CANDIDATES[contextIndex];
    }

    public boolean isYFlip() {
        return yFlip;
    }
}