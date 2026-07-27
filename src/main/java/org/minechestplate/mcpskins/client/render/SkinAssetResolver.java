package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves optional skin override files (texture, icon, HUD, LOD, geo-model), falling back
 * to the base weapon asset when no override exists.
 * <p>
 * A colon in skinId is treated as an explicit namespace override; baseGunId's colon gets
 * folded into a subfolder since it's not valid in a ResourceLocation path.
 * <p>
 * Presence checks go through {@link ClientSkinAssetCache}, which fetches bytes from the
 * server the first time each path is asked about. Runs on basically every render frame,
 * so keep this cheap. Call {@link #clearCache()} after adding skin files without a client
 * restart.
 */
public final class SkinAssetResolver {
    private static final Set<String> WARNED_INVALID = ConcurrentHashMap.newKeySet();

    private SkinAssetResolver() {
    }

    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    /**
     * Resolves a skin's geo-model override. Has two path forms: the physical path
     * ({@code assets/<namespace>/geo_models/<sub>.json}) used to check existence, and the
     * collapsed form ({@code namespace:<sub>}, no {@code geo_models/} prefix or
     * {@code .json} suffix) that TACZ's own config and asset manager actually expect.
     * E.g. base model {@code create_armorer:gun/cannon_geo} + skin "galaxy" resolves to
     * {@code create_armorer:gun/cannon_geo__skin_galaxy}.
     * <p>
     * Also used for the LOD model by passing {@link GunModelPatcher#getBaseLodModelLocation}
     * instead of the main model location.
     *
     * @return the collapsed-form location of the skin's geo-model, or null if the base
     *         location is unknown or no matching file exists
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
        ResourceLocation collapsed = ResourceLocation.tryBuild(namespace, skinSubPath);
        if (collapsed == null) return null; // defensive only, physical already built fine with the same chars

        return ClientSkinAssetCache.checkOrRequestGeoModel(physical, collapsed) ? collapsed : null;
    }

    /** Resolves a skin's optional inventory icon override. Falls back to the base icon if
     *  {@code <skinId>_icon.png} doesn't exist. */
    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    /** Resolves a skin's optional HUD icon override (the weapon silhouette TACZ draws
     *  bottom-right while it's held). Expects a 3:1 aspect ratio. */
    public static ResourceLocation resolveHud(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud.png", fallback);
    }

    /** Resolves a skin's optional "out of ammo" HUD variant. {@code fallback} may be null
     *  if the base weapon has none - TACZ just tints the normal HUD icon red instead. */
    public static ResourceLocation resolveHudEmpty(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud_empty.png", fallback);
    }

    /** Resolves a skin's optional LOD texture override, independent of {@link #resolveTexture}. */
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

        return ClientSkinAssetCache.checkOrRequestTexture(candidate) ? candidate : fallback;
    }

    /** Clears the invalid-path warning dedup set. Asset state itself lives in
     *  {@link ClientSkinAssetCache}, cleared separately. */
    public static void clearCache() {
        WARNED_INVALID.clear();
    }
}