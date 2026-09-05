package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Whether shader PBR maps are worth fetching, and where they live.
 * <p>
 * Normal and specular maps do nothing without a shader mod, so a client running none never
 * asks for them. Keyed on the mod being present rather than on a shader pack being active,
 * because a player can switch pack at any moment and a texture is fetched once.
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

    /** {@code .../cobra.png} plus {@code _n} becomes {@code .../cobra_n.png}, the LabPBR name. */
    public static ResourceLocation sibling(ResourceLocation texture, String suffix) {
        String path = texture.getPath();
        if (!path.endsWith(".png")) return null;
        return ResourceLocation.tryBuild(texture.getNamespace(),
                path.substring(0, path.length() - 4) + suffix + ".png");
    }
}
