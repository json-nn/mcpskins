package org.minechestplate.mcpskins.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animates skin textures by uploading frames into a texture the game already owns.
 * <p>
 * Deliberately does not wrap or subclass the texture. A skin texture has to stay a plain
 * {@code SimpleTexture} for a shader mod to attach PBR maps to it: a subclass was tried and
 * measured, and Iris stopped looking up the normal and specular maps altogether. Driving the
 * upload from the GL id leaves the texture object untouched, which keeps both working.
 * <p>
 * One shared counter drives every animation, so they stay in step and none drift after a stall.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class SkinTextureAnimator {

    private static final class Animation {
        final NativeImage strip;
        final SkinAnimationMeta meta;
        int lastFrame = -1;
        int lastBlend = -1;

        Animation(NativeImage strip, SkinAnimationMeta meta) {
            this.strip = strip;
            this.meta = meta;
        }
    }

    private static final Map<ResourceLocation, Animation> ANIMATED = new ConcurrentHashMap<>();
    private static int ticks;

    private SkinTextureAnimator() {
    }

    /**
     * Takes over an already registered texture: resizes it to one frame and draws the first.
     *
     * @param pngBytes the full frame strip, as delivered
     * @param meta     null for a static texture, which just clears any previous animation
     */
    public static void register(ResourceLocation location, byte[] pngBytes, SkinAnimationMeta meta) {
        forget(location);
        if (meta == null) return;
        try (InputStream in = new ByteArrayInputStream(pngBytes)) {
            Animation animation = new Animation(NativeImage.read(in), meta);
            ANIMATED.put(location, animation);
            RenderSystem.assertOnRenderThreadOrInit();
            TextureUtil.prepareImage(textureId(location), 0, meta.frameWidth(), meta.frameHeight());
            upload(location, animation, meta.frames().get(0).index(), 0, 0f);
        } catch (Exception e) {
            forget(location);
            MCPSkins.LOGGER.warn("[MCPSkins] Could not start the animation for '{}'; it stays static.",
                    location, e);
        }
    }

    public static void forget(ResourceLocation location) {
        Animation previous = ANIMATED.remove(location);
        if (previous != null) {
            previous.strip.close();
        }
    }

    public static void clear() {
        for (Animation animation : ANIMATED.values()) {
            animation.strip.close();
        }
        ANIMATED.clear();
    }

    public static boolean isAnimated(ResourceLocation location) {
        return ANIMATED.containsKey(location);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ANIMATED.isEmpty()) return;
        ticks++;
        for (Map.Entry<ResourceLocation, Animation> entry : ANIMATED.entrySet()) {
            try {
                advance(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                // One bad texture must not take the render thread down with it.
                forget(entry.getKey());
                MCPSkins.LOGGER.warn("[MCPSkins] Dropping animated texture '{}' after an upload failure.",
                        entry.getKey(), e);
            }
        }
    }

    private static void advance(ResourceLocation location, Animation animation) {
        SkinAnimationMeta meta = animation.meta;
        int position = ticks % Math.max(1, meta.totalTicks());

        int frameIndex = 0;
        int elapsed = 0;
        for (int i = 0; i < meta.frames().size(); i++) {
            int time = meta.frames().get(i).time();
            if (position < elapsed + time) {
                frameIndex = i;
                break;
            }
            elapsed += time;
        }

        // Quantised, so a still frame costs no upload and interpolation costs at most one a tick.
        int blend = meta.interpolate()
                ? (position - elapsed) * 100 / Math.max(1, meta.frames().get(frameIndex).time())
                : 0;
        if (frameIndex == animation.lastFrame && blend == animation.lastBlend) return;
        animation.lastFrame = frameIndex;
        animation.lastBlend = blend;

        upload(location, animation,
                meta.frames().get(frameIndex).index(),
                meta.frames().get((frameIndex + 1) % meta.frames().size()).index(),
                blend / 100.0f);
    }

    /** Writes a scratch frame and uploads it; the strip stays the source of truth. */
    private static void upload(ResourceLocation location, Animation animation, int from, int to, float progress) {
        SkinAnimationMeta meta = animation.meta;
        int fw = meta.frameWidth();
        int fh = meta.frameHeight();
        int columns = Math.max(1, animation.strip.getWidth() / fw);

        int fromX = (from % columns) * fw;
        int fromY = (from / columns) * fh;
        int toX = (to % columns) * fw;
        int toY = (to / columns) * fh;
        boolean blending = meta.interpolate() && progress > 0f;

        try (NativeImage frame = new NativeImage(fw, fh, false)) {
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    int pixel = animation.strip.getPixelRGBA(fromX + x, fromY + y);
                    if (blending) {
                        pixel = lerp(pixel, animation.strip.getPixelRGBA(toX + x, toY + y), progress);
                    }
                    frame.setPixelRGBA(x, y, pixel);
                }
            }
            RenderSystem.bindTexture(textureId(location));
            frame.upload(0, 0, 0, false);
        }
    }

    private static int textureId(ResourceLocation location) {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location, null);
        if (texture == null) {
            throw new IllegalStateException("No registered texture for " + location);
        }
        return texture.getId();
    }

    /** NativeImage packs pixels as ABGR, so every channel interpolates the same way. */
    private static int lerp(int from, int to, float progress) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >> shift) & 0xFF;
            int b = (to >> shift) & 0xFF;
            out |= (a + Math.round((b - a) * progress)) << shift;
        }
        return out;
    }
}
