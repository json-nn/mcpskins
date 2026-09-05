package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Predicate;

/**
 * A file fetched alongside a skin texture.
 * <p>
 * Every extra file a texture can carry is declared here rather than wired in by hand, so adding
 * one later is a single entry: give it a suffix, say whether it is worth fetching, and say
 * whether the texture has to wait for it. Nothing else in the pipeline needs to know it exists.
 *
 * @param id       short name, used in logs
 * @param suffix   appended to the texture path; {@code .mcmeta} extends the file name, while a
 *                 LabPBR suffix like {@code _n} goes before the extension
 * @param extends_ true to append after {@code .png}, false to insert before it
 * @param wanted   whether this client should fetch it at all
 * @param blocking whether the texture waits for it before being registered. Anything that
 *                 changes how the texture is built, or that something else looks up the moment
 *                 the texture first draws, has to be in hand first
 */
public record TextureCompanion(String id, String suffix, boolean extends_,
                               Predicate<Void> wanted, boolean blocking) {

    private static final String PNG = ".png";

    /**
     * Animation metadata blocks because it decides the texture's frame size, and the LabPBR
     * maps block because a shader looks them up when the texture first binds and caches the
     * miss. Both are skipped entirely when they cannot apply.
     */
    public static final List<TextureCompanion> ALL = List.of(
            new TextureCompanion("animation", ".mcmeta", true, ignored -> true, true),
            new TextureCompanion("normal", "_n", false, ignored -> PbrSupport.wanted(), true),
            new TextureCompanion("specular", "_s", false, ignored -> PbrSupport.wanted(), true));

    public boolean isWanted() {
        return wanted.test(null);
    }

    /** The companion's own path, or null if the texture path is not a png. */
    public ResourceLocation pathFor(ResourceLocation texture) {
        String path = texture.getPath();
        if (extends_) {
            return ResourceLocation.tryBuild(texture.getNamespace(), path + suffix);
        }
        if (!path.endsWith(PNG)) return null;
        return ResourceLocation.tryBuild(texture.getNamespace(),
                path.substring(0, path.length() - PNG.length()) + suffix + PNG);
    }

    /** True when {@code path} names this companion of some texture. */
    public boolean matches(String path) {
        return extends_ ? path.endsWith(suffix) : path.endsWith(suffix + PNG);
    }

    /** The texture a companion path belongs to. */
    public String textureOf(String companionPath) {
        if (extends_) {
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
