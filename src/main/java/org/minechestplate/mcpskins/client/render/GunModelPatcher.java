package org.minechestplate.mcpskins.client.render;

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
 * Builds a full geometry replacement for a skin (main model and, independently, TACZ's
 * separate LOD model/texture), when a matching geo-model file is found by
 * {@link SkinAssetResolver#resolveModel}.
 * <p>
 * A weapon's geometry isn't a simple field once loaded, it's a parsed
 * {@code BedrockGunModel} built by TACZ's own asset pipeline. Rather than reimplementing
 * that, this class copies the base weapon's config ({@code GunDisplay}, the parsed
 * display.json) with only its model/LOD fields swapped, then calls
 * {@code GunDisplayInstance}'s real {@code (ResourceLocation, GunDisplay)} constructor
 * again, so TACZ's own code does the parsing.
 * <p>
 * Nothing here is hardcoded by field name beyond one verified method
 * ({@code getModelLocation()}); everything else is discovered by reflection in
 * {@link #discover} (constructor signature, field type, or matching a field's value
 * against a known-good getter result). LOD support is discovered separately in
 * {@link #discoverLod} and fails independently: if it can't be found, main-model/texture/
 * icon/HUD skinning is unaffected, only LOD overrides are ignored.
 * <p>
 * The one hard requirement is that the skin's geo-model skeleton (bone names) matches
 * what the base weapon's animations expect; a mismatch is caught by
 * {@code GunDisplayInstance}'s own validation and handled in {@link #createInstance},
 * degrading to the base geometry rather than crashing.
 */
public final class GunModelPatcher {

    private record CacheEntry(GunDisplayInstance base, ResourceLocation modelOverride,
                              ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride,
                              GunDisplayInstance result) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // -1 = not checked, 0 = discovery failed, 1 = discovery succeeded. Checked once per session.
    private static volatile int supportState = -1;
    private static volatile boolean unsupportedWarningLogged = false;

    private static volatile Constructor<?> displayInstanceConstructor;
    private static volatile Class<?> gunDisplayClass;
    private static volatile Field configField;        // GunDisplayInstance -> GunDisplay
    private static volatile Field modelLocationField; // GunDisplay -> ResourceLocation (model)

    // Discovered independently of the above; a fork missing these still gets full
    // main-model/texture/icon/HUD re-skinning.
    private static volatile boolean lodSupported = false;
    private static volatile boolean lodUnsupportedWarningLogged = false;
    private static volatile Field lodConfigField; // GunDisplay -> GunLod (null on weapons with no "lod" block)
    private static volatile Class<?> lodClass;
    private static volatile Field lodModelField;   // GunLod -> ResourceLocation (model)
    private static volatile Field lodTextureField; // GunLod -> ResourceLocation (texture)

    // Pre-flight check via ClientAssetsManager.INSTANCE.getBedrockModelPOJO: a file
    // existing in a resource pack doesn't guarantee TACZ's model registry picked it up.
    // If this probe can't be set up, isModelRecognized() just returns true.
    private static volatile Object clientAssetsManagerInstance;
    private static volatile Method getBedrockModelPOJOMethod;
    private static volatile boolean assetsManagerProbeDone = false;
    private static final Set<String> WARNED_UNRECOGNIZED = ConcurrentHashMap.newKeySet();

    private GunModelPatcher() {
    }

    /**
     * @param cacheKey           stable key for the (weapon, skin) pair
     * @param base                the base weapon's unskinned instance, used as the config template
     * @param modelOverride       skin's main geo-model, or {@code null} to leave it untouched
     * @param lodModelOverride    skin's LOD geo-model, or {@code null}
     * @param lodTextureOverride  skin's LOD texture, or {@code null}
     * @return a new instance with the requested overrides, or {@code null} if nothing was
     *         applied (unsupported fork, unrecognized model, or all overrides filtered out)
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance base, ResourceLocation modelOverride,
                                                 ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride) {
        if (base == null) return null;
        if (modelOverride == null && lodModelOverride == null && lodTextureOverride == null) return null;
        if (!ensureSupported(base)) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null && existing.base() == base
                && Objects.equals(existing.modelOverride(), modelOverride)
                && Objects.equals(existing.lodModelOverride(), lodModelOverride)
                && Objects.equals(existing.lodTextureOverride(), lodTextureOverride)) {
            return existing.result();
        }

        if (modelOverride != null && !isModelRecognized(modelOverride)) {
            warnUnrecognized(cacheKey, "model", modelOverride);
            modelOverride = null;
        }
        if (lodModelOverride != null && !isModelRecognized(lodModelOverride)) {
            warnUnrecognized(cacheKey, "LOD model", lodModelOverride);
            lodModelOverride = null;
        }
        if (modelOverride == null && lodModelOverride == null && lodTextureOverride == null) {
            CACHE.put(cacheKey, new CacheEntry(base, null, null, null, null));
            return null;
        }

        GunDisplayInstance created = createInstance(base, modelOverride, lodModelOverride, lodTextureOverride);
        CACHE.put(cacheKey, new CacheEntry(base, modelOverride, lodModelOverride, lodTextureOverride, created));
        if (created != null) {
            MCPSkins.LOGGER.info("[MCPSkins] Built geo-model skin for '{}' (model: {}, lodModel: {}, lodTexture: {}).",
                    cacheKey, modelOverride, lodModelOverride, lodTextureOverride);
        }
        return created;
    }

    private static void warnUnrecognized(String cacheKey, String kind, ResourceLocation location) {
        if (!WARNED_UNRECOGNIZED.add(cacheKey + "|" + kind)) return;
        MCPSkins.LOGGER.warn(
                "[MCPSkins] Geo-model file '{}' ({}) exists in a resource pack, but TACZ's internal "
                        + "model registry doesn't recognize it - likely a folder/naming mismatch. "
                        + "See the '[MCPSkins][diag]' log line for the real model path on a "
                        + "working weapon, for comparison. Skin keeps the base {} geometry.",
                location, kind, kind);
    }

    /** Clears the cache of built geo-instances. Called on client resource reload. */
    public static void clear() {
        CACHE.clear();
        WARNED_UNRECOGNIZED.clear();
    }

    /**
     * Returns the base weapon's real model location, read directly off its config rather
     * than guessed, since different gunpacks use different namespaces/folders.
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
     * Returns the base weapon's real LOD geo-model location, or {@code null} if LOD isn't
     * supported on this fork, or this particular weapon has no {@code "lod"} block at all.
     */
    public static ResourceLocation getBaseLodModelLocation(GunDisplayInstance base) {
        return readLocation(baseLodConfig(base), lodModelField);
    }

    /** See {@link #getBaseLodModelLocation}. */
    public static ResourceLocation getBaseLodTexture(GunDisplayInstance base) {
        return readLocation(baseLodConfig(base), lodTextureField);
    }

    private static Object baseLodConfig(GunDisplayInstance base) {
        if (base == null || !ensureSupported(base) || !lodSupported) return null;
        try {
            Object config = configField.get(base);
            return config != null ? lodConfigField.get(config) : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static ResourceLocation readLocation(Object owner, Field field) {
        if (owner == null) return null;
        try {
            Object value = field.get(owner);
            return value instanceof ResourceLocation ? (ResourceLocation) value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean isModelRecognized(ResourceLocation location) {
        probeAssetsManagerOnce();
        if (getBedrockModelPOJOMethod == null || clientAssetsManagerInstance == null) return true;
        try {
            Object result = getBedrockModelPOJOMethod.invoke(clientAssetsManagerInstance, location);
            return result != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
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

        // 3) getModelLocation(), the one verified method name this class relies on
        Method getModelLocation = findMethod(displayClass, "getModelLocation");
        if (getModelLocation == null) return false;
        getModelLocation.setAccessible(true);
        Object currentModelLocation = getModelLocation.invoke(sampleConfig);
        if (!(currentModelLocation instanceof ResourceLocation)) return false;

        // Diagnostic line: real model location on a known-good weapon, for comparing
        // against the path SkinAssetResolver builds for a skin
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

        // 5) LOD support, independent of everything above - a failure here doesn't affect it
        try {
            lodSupported = discoverLod(displayClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            lodSupported = false;
        }
        if (!lodSupported) warnLodUnsupportedOnce();

        return true;
    }

    /**
     * Locates GunDisplay's LOD config field by <i>type</i>, not value, since {@code sample}
     * may not itself define a {@code "lod"} block (its declared field type is inspectable
     * either way). The two sub-fields are both {@code ResourceLocation}, so they're told
     * apart by writing a sentinel into each and checking which getter reflects it back.
     */
    private static boolean discoverLod(Class<?> displayClass) throws ReflectiveOperationException {
        Field foundLodField = null;
        Class<?> foundLodClass = null;
        Method modelGetter = null;
        Method textureGetter = null;
        for (Field field : displayClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type == ResourceLocation.class || type.isPrimitive() || type == displayClass) continue;
            Method m = findMethod(type, "getModelLocation");
            Method t = findMethod(type, "getModelTexture");
            if (m == null || t == null) continue;
            if (m.getReturnType() != ResourceLocation.class || t.getReturnType() != ResourceLocation.class) continue;
            field.setAccessible(true);
            m.setAccessible(true);
            t.setAccessible(true);
            foundLodField = field;
            foundLodClass = type;
            modelGetter = m;
            textureGetter = t;
            break;
        }
        if (foundLodField == null) return false;

        Field foundModelField = null;
        Field foundTextureField = null;
        Object probe = getUnsafe().allocateInstance(foundLodClass);
        ResourceLocation sentinel = ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "lod_discovery_probe");
        for (Field field : foundLodClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != ResourceLocation.class) continue;
            field.setAccessible(true);
            field.set(probe, sentinel);
            if (sentinel.equals(modelGetter.invoke(probe))) {
                foundModelField = field;
            } else if (sentinel.equals(textureGetter.invoke(probe))) {
                foundTextureField = field;
            }
            field.set(probe, null);
        }
        if (foundModelField == null || foundTextureField == null) return false;

        lodConfigField = foundLodField;
        lodClass = foundLodClass;
        lodModelField = foundModelField;
        lodTextureField = foundTextureField;
        return true;
    }

    private static GunDisplayInstance createInstance(GunDisplayInstance base, ResourceLocation modelOverride,
                                                     ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride) {
        try {
            Object baseConfig = configField.get(base);
            Object configCopy = shallowCopy(baseConfig, gunDisplayClass);
            if (configCopy == null) return null;

            if (modelOverride != null) {
                modelLocationField.set(configCopy, modelOverride);
            }
            if (lodSupported && (lodModelOverride != null || lodTextureOverride != null)) {
                // LOD is a separate nested object - copy it too rather than mutating in
                // place, or the change would leak into the base weapon's own GunDisplay
                Object baseLod = lodConfigField.get(configCopy);
                if (baseLod != null) {
                    Object lodCopy = shallowCopy(baseLod, lodClass);
                    if (lodCopy != null) {
                        if (lodModelOverride != null) lodModelField.set(lodCopy, lodModelOverride);
                        if (lodTextureOverride != null) lodTextureField.set(lodCopy, lodTextureOverride);
                        lodConfigField.set(configCopy, lodCopy);
                    }
                }
                // else: this weapon has no "lod" block, nothing to skin
            }

            ResourceLocation identitySource = modelOverride != null ? modelOverride
                    : lodModelOverride != null ? lodModelOverride : lodTextureOverride;
            ResourceLocation identity = syntheticIdentity(identitySource);
            Object instance = displayInstanceConstructor.newInstance(identity, configCopy);
            GunDisplayInstance created = (GunDisplayInstance) instance;

            // Forces the lazy load now, synchronously, so this instance is fully ready
            // before the mixin ever sees it - otherwise it'd be returned not-yet-loaded,
            // then a second differently-loaded copy on the next call, which TACZ's
            // animation system reads as the weapon changing and retriggers the equip pose
            // (most visible on the refit screen, which doesn't tick weapon animation).
            // LOD stays a normal lazy field, it's only read at distance/third-person.
            created.getAnimationStateMachine();

            return created;
        } catch (Throwable t) {
            // Covers checkAnimation() throwing on a skeleton mismatch between the skin's
            // geo-model and the base weapon's animations - degrade rather than crash
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Failed to build geo-model (model: {}, lodModel: {}, lodTexture: {}) - "
                            + "the skin's geo-model skeleton likely doesn't match the base weapon's "
                            + "animations (bone names must match). Skin will keep the weapon's base geometry.",
                    modelOverride, lodModelOverride, lodTextureOverride, t);
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

    /** A synthetic identity for the constructed instance, distinct from any real GunId. */
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

    private static void warnLodUnsupportedOnce() {
        if (lodUnsupportedWarningLogged) return;
        lodUnsupportedWarningLogged = true;
        MCPSkins.LOGGER.warn(
                "[MCPSkins] LOD geo-model/texture skin replacement disabled: could not "
                        + "reflectively locate the required GunLod internals for this TACZ "
                        + "version. Main-model, texture, icon, and HUD re-skinning are "
                        + "unaffected - only the distant/third-person low-poly model is affected.");
    }
}
