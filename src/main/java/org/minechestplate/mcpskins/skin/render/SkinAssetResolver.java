package org.minechestplate.mcpskins.skin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves optional skin override files (texture, icon, HUD, LOD, geo-model) against the
 * active resource packs, falling back to the weapon's base asset when no override exists.
 * <p>
 * {@code baseGunId} and {@code skinId} may themselves be "namespace:path" for a weapon or
 * skin from a third-party gunpack/resource pack; a colon in {@code skinId} is treated as
 * an explicit namespace override, and {@code baseGunId}'s colon is folded into a subfolder
 * since it isn't valid inside a {@link ResourceLocation} path.
 * <p>
 * Existence checks go through {@link Minecraft#getResourceManager()} and are cached, since
 * this runs on essentially every render frame. Call {@link #clearCache()} after adding new
 * skin files without a client restart.
 */
public final class SkinAssetResolver {
    private static final Map<String, Boolean> EXISTS_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_INVALID = ConcurrentHashMap.newKeySet();

    private SkinAssetResolver() {
    }

    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    /**
     * Resolves a skin's full geo-model override. A gun model has two path forms for the
     * same file: the <b>physical path</b> ({@code assets/<namespace>/geo_models/<sub>.json}),
     * used to check existence, and the <b>collapsed form</b> ({@code namespace:<sub>}, no
     * {@code geo_models/} prefix or {@code .json} suffix), which is what TACZ's own config
     * and asset manager expect. Given the base model's real location (see
     * {@link GunModelPatcher#getBaseModelLocation}), derives a skin-specific file name in
     * the same folder, e.g. base model {@code create_armorer:gun/cannon_geo} and skin id
     * {@code "galaxy"} looks for {@code create_armorer:gun/cannon_geo__skin_galaxy}.
     * <p>
     * Generic over which model it's resolving - also used for the LOD geo-model by passing
     * {@link GunModelPatcher#getBaseLodModelLocation} instead of the main model location.
     *
     * @return the collapsed-form location of the skin's geo-model, or {@code null} if the
     *         base location is unknown or no matching file exists
     */
    public static ResourceLocation resolveModel(ResourceLocation baseModelLocation, String skinId) {
        if (baseModelLocation == null || skinId == null || skinId.isBlank()) return null;

        String namespace = baseModelLocation.getNamespace();
        String basePath = baseModelLocation.getPath(); // collapsed form, e.g. "gun/cannon_geo"

        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "";
        String baseFileName = lastSlash >= 0 ? basePath.substring(lastSlash + 1) : basePath;

        String sanitizedSkinId = skinId.replace(':', '_').replace('/', '_');
        String skinSubPath = dir + baseFileName + "__skin_" + sanitizedSkinId; // collapsed form

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
     * Resolves a skin's optional 2D inventory icon override, a separate image from the
     * 3D model's texture. If {@code <skinId>_icon.png} doesn't exist, the base weapon's
     * icon is kept.
     */
    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    /**
     * Resolves a skin's optional HUD icon override, the weapon silhouette TACZ draws
     * bottom-right while it's held. Expects a 3:1 aspect ratio.
     */
    public static ResourceLocation resolveHud(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud.png", fallback);
    }

    /**
     * Resolves a skin's optional "out of ammo" HUD variant. {@code fallback} may itself be
     * {@code null} if the base weapon has no such variant, in which case TACZ tints the
     * normal HUD icon red instead.
     */
    public static ResourceLocation resolveHudEmpty(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud_empty.png", fallback);
    }

    /**
     * Resolves a skin's optional LOD texture override, independent of {@link #resolveTexture}.
     */
    public static ResourceLocation resolveLodTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_lod.png", fallback);
    }

    private static ResourceLocation resolve(String defaultModId, String baseGunId, String skinId, String pathFormat, ResourceLocation fallback) {
        if (defaultModId == null || baseGunId == null || skinId == null || skinId.isBlank()) return fallback;

        String skinNamespace = defaultModId;
        String skinPath = skinId;
        int colon = skinId.indexOf(':');
        if (colon >= 0) {
            skinNamespace = skinId.substring(0, colon);
            skinPath = skinId.substring(colon + 1);
        }

        String sanitizedGunId = baseGunId.replace(':', '/');
        String path = String.format(pathFormat, sanitizedGunId, skinPath);

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
