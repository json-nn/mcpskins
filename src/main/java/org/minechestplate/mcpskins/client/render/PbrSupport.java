package org.minechestplate.mcpskins.client.render;

import net.neoforged.fml.ModList;

/** Fetch PBR maps when a shader mod is present, not only when a pack is active: a texture is fetched once. */
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
