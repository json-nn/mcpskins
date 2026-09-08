package org.minechestplate.mcpskins.client.render;

import net.neoforged.fml.ModList;

/**
 * Whether shader PBR maps are worth fetching. Keyed on the mod being present rather than on a
 * pack being active, since a player can switch pack at any moment and a texture is fetched once.
 */
public final class PbrSupport {

    private static Boolean shaderModPresent;

    private PbrSupport() {
    }

    public static boolean wanted() {
        if (shaderModPresent == null) {
            shaderModPresent = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
        }
        return shaderModPresent;
    }
}
