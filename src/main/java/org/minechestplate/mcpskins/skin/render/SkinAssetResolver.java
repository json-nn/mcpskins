package org.minechestplate.mcpskins.skin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves optional skin override files (texture, icon, geo-model) against the active
 * resource packs, falling back to the weapon's base asset when no override exists.
 * <p>
 * Both {@code baseGunId} and {@code skinId} may themselves be "namespace:path" strings
 * when referring to a weapon or skin from a third-party gunpack/resource pack. Colons
 * are not valid inside a {@link ResourceLocation} path, so {@code baseGunId} is
 * sanitized into a subfolder and a colon in {@code skinId} is treated as an explicit
 * namespace override, letting skins live in any resource pack's own namespace. Every
 * candidate location is built with {@link ResourceLocation#tryBuild}, which returns
 * {@code null} instead of throwing on invalid input, so a malformed id degrades to the
 * base asset rather than crashing the render thread.
 * <p>
 * Resource existence checks go through {@link Minecraft#getResourceManager()} and are
 * cached in memory, since this is called on essentially every render frame. Call
 * {@link #clearCache()} after dynamically adding new skin files without a client restart.
 */
public final class SkinAssetResolver {
    private static final Map<String, Boolean> EXISTS_CACHE = new ConcurrentHashMap<>();

    // Warn once per unique invalid id, rather than every frame it's rendered
    private static final Set<String> WARNED_INVALID = ConcurrentHashMap.newKeySet();

    private SkinAssetResolver() {
    }

    /**
     * Resolves a skin's 3D texture override, or {@code fallback} if none exists.
     *
     * @param modId     default namespace for skin files, used when {@code skinId} doesn't
     *                  specify its own namespace
     * @param baseGunId raw GunId of the weapon (no "default:" prefix); may itself be
     *                  "namespace:path" for a third-party gunpack weapon
     * @param skinId    the {@code mcpskins:skin_id} value, optionally "namespace:path"
     * @param fallback  the base weapon's texture, returned when no override exists
     */
    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    /**
     * Resolves a skin's full geo-model override, if one exists in an active resource pack.
     * <p>
     * A gun model has two different path forms for the same file: the <b>physical path</b>
     * ({@code assets/<namespace>/geo_models/<sub>.json}), used to check existence via the
     * vanilla resource manager, and the <b>collapsed form</b> ({@code namespace:<sub>}, no
     * {@code geo_models/} prefix or {@code .json} suffix), which is what
     * {@code GunDisplay.model} and TACZ's asset manager actually expect. This method takes
     * the real base model location (see {@link GunModelPatcher#getBaseModelLocation}, which
     * reads it directly off the weapon rather than guessing its namespace) and derives a
     * skin-specific file name in the same folder, e.g. for base model
     * {@code create_armorer:gun/cannon_geo} and skin id {@code "galaxy"}, it looks for
     * {@code create_armorer:gun/cannon_geo__skin_galaxy}.
     * <p>
     * Returns {@code null} if the base model location is unknown or no matching file
     * exists, in which case the skin stays texture-only.
     *
     * @param baseModelLocation the base weapon's real model location, or {@code null} if
     *                          it couldn't be determined
     * @param skinId            the {@code mcpskins:skin_id} value
     * @return the collapsed-form ResourceLocation of the skin's geo-model, ready to pass to
     *         {@link GunModelPatcher#getOrCreate}, or {@code null} if none exists
     */
    public static ResourceLocation resolveModel(ResourceLocation baseModelLocation, String skinId) {
        if (baseModelLocation == null || skinId == null || skinId.isBlank()) return null;

        String namespace = baseModelLocation.getNamespace();
        String basePath = baseModelLocation.getPath(); // collapsed form, e.g. "gun/cannon_geo"

        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "";
        String baseFileName = lastSlash >= 0 ? basePath.substring(lastSlash + 1) : basePath;

        // skinId is flattened into a safe filename suffix - colons/slashes aren't valid here
        String sanitizedSkinId = skinId.replace(':', '_').replace('/', '_');
        String skinSubPath = dir + baseFileName + "__skin_" + sanitizedSkinId; // collapsed form

        // Physical path has the "geo_models/" prefix and ".json" suffix that the collapsed form omits
        ResourceLocation physical = ResourceLocation.tryBuild(namespace, "geo_models/" + skinSubPath + ".json");
        if (physical == null) {
            String debugId = namespace + ":geo_models/" + skinSubPath + ".json";
            if (WARNED_INVALID.add(debugId)) {
                MCPSkins.LOGGER.warn(
                        "Skin id '{}' for model '{}' produced an invalid geo-model path ('{}') - "
                                + "ignoring geo override, weapon keeps its base geometry.",
                        skinId, baseModelLocation, debugId);
            }
            return null;
        }
        if (!exists(physical)) return null;

        return ResourceLocation.tryBuild(namespace, skinSubPath);
    }

    /**
     * Resolves a skin's optional 2D inventory icon override. TACZ's inventory icon is a
     * separate image from the 3D model's UV texture, so re-texturing the model alone does
     * not change how the item looks in the inventory. If a {@code <skinId>_icon.png} file
     * exists next to the skin's texture, it's used as the icon; otherwise the base
     * weapon's icon is kept and only the held-model texture changes.
     */
    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    private static ResourceLocation resolve(String defaultModId, String baseGunId, String skinId, String pathFormat, ResourceLocation fallback) {
        if (defaultModId == null || baseGunId == null || skinId == null || skinId.isBlank()) return fallback;

        // skinId may specify its own namespace ("<namespace>:<path>") for a skin living in
        // a third-party resource pack; otherwise it defaults to this mod's namespace
        String skinNamespace = defaultModId;
        String skinPath = skinId;
        int colon = skinId.indexOf(':');
        if (colon >= 0) {
            skinNamespace = skinId.substring(0, colon);
            skinPath = skinId.substring(colon + 1);
        }

        // baseGunId may itself be "namespace:path" for a third-party gunpack weapon;
        // colons aren't valid inside a path, so fold it into a subfolder instead
        String sanitizedGunId = baseGunId.replace(':', '/');

        String path = String.format(pathFormat, sanitizedGunId, skinPath);

        // tryBuild returns null instead of throwing on invalid characters, which is what
        // keeps a malformed id from crashing the render thread
        ResourceLocation candidate = ResourceLocation.tryBuild(skinNamespace, path);
        if (candidate == null) {
            String debugId = skinNamespace + ":" + path;
            if (WARNED_INVALID.add(debugId)) {
                MCPSkins.LOGGER.warn(
                        "Skin id '{}' for weapon '{}' produced an invalid ResourceLocation ('{}') - "
                                + "ignoring skin, falling back to the base texture.",
                        skinId, baseGunId, debugId);
            }
            return fallback;
        }

        return exists(candidate) ? candidate : fallback;
    }

    private static boolean exists(ResourceLocation location) {
        String key = location.toString();
        Boolean cached = EXISTS_CACHE.get(key);
        if (cached != null) return cached;
        boolean found = Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
        EXISTS_CACHE.put(key, found);
        return found;
    }

    /** Clears the resource-existence cache. */
    public static void clearCache() {
        EXISTS_CACHE.clear();
        WARNED_INVALID.clear();
    }
}