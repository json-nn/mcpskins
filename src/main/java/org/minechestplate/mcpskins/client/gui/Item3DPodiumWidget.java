package org.minechestplate.mcpskins.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.util.RenderDistance;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;

import java.lang.reflect.Field;

/**
 * Mouse-controlled 3D preview of an {@link ItemStack}: drag to rotate, scroll to zoom, with a
 * fallback to a flat icon if rendering throws. {@code FIXED} is the only
 * {@link ItemDisplayContext} that neither clips nor flattens long weapon models here.
 */
public final class Item3DPodiumWidget {

    private static final ItemDisplayContext RENDER_CONTEXT = ItemDisplayContext.FIXED;

    // Full-bright packed light, same as vanilla's GuiGraphics#renderItem uses for icons
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

    // If rendering throws once, fall back to a flat icon instead of retrying every frame
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

    /**
     * @param accentColor backdrop accent stripe color (usually the current skin's
     *                    {@code labelColor})
     */
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
        float baseScale = Math.min(width, height) * 0.55f;

        guiGraphics.enableScissor(x, y, x + width, y + height);
        try {
            // The panel behind sits at GUI z=0, mid depth buffer, so a long gun scaled up and
            // turned edge-on reaches past it and its far end fails the depth test. glClear
            // honours the scissor, so this clears only the podium and the model keeps the full
            // range for self-occlusion. Tooltips draw later and nearer, so they still win.
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
            // Y is mirrored to keep the item right-side up under ItemDisplayContext.FIXED
            pose.scale(scale, -scale, scale);
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));

            Lighting.setupFor3DItems();
            RenderSystem.disableCull(); // the mirror above flips winding order

            // Without this, TACZ picks the LOD geo-model here instead of the full one, since
            // RenderDistance.inRenderHighPolyModelDistance only returns true near a recent GUI
            // render timestamp. TACZ's own GunSmithTableScreen does the same before its preview.
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
     * Closes the 100ms window {@link RenderDistance#markGuiRenderTimestamp()} opens. Left set,
     * it also forces full geometry for every 16px gun icon on screen. Reflective and optional:
     * if TACZ moves the field the hint just stays open, as it did before.
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
            // Best effort - worst case the hint stays open for its 100ms.
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
