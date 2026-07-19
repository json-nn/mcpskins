package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a full geometry replacement for a skin (not just a texture re-paint, see
 * {@link GunDisplayInstancePatcher}), when a matching geo-model file is found by
 * {@link SkinAssetResolver#resolveModel}.
 * <p>
 * Unlike a texture, a weapon's geometry isn't stored as a simple {@code ResourceLocation}
 * after loading - it becomes a fully parsed {@code BedrockGunModel} built by TACZ's
 * private asset-loading pipeline. Rather than reimplementing that parsing by hand (fragile
 * and version-dependent), this class asks TACZ to do it: {@code GunDisplayInstance} has
 * exactly one constructor, {@code (ResourceLocation, GunDisplay)}, where {@code GunDisplay}
 * is a plain data POJO (the parsed display.json config) rather than the lazily-loaded
 * model itself. A shallow copy of the base weapon's config is made with only its model
 * field swapped, then that same constructor is called again - so TACZ's own code parses
 * the skin's geo.json exactly as it would for any real weapon, with zero pipeline logic
 * duplicated here.
 * <p>
 * None of the required fields/methods are hardcoded by name beyond what's already
 * verified ({@code getModelLocation()}); everything else is discovered via reflection in
 * {@link #discover} - by constructor signature, by field type, and by matching a field's
 * value against a known-good getter result. If discovery fails on some fork version,
 * {@link #getOrCreate} simply returns {@code null} from then on and the weapon keeps its
 * base geometry; texture/icon re-skinning is unaffected.
 * <p>
 * The copied config only has its model field changed - animations, sounds, and the icon
 * all come from the base weapon unchanged. The one hard requirement is that the skin's
 * geo-model skeleton (bone names) matches what the base weapon's animations expect;
 * a mismatch is caught by {@code GunDisplayInstance}'s own validation and handled via
 * try/catch in {@link #createInstance}, degrading to the base geometry rather than
 * crashing.
 */
public final class GunModelPatcher {

    private record CacheEntry(GunDisplayInstance base, ResourceLocation modelOverride, GunDisplayInstance result) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // -1 = not checked yet, 0 = discovery failed (feature unsupported on this fork version),
    // 1 = discovery succeeded. Checked once per session.
    private static volatile int supportState = -1;
    private static volatile boolean unsupportedWarningLogged = false;

    private static volatile Constructor<?> displayInstanceConstructor;
    private static volatile Class<?> gunDisplayClass;
    private static volatile Field configField;        // GunDisplayInstance -> GunDisplay
    private static volatile Field modelLocationField; // GunDisplay -> ResourceLocation (model)

    /**
     * Pre-flight check that asks {@code ClientAssetsManager.INSTANCE.getBedrockModelPOJO}
     * directly whether it recognizes a given model location, before attempting the
     * (expensive, Unsafe-based) instance construction. Physical file existence in a
     * resource pack (already confirmed by {@link SkinAssetResolver}) doesn't guarantee
     * TACZ's internal model registry has picked it up - these are two independent
     * mechanisms. If this probe itself can't be set up via reflection (different fork
     * version), it doesn't block the attempt - {@link #isModelRecognized} just returns
     * {@code true} and TACZ's own error handling takes over, without this class's more
     * detailed diagnostic log.
     */
    private static volatile Object clientAssetsManagerInstance;
    private static volatile Method getBedrockModelPOJOMethod;
    private static volatile boolean assetsManagerProbeDone = false;
    private static final Set<String> WARNED_UNRECOGNIZED = ConcurrentHashMap.newKeySet();

    private GunModelPatcher() {
    }

    /**
     * @param cacheKey      stable key for the (weapon, skin) pair, e.g.
     *                      {@code baseGunId + "\u0000" + skinId}
     * @param base          the base weapon's unskinned {@link GunDisplayInstance}, used as
     *                      the config template
     * @param modelOverride collapsed-form ResourceLocation of the skin's geo.json, from
     *                      {@link SkinAssetResolver#resolveModel}
     * @return a new {@link GunDisplayInstance} with the skin's geometry, or {@code null} if
     *         the feature isn't supported on this fork or building it failed (e.g.
     *         incompatible skeleton) - either way the caller should keep the base geometry
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance base, ResourceLocation modelOverride) {
        if (base == null || modelOverride == null) return null;
        if (!ensureSupported(base)) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null && existing.base() == base && modelOverride.equals(existing.modelOverride())) {
            return existing.result();
        }

        if (!isModelRecognized(modelOverride)) {
            if (WARNED_UNRECOGNIZED.add(cacheKey)) {
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Geo-model file '{}' exists in a resource pack, but TACZ's internal "
                                + "model registry doesn't recognize it - likely a folder/naming mismatch. "
                                + "See the '[MCPSkins][diag]' log line for the real model path on a "
                                + "working weapon, for comparison. Skin keeps its base geometry.",
                        modelOverride);
            }
            CACHE.put(cacheKey, new CacheEntry(base, modelOverride, null));
            return null;
        }

        GunDisplayInstance created = createInstance(base, modelOverride);
        CACHE.put(cacheKey, new CacheEntry(base, modelOverride, created));
        if (created != null) {
            MCPSkins.LOGGER.info("[MCPSkins] Built geo-model skin for '{}' (model: {}).", cacheKey, modelOverride);
        }
        return created;
    }

    /** Clears the cache of built geo-instances. Called on client resource reload. */
    public static void clear() {
        CACHE.clear();
        WARNED_UNRECOGNIZED.clear();
    }

    /**
     * Returns the base weapon's real model {@link ResourceLocation}, read directly off
     * its config rather than guessed from a naming convention - different gunpacks use
     * different namespaces/folders, so the only reliable source is the weapon's own
     * config field. {@link SkinAssetResolver#resolveModel} uses this to derive the
     * skin's geo-model path in the same namespace/folder automatically.
     * <p>
     * Returns {@code null} if reflection couldn't discover the needed fields (unsupported
     * fork version) or {@code base} has no config/model.
     */
    public static ResourceLocation getBaseModelLocation(GunDisplayInstance base) {
        if (base == null) return null;
        if (!ensureSupported(base)) return null;
        try {
            Object config = configField.get(base);
            if (config == null) return null;
            Object location = modelLocationField.get(config);
            return location instanceof ResourceLocation ? (ResourceLocation) location : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Checks via {@code ClientAssetsManager.INSTANCE.getBedrockModelPOJO(location)}
     * whether TACZ recognizes this model location. Returns {@code true} (don't block)
     * if the probe itself couldn't be set up.
     */
    private static boolean isModelRecognized(ResourceLocation location) {
        probeAssetsManagerOnce();
        if (getBedrockModelPOJOMethod == null || clientAssetsManagerInstance == null) return true;
        try {
            Object result = getBedrockModelPOJOMethod.invoke(clientAssetsManagerInstance, location);
            return result != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true; // can't ask - don't block for the same reason as above
        }
    }

    private static void probeAssetsManagerOnce() {
        if (assetsManagerProbeDone) return;
        synchronized (GunModelPatcher.class) {
            if (assetsManagerProbeDone) return;
            assetsManagerProbeDone = true;
            try {
                Class<?> assetsManagerClass = Class.forName("com.tacz.guns.client.resource.ClientAssetsManager");
                Object instance = null;
                for (Field field : assetsManagerClass.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && field.getType() == assetsManagerClass) {
                        field.setAccessible(true);
                        instance = field.get(null);
                        break;
                    }
                }
                if (instance == null) {
                    MCPSkins.LOGGER.warn("[MCPSkins] ClientAssetsManager.INSTANCE singleton field not found - "
                            + "geo-model recognition pre-check disabled (feature itself still works, only the diagnostic does not).");
                    return;
                }
                Method method = findMethod(assetsManagerClass, "getBedrockModelPOJO", ResourceLocation.class);
                if (method == null) {
                    MCPSkins.LOGGER.warn("[MCPSkins] ClientAssetsManager.getBedrockModelPOJO(ResourceLocation) not found - "
                            + "geo-model recognition pre-check disabled (feature itself still works, only the diagnostic does not).");
                    return;
                }
                method.setAccessible(true);
                clientAssetsManagerInstance = instance;
                getBedrockModelPOJOMethod = method;
            } catch (ReflectiveOperationException | RuntimeException e) {
                MCPSkins.LOGGER.warn("[MCPSkins] Could not access ClientAssetsManager for the geo-model "
                        + "recognition pre-check (feature itself still works, only the diagnostic does not).", e);
            }
        }
    }

    private static boolean ensureSupported(GunDisplayInstance sample) {
        if (supportState != -1) return supportState == 1;
        synchronized (GunModelPatcher.class) {
            if (supportState != -1) return supportState == 1;
            boolean ok;
            try {
                ok = discover(sample);
            } catch (ReflectiveOperationException | RuntimeException e) {
                MCPSkins.LOGGER.error("[MCPSkins] Failed to discover GunDisplayInstance/GunDisplay internals for geo-model replacement.", e);
                ok = false;
            }
            supportState = ok ? 1 : 0;
            if (!ok) warnUnsupportedOnce();
            return ok;
        }
    }

    private static boolean discover(GunDisplayInstance sample) throws ReflectiveOperationException {
        // 1) The single (ResourceLocation, GunDisplay) constructor
        Constructor<?> found = null;
        for (Constructor<?> ctor : GunDisplayInstance.class.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0] == ResourceLocation.class) {
                found = ctor;
                break;
            }
        }
        if (found == null) return false;
        found.setAccessible(true);
        Class<?> displayClass = found.getParameterTypes()[1];

        // 2) The field on GunDisplayInstance holding this config, found by field type
        Field foundConfigField = null;
        for (Field field : GunDisplayInstance.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() == displayClass) {
                field.setAccessible(true);
                foundConfigField = field;
                break;
            }
        }
        if (foundConfigField == null) return false;

        Object sampleConfig = foundConfigField.get(sample);
        if (sampleConfig == null) return false;

        // 3) getModelLocation() - looked up as a declared method so accessibility doesn't matter
        Method getModelLocation = findMethod(displayClass, "getModelLocation");
        if (getModelLocation == null) return false;
        getModelLocation.setAccessible(true);
        Object currentModelLocation = getModelLocation.invoke(sampleConfig);
        if (!(currentModelLocation instanceof ResourceLocation)) return false;

        // Diagnostic: log the real model location on a known-good weapon once per session,
        // useful for comparing against the path SkinAssetResolver builds for skins
        ResourceLocation realModelLocation = (ResourceLocation) currentModelLocation;
        MCPSkins.LOGGER.info(
                "[MCPSkins][diag] Base weapon's real model ResourceLocation: '{}' "
                        + "(namespace='{}', path='{}').",
                realModelLocation, realModelLocation.getNamespace(), realModelLocation.getPath());

        // 4) The model field on GunDisplay, found by value (equals, not ==, in case the
        //    getter returns a rebuilt copy rather than the field itself)
        Field foundModelField = null;
        for (Field field : displayClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != ResourceLocation.class) continue;
            field.setAccessible(true);
            Object value = field.get(sampleConfig);
            if (Objects.equals(value, currentModelLocation)) {
                foundModelField = field;
                break;
            }
        }
        if (foundModelField == null) return false;

        displayInstanceConstructor = found;
        gunDisplayClass = displayClass;
        configField = foundConfigField;
        modelLocationField = foundModelField;
        return true;
    }

    private static GunDisplayInstance createInstance(GunDisplayInstance base, ResourceLocation modelOverride) {
        try {
            Object baseConfig = configField.get(base);
            Object configCopy = shallowCopy(baseConfig, gunDisplayClass);
            if (configCopy == null) return null;
            modelLocationField.set(configCopy, modelOverride);

            ResourceLocation identity = syntheticIdentity(modelOverride);
            Object instance = displayInstanceConstructor.newInstance(identity, configCopy);
            GunDisplayInstance created = (GunDisplayInstance) instance;

            // Fixes a first-frame "small/offset weapon" glitch: the constructor above loads
            // geometry/animation lazily, so `created` isn't ready yet (modelLoaded=false) when
            // it's returned. Left alone, the mixin would return this not-yet-loaded instance on
            // the next getGunDisplay call, then a second, differently-loaded copy on the call
            // after that - two unplanned identity changes that TACZ's animation system reads as
            // "weapon swapped", retriggering the equip pose. That's usually invisible in normal
            // gameplay (a couple of ticks), but the refit screen doesn't seem to tick weapon
            // animation, so the pose can visibly get stuck. Forcing the load here, synchronously,
            // right after construction and before this instance is ever seen by the mixin, avoids
            // it entirely - and unlike forcing the load on the shared `base` singleton (see
            // GunDisplayInstancePatcher's lazy-loading note), this only touches our own one-off
            // `created` instance, and only after getOrCreate() already confirmed the model is
            // recognized and ready. getAnimationStateMachine() loads the model as a side effect,
            // so this single call is enough to mark loading complete.
            created.getAnimationStateMachine();

            return created;
        } catch (Throwable t) {
            // Covers checkAnimation() throwing when the skin's geo-model skeleton doesn't match
            // the base weapon's animations - degrade to the base geometry rather than crash
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Failed to build geo-model '{}' - the skin's geo-model skeleton "
                            + "likely doesn't match the base weapon's animations (bone names must "
                            + "match). Skin will keep the weapon's base geometry.",
                    modelOverride, t);
            return null;
        }
    }

    private static Object shallowCopy(Object instance, Class<?> type) {
        try {
            Unsafe unsafe = getUnsafe();
            Object copy = unsafe.allocateInstance(type);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(instance));
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Unsafe getUnsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    /**
     * A synthetic identity for the constructed {@link GunDisplayInstance}, distinct from
     * any real registered GunId, used only for this instance's own internal
     * identification/logging.
     */
    private static ResourceLocation syntheticIdentity(ResourceLocation modelOverride) {
        String path = "geo_skin/" + modelOverride.getNamespace() + "/" + modelOverride.getPath();
        ResourceLocation built = ResourceLocation.tryBuild(MCPSkins.MOD_ID, path);
        return built != null ? built : modelOverride;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        return findMethod(clazz, name, new Class<?>[0]);
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void warnUnsupportedOnce() {
        if (unsupportedWarningLogged) return;
        unsupportedWarningLogged = true;
        MCPSkins.LOGGER.warn(
                "[MCPSkins] Full geo-model skin replacement disabled: could not reflectively "
                        + "locate the required GunDisplayInstance/GunDisplay internals for this "
                        + "TACZ version. Texture and icon re-skinning still work normally - only "
                        + "full geometry replacement for skins with a '_geo.json' is affected.");
    }
}