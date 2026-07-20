package org.minechestplate.mcpskins.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Local client preferences - not synced, purely cosmetic/UI. Editable by hand in
 * {@code mcpskins-client.toml} or in-game via {@code MCPSkinsConfigScreen}.
 */
public class MCPSkinsClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue REFIT_BUTTON_ENABLED;
    public static final ModConfigSpec.EnumValue<ScreenAnchor> REFIT_BUTTON_ANCHOR;
    public static final ModConfigSpec.IntValue REFIT_BUTTON_OFFSET_X;
    public static final ModConfigSpec.IntValue REFIT_BUTTON_OFFSET_Y;
    public static final ModConfigSpec.IntValue REFIT_BUTTON_SIZE;
    public static final ModConfigSpec.BooleanValue REFIT_BUTTON_TOOLTIP;

    public static final ModConfigSpec.BooleanValue TOAST_ENABLED;
    public static final ModConfigSpec.IntValue TOAST_DURATION_MS;

    public static final ModConfigSpec.IntValue CAROUSEL_HEIGHT;
    public static final ModConfigSpec.IntValue CAROUSEL_SLOT_SIZE;
    public static final ModConfigSpec.IntValue CAROUSEL_SLOT_SPACING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("refit_toggle_button");
        REFIT_BUTTON_ENABLED = builder
                .comment("Show the skin-mode toggle button on TACZ's refit screen.")
                .define("enabled", true);
        REFIT_BUTTON_ANCHOR = builder
                .comment("Screen corner the offsets below are measured from.")
                .defineEnum("anchor", ScreenAnchor.TOP_RIGHT);
        // Default offsetX 120 / offsetY 10 / size 18 (TOP_RIGHT) places the button directly
        // left of TACZ's own attachment row, which starts at x = width-120, y = 10
        REFIT_BUTTON_OFFSET_X = builder
                .comment("Horizontal offset in pixels from the anchor corner.")
                .defineInRange("offsetX", 120, 0, 4000);
        REFIT_BUTTON_OFFSET_Y = builder
                .comment("Vertical offset in pixels from the anchor corner.")
                .defineInRange("offsetY", 10, 0, 4000);
        REFIT_BUTTON_SIZE = builder
                .comment("Button size in pixels.")
                .defineInRange("size", 18, 12, 32);
        REFIT_BUTTON_TOOLTIP = builder
                .comment("Show the hover label under the button.")
                .define("showTooltip", true);
        builder.pop();

        builder.push("toast");
        TOAST_ENABLED = builder
                .comment("Show a confirmation toast when a skin is applied.")
                .define("enabled", true);
        TOAST_DURATION_MS = builder
                .comment("How long the toast stays on screen, in milliseconds.")
                .defineInRange("durationMs", 2200, 500, 10000);
        builder.pop();

        builder.push("carousel");
        CAROUSEL_HEIGHT = builder
                .comment("Height in pixels of the skin carousel panel.")
                .defineInRange("height", 96, 60, 160);
        CAROUSEL_SLOT_SIZE = builder
                .comment("Base size in pixels of a carousel skin slot.")
                .defineInRange("slotSize", 44, 24, 80);
        CAROUSEL_SLOT_SPACING = builder
                .comment("Horizontal spacing in pixels between carousel slots.")
                .defineInRange("slotSpacing", 60, 30, 120);
        builder.pop();

        SPEC = builder.build();
    }

    private MCPSkinsClientConfig() {
    }

    // Accessors go through SPEC.isLoaded() since ConfigValue#get() throws until the
    // config file is actually loaded (e.g. the mod list "Config" screen can open earlier).

    public static boolean refitButtonEnabled() {
        return safe(REFIT_BUTTON_ENABLED, true);
    }

    public static ScreenAnchor refitButtonAnchor() {
        return safe(REFIT_BUTTON_ANCHOR, ScreenAnchor.TOP_RIGHT);
    }

    public static int refitButtonOffsetX() {
        return safe(REFIT_BUTTON_OFFSET_X, 120);
    }

    public static int refitButtonOffsetY() {
        return safe(REFIT_BUTTON_OFFSET_Y, 10);
    }

    public static int refitButtonSize() {
        return safe(REFIT_BUTTON_SIZE, 18);
    }

    public static boolean refitButtonTooltip() {
        return safe(REFIT_BUTTON_TOOLTIP, true);
    }

    public static boolean toastEnabled() {
        return safe(TOAST_ENABLED, true);
    }

    public static int toastDurationMs() {
        return safe(TOAST_DURATION_MS, 2200);
    }

    public static int carouselHeight() {
        return safe(CAROUSEL_HEIGHT, 96);
    }

    public static int carouselSlotSize() {
        return safe(CAROUSEL_SLOT_SIZE, 44);
    }

    public static int carouselSlotSpacing() {
        return safe(CAROUSEL_SLOT_SPACING, 60);
    }

    /** No-op once loaded fails silently instead of crashing while the spec isn't ready. */
    public static <T> void trySet(ModConfigSpec.ConfigValue<T> value, T newValue) {
        if (SPEC.isLoaded()) {
            value.set(newValue);
        }
    }

    private static <T> T safe(ModConfigSpec.ConfigValue<T> value, T fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }
}