package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A file fetched alongside a skin texture. Adding another kind is one entry in {@link #ALL}.
 *
 * @param suffix         appended to the texture path
 * @param afterExtension true to append after {@code .png}, false to insert before it
 * @param blocking       whether the texture waits for it before being registered
 */
public record TextureCompanion(String id, String suffix, boolean afterExtension,
                               BooleanSupplier wanted, boolean blocking) {

    private static final String PNG = ".png";

    /**
     * Animation metadata blocks because it decides the frame size; the LabPBR maps block
     * because a shader looks them up the moment the texture first binds and caches a miss.
     */
    public static final List<TextureCompanion> ALL = List.of(
            new TextureCompanion("animation", ".mcmeta", true, () -> true, true),
            new TextureCompanion("normal", "_n", false, PbrSupport::wanted, true),
            new TextureCompanion("specular", "_s", false, PbrSupport::wanted, true));

    public boolean isWanted() {
        return wanted.getAsBoolean();
    }

    /** Null if the texture path is not a png. */
    public ResourceLocation pathFor(ResourceLocation texture) {
        String path = texture.getPath();
        if (afterExtension) {
            return ResourceLocation.tryBuild(texture.getNamespace(), path + suffix);
        }
        if (!path.endsWith(PNG)) return null;
        return ResourceLocation.tryBuild(texture.getNamespace(),
                path.substring(0, path.length() - PNG.length()) + suffix + PNG);
    }

    public boolean matches(String path) {
        return afterExtension ? path.endsWith(suffix) : path.endsWith(suffix + PNG);
    }

    /** The texture a companion path belongs to. */
    public String textureOf(String companionPath) {
        if (afterExtension) {
            return companionPath.substring(0, companionPath.length() - suffix.length());
        }
        return companionPath.substring(0, companionPath.length() - (suffix + PNG).length()) + PNG;
    }

    public static TextureCompanion matching(String path) {
        for (TextureCompanion companion : ALL) {
            if (companion.matches(path)) return companion;
        }
        return null;
    }
}
