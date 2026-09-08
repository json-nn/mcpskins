package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves optional skin override files, falling back to the base asset when none exists.
 * <p>
 * A colon in skinId is an explicit namespace override; the base id's colon folds into a
 * subfolder, since it is not valid in a ResourceLocation path.
 * <p>
 * This runs every render frame, so path construction is memoized. The presence check is not:
 * an in-flight asset has to be re-polled to notice it arriving.
 */
public final class SkinAssetResolver {
    private static final Set<String> WARNED_INVALID = ConcurrentHashMap.newKeySet();

    private record ResolveKey(String modId, String baseGunId, String skinId, String pathFormat) {
    }

    private record ModelKey(ResourceLocation baseModelLocation, String skinId) {
    }

    private record ModelPaths(ResourceLocation physical, ResourceLocation collapsed) {
    }

    private static final Map<ResolveKey, ResourceLocation> RESOLVE_CACHE = new ConcurrentHashMap<>();
    private static final Map<ModelKey, ModelPaths> MODEL_PATH_CACHE = new ConcurrentHashMap<>();

    /** Sentinel, compared by identity: {@code computeIfAbsent} stores nothing for a null. */
    private static final ResourceLocation INVALID_LOCATION =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "invalid_path_sentinel");
    private static final ModelPaths INVALID_MODEL_PATHS = new ModelPaths(INVALID_LOCATION, INVALID_LOCATION);

    private SkinAssetResolver() {
    }

    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    /**
     * Two path forms are in play: the physical {@code assets/<ns>/geo_models/<sub>.json} used to
     * check existence, and the collapsed {@code ns:<sub>} that TACZ's asset manager expects. So
     * {@code create_armorer:gun/cannon_geo} plus skin "galaxy" becomes
     * {@code create_armorer:gun/cannon_geo__skin_galaxy}.
     *
     * @return the collapsed location, or null if the base is unknown or no file exists
     */
    public static ResourceLocation resolveModel(ResourceLocation baseModelLocation, String skinId) {
        if (baseModelLocation == null || skinId == null || skinId.isBlank()) return null;

        ModelPaths paths = MODEL_PATH_CACHE.computeIfAbsent(
                new ModelKey(baseModelLocation, skinId),
                key -> buildModelPaths(key.baseModelLocation(), key.skinId()));
        if (paths == INVALID_MODEL_PATHS) return null; // already warned once inside buildModelPaths

        return ClientSkinAssetCache.checkOrRequestGeoModel(paths.physical(), paths.collapsed()) ? paths.collapsed() : null;
    }

    private static ModelPaths buildModelPaths(ResourceLocation baseModelLocation, String skinId) {
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
            return INVALID_MODEL_PATHS;
        }
        ResourceLocation collapsed = ResourceLocation.tryBuild(namespace, skinSubPath);
        // Defensive only - physical already built fine with the same characters.
        if (collapsed == null) return INVALID_MODEL_PATHS;

        return new ModelPaths(physical, collapsed);
    }


    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    /** The silhouette TACZ draws bottom-right while the weapon is held. Expects 3:1. */
    public static ResourceLocation resolveHud(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud.png", fallback);
    }

    /** {@code fallback} may be null: TACZ then tints the normal HUD icon red instead. */
    public static ResourceLocation resolveHudEmpty(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud_empty.png", fallback);
    }

    public static ResourceLocation resolveLodTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_lod.png", fallback);
    }

    private static ResourceLocation resolve(String defaultModId, String baseGunId, String skinId, String pathFormat, ResourceLocation fallback) {
        if (defaultModId == null || baseGunId == null || skinId == null || skinId.isBlank()) return fallback;

        ResourceLocation candidate = RESOLVE_CACHE.computeIfAbsent(
                new ResolveKey(defaultModId, baseGunId, skinId, pathFormat),
                key -> buildCandidate(key.modId(), key.baseGunId(), key.skinId(), key.pathFormat()));
        if (candidate == INVALID_LOCATION) return fallback; // already warned once inside buildCandidate

        return ClientSkinAssetCache.checkOrRequestTexture(candidate) ? candidate : fallback;
    }

    private static ResourceLocation buildCandidate(String defaultModId, String baseGunId, String skinId, String pathFormat) {
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
            return INVALID_LOCATION;
        }
        return candidate;
    }

    /** Clears the path memo only; asset state lives in {@link ClientSkinAssetCache}. */
    public static void clearCache() {
        RESOLVE_CACHE.clear();
        MODEL_PATH_CACHE.clear();
        WARNED_INVALID.clear();
    }
}