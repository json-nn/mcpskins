package org.minechestplate.mcpskins.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-authoritative settings. Registered as {@code ModConfig.Type.SERVER}, so values
 * live in {@code world/serverconfig/} and are pushed to every client on join.
 */
public class MCPSkinsServerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue FUSE_ENABLED;
    public static final ModConfigSpec.IntValue FUSE_COST;

    public static final ModConfigSpec.BooleanValue ALLOW_LOCKED_SKIN_PREVIEW;
    public static final ModConfigSpec.IntValue EQUIP_BYPASS_PERMISSION_LEVEL;
    public static final ModConfigSpec.IntValue ADMIN_COMMAND_PERMISSION_LEVEL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("fusion");
        FUSE_ENABLED = builder
                .comment("Allow shift + right-click fusing of unlock items into a higher rarity.")
                .define("enabled", true);
        FUSE_COST = builder
                .comment("Same-rarity unlock items consumed per fuse roll.")
                .defineInRange("cost", 3, 2, 10);
        builder.pop();

        builder.push("access");
        ALLOW_LOCKED_SKIN_PREVIEW = builder
                .comment("Allow client-side preview of a skin the player doesn't own on the refit screen.")
                .define("allowLockedSkinPreview", true);
        EQUIP_BYPASS_PERMISSION_LEVEL = builder
                .comment("Permission level allowed to equip a skin without owning it.")
                .defineInRange("equipBypassPermissionLevel", 2, 0, 4);
        ADMIN_COMMAND_PERMISSION_LEVEL = builder
                .comment("Permission level required for /mcpskins give and take.")
                .defineInRange("adminCommandPermissionLevel", 4, 2, 4);
        builder.pop();

        SPEC = builder.build();
    }

    private MCPSkinsServerConfig() {
    }

    // Accessors go through SPEC.isLoaded() since ConfigValue#get() throws until a
    // server/world is actually joined.

    public static boolean fuseEnabled() {
        return safe(FUSE_ENABLED, true);
    }

    public static int fuseCost() {
        return safe(FUSE_COST, 3);
    }

    public static boolean allowLockedSkinPreview() {
        return safe(ALLOW_LOCKED_SKIN_PREVIEW, true);
    }

    public static int equipBypassPermissionLevel() {
        return safe(EQUIP_BYPASS_PERMISSION_LEVEL, 2);
    }

    public static int adminCommandPermissionLevel() {
        return safe(ADMIN_COMMAND_PERMISSION_LEVEL, 4);
    }

    private static <T> T safe(ModConfigSpec.ConfigValue<T> value, T fallback) {
        return SPEC.isLoaded() ? value.get() : fallback;
    }
}