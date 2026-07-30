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
 * <p>
 * <b>Why a freshly built instance needs its animation state machine primed:</b> every
 * {@code GunDisplayInstance} owns its own {@code LuaAnimationStateMachine}, built new by
 * the constructor and never initialized on its own - {@code initialize()} is only ever
 * called by TACZ's {@code AnimateGeoItemRenderer.tryInit}. Normally that happens
 * automatically the next time the currently-held weapon is ticked, but TACZ's own
 * auto-recovery (in {@code TickAnimationEvent}) explicitly skips first-person, which is
 * exactly where a held weapon is rendered. Until something initializes it, the state
 * machine has no current pose to write to the model, so the gun renders at its raw bind
 * pose - the same "detached hands" look this fork already has on an F3+T resource reload
 * (which likewise rebuilds every {@code GunDisplayInstance} from scratch). Swapping to a
 * skin with its own geo-model hits the same gap: {@link #createInstance} builds a brand
 * new instance, so its state machine starts uninitialized too. {@link #primeAnimation}
 * closes that gap immediately after the instance is cached, by driving the same
 * init/draw sequence TACZ's own renderer would eventually run, so nothing ever renders
 * the uninitialized state. Plain texture-only skins never hit this: they reuse the base
 * weapon's already-initialized instance via {@link GunDisplayInstancePatcher}'s shallow
 * copy, which carries the same (already-primed) state machine reference over.
 */
public final class GunModelPatcher {

    /**
     * A build result keyed by the overrides <em>as they were requested</em>.
     *
     * @param modelOverride      the requested model override, before any filtering - see the
     *                           note in {@link #getOrCreate} on why the unfiltered values are
     *                           what get stored
     * @param generation         the {@link ClientSkinAssetCache#generation()} this was built at
     * @param result             the patched instance, or null if this combination can't be built
     */
    private record CacheEntry(ResourceLocation modelOverride,
                              ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride,
                              int generation, GunDisplayInstance result) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // cacheKeys currently inside createInstance()+primeAnimation() on this thread. Both
    // TACZ's own per-frame context refresh (GunAnimationStateContext#setCurrentGunItem)
    // and primeAnimation()'s own tryInit() call re-enter TimelessAPI.getGunDisplay(stack)
    // - i.e. this exact method, for this exact cacheKey - WHILE we're still inside the
    // first build. Without this guard that reentrant call used to re-check identity
    // (see the removed CacheEntry.base()), see it not match, and race a second,
    // independent createInstance()+primeAnimation() for the same logical (weapon, skin):
    // two GunDisplayInstances, two LuaAnimationStateMachines, and whichever one a given
    // caller happens to hold a reference to next initializes/exits out of sync with the
    // one actually cached - that's what threw "State machine is already initialized,
    // call exit() first" on login, and is also the most likely reason shots went missing
    // for a while right after equipping a geo-skinned weapon (the fire trigger and the
    // ammo/cooldown bookkeeping ending up on two different orphaned instances). A
    // reentrant call for a key already mid-build just gets whatever's cached so far.
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
     *                            the next time this key actually needs to be (re)built - NOT used
     *                            to decide whether a rebuild is needed, since TACZ's own object
     *                            lifetime for its base instance can't be relied on (see
     *                            {@link PatchedGunDisplayCache} for the same caveat) - only the
     *                            override values below and an explicit {@link #clear()} do that
     * @param stack               the item stack this lookup is for, used only to prime the
     *                            new instance's animation state machine the first time it's
     *                            built (see {@link #primeAnimation}); never {@code null} in
     *                            practice, but if it were, priming is skipped rather than NPEing
     * @param modelOverride       skin's main geo-model, or {@code null} to leave it untouched
     * @param lodModelOverride    skin's LOD geo-model, or {@code null}
     * @param lodTextureOverride  skin's LOD texture, or {@code null}
     * @return a new instance with the requested overrides, or {@code null} if nothing was
     *         applied (unsupported fork, unrecognized model, or all overrides filtered out)
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance base, ItemStack stack, ResourceLocation modelOverride,
                                                 ResourceLocation lodModelOverride, ResourceLocation lodTextureOverride) {
        if (base == null) return null;
        if (modelOverride == null && lodModelOverride == null && lodTextureOverride == null) return null;
        if (!ensureSupported(base)) return null;

        // Held so the cache entry can be keyed on what was ASKED for, not on what survives
        // the isModelRecognized() filtering below.
        ResourceLocation requestedModel = modelOverride;
        ResourceLocation requestedLodModel = lodModelOverride;

        int generation = ClientSkinAssetCache.generation();
        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.generation() == generation
                && Objects.equals(existing.modelOverride(), requestedModel)
                && Objects.equals(existing.lodModelOverride(), requestedLodModel)
                && Objects.equals(existing.lodTextureOverride(), lodTextureOverride)) {
            return existing.result();
        }

        // Reentrant call for a key we're already building further down this same call
        // stack (see the BUILDING field javadoc) - hand back whatever's cached so far
        // rather than racing a second, independent build for the same (weapon, skin).
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
            // Keyed on the REQUESTED overrides, not the filtered-to-null ones. Storing the
            // nulls made this entry unmatchable by the hit check above, which compares
            // against what the caller passed in - so an unrecognized model fell through to
            // isModelRecognized() (a reflective call into TACZ internals) on every single
            // render call, forever, instead of being answered from cache.
            CACHE.put(cacheKey, new CacheEntry(requestedModel, requestedLodModel, lodTextureOverride, generation, null));
            return null;
        }

        building.add(cacheKey);
        try {
            GunDisplayInstance created = createInstance(base, modelOverride, lodModelOverride, lodTextureOverride);
            // Cache first, priming second: priming below re-enters TimelessAPI.getGunDisplay(stack)
            // (see primeAnimation's javadoc), which comes straight back through this same method.
            // With the entry already cached AND this cacheKey marked as building, that reentrant
            // call resolves to a cheap cache hit instead of racing a second build.
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

    // Reentrancy guard for primeAnimation - belt-and-suspenders alongside the cache-first
    // ordering above, in case a future TACZ change makes the reentrant getGunDisplay() call
    // reachable before this entry is visible.
    private static final ThreadLocal<Boolean> PRIMING = ThreadLocal.withInitial(() -> false);
    private static volatile boolean primeWarningLogged = false;

    /**
     * Drives the same init/draw sequence TACZ's own {@code AnimateGeoItemRenderer} uses to
     * bring a freshly-built instance's animation state machine out of its uninitialized
     * "bind pose" state - see the class javadoc for why this is needed at all. A no-op once
     * the state machine is initialized, so this only ever does real work the first time a
     * given (weapon, skin) geo-model pair is built.
     * <p>
     * Calling {@code GunItemRendererWrapper.tryInit} means building a
     * {@code GunAnimationStateContext}, whose {@code setCurrentGunItem} calls
     * {@code TimelessAPI.getGunDisplay(stack)} itself - reentering the very mixin that
     * called {@link #getOrCreate} in the first place. That's intentional: it's the only
     * public way to get a context wired up the way TACZ's own scripts expect, and it's safe
     * here specifically because the cache entry is already in place by the time this runs
     * (see the comment in {@link #getOrCreate}), so the reentrant call resolves to a cache
     * hit rather than looping.
     */
    private static void primeAnimation(GunDisplayInstance created, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (Boolean.TRUE.equals(PRIMING.get())) return;

        LuaAnimationStateMachine<?> stateMachine;
        try {
            stateMachine = created.getAnimationStateMachine();
        } catch (RuntimeException e) {
            return; // already logged/handled by the eager load in createInstance
        }
        if (stateMachine == null || stateMachine.isInitialized()) return;

        GunItemRendererWrapper renderer = GunItemRendererWrapper.INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (renderer == null || player == null) return;

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
            // Fail open: if the probe itself breaks we can't tell recognized from
            // unrecognized, and refusing every model would disable geo skins wholesale.
            // Letting the build attempt proceed is the safer wrong answer - createInstance
            // catches and reports its own failure with real context.
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
            rethrowIfFatal(t);
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

    /**
     * Rethrows the throwables that must not be swallowed.
     * <p>
     * The build path catches {@link Throwable} on purpose - it drives a reflective call into
     * another mod's constructor, and the realistic failure (a skin skeleton whose bone names
     * don't match the base weapon's animations) can surface as almost anything. But an
     * {@link OutOfMemoryError} or {@link StackOverflowError} is not a "this skin didn't work"
     * signal, and turning one into a cached null just hides a dying JVM behind a weapon that
     * quietly keeps its base geometry.
     */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof LinkageError || t instanceof ThreadDeath) {
            throw (Error) t;
        }
    }

    private static Object shallowCopy(Object instance, Class<?> type) {
        try {
            Unsafe unsafe = getUnsafe();
            Object copy = unsafe.allocateInstance(type);
            // Superclass fields included - allocateInstance leaves them at their defaults,
            // and TACZ's display config types are more likely to use inheritance than
            // GunDisplayInstance itself is.
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
            // Caller (createInstance) reports the failure with the override paths in hand,
            // which is far more useful context than anything available here.
            MCPSkins.LOGGER.debug("[MCPSkins] Unsafe shallow copy of {} failed.", type.getName(), e);
            return null;
        }
    }

    /** Resolved once - this sits on a path that runs per weapon per frame. */
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