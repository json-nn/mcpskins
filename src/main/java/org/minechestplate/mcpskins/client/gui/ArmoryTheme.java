package org.minechestplate.mcpskins.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Sprites, colours and text scaling for {@link SkinArmoryScreen}.
 * <p>
 * Every surface with a corner is a nine-slice sprite under
 * {@code textures/gui/sprites/armory/}, so a resource pack can replace any of them without
 * touching the layout. Straight edges - spines, bars, hairlines - stay {@code fill} calls,
 * which cost less than an atlas lookup and can take a colour directly.
 * <p>
 * The white sprites ({@code row}, {@code tile_ring}, {@code rule_fade}, {@code glow} and the
 * icons) carry shape in their alpha only, so one sprite covers every rarity including
 * datapack-defined ones.
 */
public final class ArmoryTheme {

    public static final ResourceLocation PANEL = sprite("panel");
    public static final ResourceLocation INSET = sprite("inset");
    public static final ResourceLocation INSET_FOCUS = sprite("inset_focus");
    public static final ResourceLocation CHIP = sprite("chip");
    public static final ResourceLocation CHIP_ON = sprite("chip_on");
    public static final ResourceLocation TILE = sprite("tile");
    public static final ResourceLocation TILE_RING = sprite("tile_ring");
    public static final ResourceLocation STAGE = sprite("stage");
    public static final ResourceLocation ROW = sprite("row");
    public static final ResourceLocation RULE_FADE = sprite("rule_fade");
    public static final ResourceLocation GLOW = sprite("glow");
    public static final ResourceLocation ICON_SEARCH = sprite("icon_search");
    public static final ResourceLocation ICON_LOCK = sprite("icon_lock");
    public static final ResourceLocation ICON_CHECK = sprite("icon_check");

    public static final int TEXT = 0xFFE6E8EA;
    public static final int TEXT_72 = 0xB8E6E8EA;
    public static final int TEXT_50 = 0x80E6E8EA;
    public static final int TEXT_35 = 0x59E6E8EA;
    public static final int TEXT_28 = 0x47E6E8EA;

    /** Matches the accent baked into inset_focus and chip_on, so lit edges agree. */
    public static final int ACCENT = 0xFF78AAC8;
    public static final int ACCENT_LIFT = 0xFF9CC8E0;

    public static final int RULE = 0x29E6E8EA;
    public static final int ROW_HOVER = 0x2EFFFFFF;
    public static final int ROW_SELECTED = 0x4478AAC8;
    public static final int STAGE_FLOOR = 0xFF0E1013;

    /** 6px and 7px in the design both land on the vanilla font's small variant. */
    public static final float SMALL = 0.75f;
    public static final float BASE = 1.0f;

    /**
     * A blit past this is always a bug rather than a layout, and a nine-slice tiles its
     * inner region, so an absurd size turns into millions of quads and hangs the client.
     * Dropping the draw degrades one panel instead of the whole game.
     */
    private static final int MAX_SPRITE_DIM = 4096;

    private static boolean oversizeWarned = false;

    private ArmoryTheme() {
    }

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "armory/" + name);
    }

    /**
     * Draws a sprite at its own colours.
     * <p>
     * Deliberately not overloaded with a tinted form. {@code GuiGraphics#blitSprite} has a
     * six-int signature ending {@code blitOffset, width, height} and no tint parameter at
     * all, so an extra colour argument binds silently to {@code height}. Naming the tinted
     * path differently means that mistake cannot compile.
     */
    public static void sprite(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
        if (!drawable(sprite, width, height)) {
            return;
        }
        graphics.blitSprite(sprite, x, y, width, height);
    }

    /**
     * Draws a sprite multiplied by {@code argb}, for the white shape sprites and the icons.
     * <p>
     * 1.21.1 has no tinted blit, so the colour goes through the shader. {@code innerBlit}
     * draws immediately rather than buffering, so the tint applies to this call alone. The
     * reset is in a {@code finally} because a leaked shader colour tints the rest of the
     * frame, not just this screen.
     */
    public static void spriteTinted(GuiGraphics graphics, ResourceLocation sprite,
                                    int x, int y, int width, int height, int argb) {
        if (!drawable(sprite, width, height)) {
            return;
        }
        float a = (argb >>> 24) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(r, g, b, a);
        try {
            graphics.blitSprite(sprite, x, y, width, height);
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    private static boolean drawable(ResourceLocation sprite, int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (width > MAX_SPRITE_DIM || height > MAX_SPRITE_DIM) {
            if (!oversizeWarned) {
                oversizeWarned = true;
                MCPSkins.LOGGER.error("[MCPSkins] Refusing to draw '{}' at {}x{}; a size this large "
                        + "means a bad argument, and tiling it would hang the client.", sprite, width, height);
            }
            return false;
        }
        return true;
    }

    /** Replaces the alpha byte, leaving the RGB alone. */
    public static int withAlpha(int argb, float alpha) {
        int a = Mth.clamp(Math.round(alpha * 255f), 0, 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** An opaque colour from a packed RGB, for values that arrive without an alpha byte. */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /**
     * A rarity colour bright enough to read on the dark ground. A pack can declare any
     * {@code label_color} it likes, and a near-black one would vanish on the tile ring and
     * the stage rule, so anything below the floor gets lifted toward it rather than dropped.
     */
    public static int readable(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        if (luma >= 0.45f) {
            return opaque(rgb);
        }
        float lift = luma <= 0.001f ? 1f : Math.min(1f, 0.45f / luma);
        return opaque((Math.round(Math.min(255f, r * lift)) << 16)
                | (Math.round(Math.min(255f, g * lift)) << 8)
                | Math.round(Math.min(255f, b * lift)));
    }

    public static int scaledWidth(Font font, String text, float scale) {
        return Math.round(font.width(text) * scale);
    }

    public static int scaledHeight(float scale) {
        return Math.round(8f * scale);
    }

    /** Draws at a fraction of the font's native size, for the small label rows. */
    public static void text(GuiGraphics graphics, Font font, String value, int x, int y, float scale, int color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (scale == BASE) {
            graphics.drawString(font, value, x, y, color, false);
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0.0f);
            pose.scale(scale, scale, 1.0f);
            graphics.drawString(font, value, 0, 0, color, false);
        } finally {
            pose.popPose();
        }
    }

    /** Same as {@link #text}, right-aligned so the value ends at {@code rightX}. */
    public static void textRight(GuiGraphics graphics, Font font, String value, int rightX, int y, float scale, int color) {
        text(graphics, font, value, rightX - scaledWidth(font, value, scale), y, scale, color);
    }

    /** Cuts to fit with an ellipsis rather than clipping mid-glyph. */
    public static String truncate(Font font, String value, int maxWidth, float scale) {
        if (value == null || value.isEmpty() || scaledWidth(font, value, scale) <= maxWidth) {
            return value == null ? "" : value;
        }
        String ellipsis = "...";
        int room = maxWidth - scaledWidth(font, ellipsis, scale);
        if (room <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            out.append(value.charAt(i));
            if (scaledWidth(font, out.toString(), scale) > room) {
                out.setLength(out.length() - 1);
                break;
            }
        }
        return out + ellipsis;
    }
}
