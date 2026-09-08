package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a full geometry replacement for a skin, main model plus TACZ's separate LOD.
 * <p>
 * Geometry is a parsed {@code BedrockGunModel} rather than a swappable field, so this copies the
 * base weapon's {@code GunDisplay} config with the model fields replaced and re-runs
 * {@code GunDisplayInstance}'s constructor. The new instance owns an uninitialized state machine,
 * so a held weapon needs {@link #primeAnimation} or it renders at its bind pose.
 */
public final class GunModelPatcher {

    /**
     * Keyed on the overrides as requested, before {@link #getOrCreate}'s filtering.
     * {@code generation} only retries negative entries: a model TACZ has not registered yet
     * leaves the overrides identical.
     */
    private record CacheEntry(ResourceLocation modelOverride,
                              ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride,
                              int generation, GunDisplayInstance result) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // cacheKeys mid-build on this thread. TACZ's context refresh and primeAnimation's tryInit
    // both re-enter getGunDisplay for a key still building; unguarded that races a second
    // instance with its own state machine ("State machine is already initialized" on login).
    private static final ThreadLocal<Set<String>> BUILDING = ThreadLocal.withInitial(HashSet::new);

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

    // A file existing in a resource pack doesn't mean TACZ's model registry picked it up.
    private static volatile Object clientAssetsManagerInstance;
    private static volatile Method getBedrockModelPOJOMethod;
    private static volatile boolean assetsManagerProbeDone = false;
    private static final Set<String> WARNED_UNRECOGNIZED = ConcurrentHashMap.newKeySet();

    private GunModelPatcher() {
    }

    /**
     * @param cacheKey stable key for the (weapon, skin) pair
     * @param base     config template for the build. Not part of the hit check - TACZ's base
     *                 instance identity isn't stable (see {@link PatchedGunDisplayCache})
     * @param stack    only used to prime the new instance's animation state machine
     * @return the patched instance, or {@code null} if nothing was applied (unsupported fork,
     *         unrecognized model, or all overrides filtered out)
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance base, ItemStack stack, ResourceLocation modelOverride,
                                                 ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride) {
        if (base == null) return null;
        if (modelOverride == null && lodModelOverride == null && lodTextureOverride == null) return null;
        if (!ensureSupported(base)) return null;

        ResourceLocation requestedModel = modelOverride;
        ResourceLocation requestedLodModel = lodModelOverride;

        int generation = ClientSkinAssetCache.generation();
        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && Objects.equals(existing.modelOverride(), requestedModel)
                && Objects.equals(existing.lodModelOverride(), requestedLodModel)
                && Objects.equals(existing.lodTextureOverride(), lodTextureOverride)
                && (existing.result() != null || existing.generation() == generation)) {
            return existing.result();
        }

        // Reentrant call for a key already building further down this stack - see BUILDING.
        Set<String> building = BUILDING.get();
        if (building.contains(cacheKey)) {
            return existing != null ? existing.result() : null;
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
            CACHE.put(cacheKey, new CacheEntry(requestedModel, requestedLodModel, lodTextureOverride, generation, null));
            return null;
        }

        building.add(cacheKey);
        try {
            GunDisplayInstance created = createInstance(base, modelOverride, lodModelOverride, lodTextureOverride);
            // Cache before priming: priming re-enters this method, and the entry has to be
            // visible for that call to resolve as a hit.
            CACHE.put(cacheKey, new CacheEntry(requestedModel, requestedLodModel, lodTextureOverride, generation, created));
            if (created != null) {
                MCPSkins.LOGGER.info("[MCPSkins] Built geo-model skin for '{}' (model: {}, lodModel: {}, lodTexture: {}).",
                        cacheKey, modelOverride, lodModelOverride, lodTextureOverride);
                primeAnimation(created, stack);
            }
            return created;
        } finally {
            building.remove(cacheKey);
        }
    }

    // Second line of defence behind the cache-first ordering in getOrCreate.
    private static final ThreadLocal<Boolean> PRIMING = ThreadLocal.withInitial(() -> false);
    private static volatile boolean primeWarningLogged = false;

    /**
     * Runs TACZ's init/draw sequence to get a new instance's state machine out of its bind pose.
     * No-op once initialized.
     * <p>
     * Held stack only: {@code tryInit} ends in {@code trigger("draw")}, and that animation carries
     * sound keyframes, so priming the Armory's preview stacks fired a draw sound per skin browsed.
     */
    private static void primeAnimation(GunDisplayInstance created, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (Boolean.TRUE.equals(PRIMING.get())) return;

        GunItemRendererWrapper renderer = GunItemRendererWrapper.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (renderer == null || player == null) return;

        // Identity, not equality - a preview stack can carry the same gun and skin. Missing a
        // real held stack is harmless; FirstPersonAnimationFix re-inits it next frame.
        if (stack != player.getMainHandItem()) return;

        LuaAnimationStateMachine<?> stateMachine;
        try {
            stateMachine = created.getAnimationStateMachine();
        } catch (RuntimeException e) {
            return; // already logged/handled by the eager load in createInstance
        }
        if (stateMachine == null || stateMachine.isInitialized()) return;

        PRIMING.set(true);
        try {
            float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
            renderer.tryInit(stack, player, partialTick);
        } catch (RuntimeException e) {
            if (!primeWarningLogged) {
                primeWarningLogged = true;
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Failed to prime the animation state machine for a geo-model "
                                + "skin - the weapon may briefly show its raw bind pose ('detached "
                                + "hands') the first time this skin is equipped in first person, "
                                + "same as this TACZ fork's known F3+T behavior. Not fatal.", e);
            }
        } finally {
            PRIMING.set(false);
        }
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

    public static void clear() {
        CACHE.clear();
        WARNED_UNRECOGNIZED.clear();
    }

    /** Read off the config rather than guessed: gunpacks use their own namespaces and folders. */
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

    /** Null when the fork has no LOD support, or this weapon has no {@code "lod"} block. */
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
            if (WARNED_UNRECOGNIZED.add("probe-failure")) {
                MCPSkins.LOGGER.warn("[MCPSkins] ClientAssetsManager model probe failed; "
                        + "geo-model overrides will be attempted without pre-validation.", e);
            }
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

    /** {@code getModelLocation()} is the one member this class has to find by name. */
    private static boolean discover(GunDisplayInstance sample) throws ReflectiveOperationException {
        Constructor<?> constructor = findDisplayConstructor();
        if (constructor == null) return false;
        Class<?> displayClass = constructor.getParameterTypes()[1];

        Field foundConfigField = findFieldByType(GunDisplayInstance.class, displayClass);
        if (foundConfigField == null) return false;
        Object sampleConfig = foundConfigField.get(sample);
        if (sampleConfig == null) return false;

        Method getModelLocation = findMethod(displayClass, "getModelLocation");
        if (getModelLocation == null) return false;
        getModelLocation.setAccessible(true);
        if (!(getModelLocation.invoke(sampleConfig) instanceof ResourceLocation modelLocation)) return false;

        Field foundModelField =
                findFieldByValue(displayClass, ResourceLocation.class, sampleConfig, modelLocation);
        if (foundModelField == null) return false;

        displayInstanceConstructor = constructor;
        gunDisplayClass = displayClass;
        configField = foundConfigField;
        modelLocationField = foundModelField;

        // Independent of everything above: no LOD support still leaves full main-model skinning.
        try {
            lodSupported = discoverLod(displayClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            lodSupported = false;
        }
        if (!lodSupported) warnLodUnsupportedOnce();
        return true;
    }

    private static Constructor<?> findDisplayConstructor() {
        for (Constructor<?> ctor : GunDisplayInstance.class.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0] == ResourceLocation.class) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            field.setAccessible(true);
            return field;
        }
        return null;
    }

    /** equals, not ==: the getter may hand back a rebuilt copy rather than the field itself. */
    private static Field findFieldByValue(Class<?> owner, Class<?> type, Object instance, Object value)
            throws ReflectiveOperationException {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            field.setAccessible(true);
            if (Objects.equals(field.get(instance), value)) return field;
        }
        return null;
    }

    /**
     * By type rather than value, since the sample weapon may have no {@code "lod"} block. Its two
     * {@code ResourceLocation} fields are told apart by writing a sentinel into each and seeing
     * which getter reflects it back.
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
                // Nested object - copy rather than mutate, or it leaks into the base weapon.
                Object baseLod = lodConfigField.get(configCopy);
                if (baseLod != null) {
                    Object lodCopy = shallowCopy(baseLod, lodClass);
                    if (lodCopy != null) {
                        if (lodModelOverride != null) lodModelField.set(lodCopy, lodModelOverride);
                        if (lodTextureOverride != null) lodTextureField.set(lodCopy, lodTextureOverride);
                        lodConfigField.set(configCopy, lodCopy);
                    }
                }
                }

            ResourceLocation identitySource = modelOverride != null ? modelOverride
                    : lodModelOverride != null ? lodModelOverride : lodTextureOverride;
            ResourceLocation identity = syntheticIdentity(identitySource);
            Object instance = displayInstanceConstructor.newInstance(identity, configCopy);
            GunDisplayInstance created = (GunDisplayInstance) instance;

            // Force the lazy load now so the mixin never sees a half-loaded instance. TACZ
            // reads that as the weapon changing and retriggers the equip pose - most visible
            // on the refit screen, which doesn't tick weapon animation.
            created.getAnimationStateMachine();

            return created;
        } catch (Throwable t) {
            rethrowIfFatal(t);
            // Usually checkAnimation() rejecting a skeleton mismatch - degrade, don't crash.
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Failed to build geo-model (model: {}, lodModel: {}, lodTexture: {}) - "
                            + "the skin's geo-model skeleton likely doesn't match the base weapon's "
                            + "animations (bone names must match). Skin will keep the weapon's base geometry.",
                    modelOverride, lodModelOverride, lodTextureOverride, t);
            return null;
        }
    }

    /** The build path catches {@link Throwable}; a dying JVM is not something to swallow. */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof LinkageError || t instanceof ThreadDeath) {
            throw (Error) t;
        }
    }

    private static Object shallowCopy(Object instance, Class<?> type) {
        try {
            Unsafe unsafe = getUnsafe();
            Object copy = unsafe.allocateInstance(type);
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    field.set(copy, field.get(instance));
                }
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.debug("[MCPSkins] Unsafe shallow copy of {} failed.", type.getName(), e);
            return null;
        }
    }

    private static volatile Unsafe cachedUnsafe;

    private static Unsafe getUnsafe() throws ReflectiveOperationException {
        Unsafe local = cachedUnsafe;
        if (local != null) return local;
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        local = (Unsafe) f.get(null);
        cachedUnsafe = local;
        return local;
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