package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a skin to its override files, falling back to the base asset. A colon in the skin id
 * overrides the namespace; the base id's colon becomes a folder. Runs every frame: paths are
 * memoized, presence is re-polled so an in-flight asset is noticed. Path table in docs/ai/data-and-ownership.md.
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

    // Sentinel, compared by identity: computeIfAbsent stores nothing for a null.
    private static final ResourceLocation INVALID_LOCATION =
            ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "invalid_path_sentinel");
    private static final ModelPaths INVALID_MODEL_PATHS = new ModelPaths(INVALID_LOCATION, INVALID_LOCATION);

    private static final String ANIMATION_PATH_FORMAT = "animations/skins/%s/%s.animation.json";

    private SkinAssetResolver() {
    }

    public static ResourceLocation resolveTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s.png", fallback);
    }

    // Fetches <ns>:geo_models/<sub>.json; returns the collapsed <ns>:<sub> TACZ's model registry uses.
    public static ResourceLocation resolveModel(ResourceLocation baseModelLocation, String skinId) {
        if (baseModelLocation == null || skinId == null || skinId.isBlank()) return null;

        ModelPaths paths = MODEL_PATH_CACHE.computeIfAbsent(
                new ModelKey(baseModelLocation, skinId),
                key -> buildModelPaths(key.baseModelLocation(), key.skinId()));
        if (paths == INVALID_MODEL_PATHS) return null;

        return ClientSkinAssetCache.checkOrRequestGeoModel(paths.physical(), paths.collapsed()) ? paths.collapsed() : null;
    }

    private static ModelPaths buildModelPaths(ResourceLocation baseModelLocation, String skinId) {
        String namespace = baseModelLocation.getNamespace();
        String basePath = baseModelLocation.getPath(); // collapsed, e.g. "gun/cannon_geo"

        int lastSlash = basePath.lastIndexOf('/');
        String dir = lastSlash >= 0 ? basePath.substring(0, lastSlash + 1) : "";
        String baseFileName = lastSlash >= 0 ? basePath.substring(lastSlash + 1) : basePath;

        String sanitizedSkinId = skinId.replace(':', '_').replace('/', '_');
        String skinSubPath = dir + baseFileName + "__skin_" + sanitizedSkinId;

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
        if (collapsed == null) return INVALID_MODEL_PATHS;

        return new ModelPaths(physical, collapsed);
    }


    public static ResourceLocation resolveIcon(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_icon.png", fallback);
    }

    // The 3:1 silhouette TACZ draws bottom-right while the gun is held.
    public static ResourceLocation resolveHud(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud.png", fallback);
    }

    // fallback may be null: TACZ then tints the normal HUD icon red instead.
    public static ResourceLocation resolveHudEmpty(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_hud_empty.png", fallback);
    }

    public static ResourceLocation resolveLodTexture(String modId, String baseGunId, String skinId, ResourceLocation fallback) {
        return resolve(modId, baseGunId, skinId, "textures/skins/%s/%s_lod.png", fallback);
    }

    // animations/skins/<gun ns>/<gun path>/<skin>.animation.json, merged by SkinAnimationInjector. Guns only.
    public static ResourceLocation resolveAnimation(String modId, String baseGunId, String skinId, ResourceLocation baseAnimation) {
        if (modId == null || baseGunId == null || skinId == null || skinId.isBlank()) return null;

        ResourceLocation file = RESOLVE_CACHE.computeIfAbsent(
                new ResolveKey(modId, baseGunId, skinId, ANIMATION_PATH_FORMAT),
                key -> buildCandidate(key.modId(), key.baseGunId(), key.skinId(), key.pathFormat()));
        if (file == INVALID_LOCATION) return null;

        return ClientSkinAssetCache.checkOrRequestAnimation(file) ? SkinAnimationInjector.merged(file, baseAnimation) : null;
    }

    private static ResourceLocation resolve(String defaultModId, String baseGunId, String skinId, String pathFormat, ResourceLocation fallback) {
        if (defaultModId == null || baseGunId == null || skinId == null || skinId.isBlank()) return fallback;

        ResourceLocation candidate = RESOLVE_CACHE.computeIfAbsent(
                new ResolveKey(defaultModId, baseGunId, skinId, pathFormat),
                key -> buildCandidate(key.modId(), key.baseGunId(), key.skinId(), key.pathFormat()));
        if (candidate == INVALID_LOCATION) return fallback;

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
                                + "ignoring that skin file, the weapon keeps its base asset.",
                        skinId, baseGunId, debugId);
            }
            return INVALID_LOCATION;
        }
        return candidate;
    }

    // Clears the path memo only; asset state lives in ClientSkinAssetCache.
    public static void clearCache() {
        RESOLVE_CACHE.clear();
        MODEL_PATH_CACHE.clear();
        WARNED_INVALID.clear();
    }
}