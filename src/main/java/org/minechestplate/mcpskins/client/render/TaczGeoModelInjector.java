package org.minechestplate.mcpskins.client.render;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects a skin's geo-model JSON straight into TACZ's {@code ClientAssetsManager} via
 * reflection - the same {@code bedrockModel}/{@code dataMap} a normal resource-pack reload
 * populates, so {@link GunModelPatcher} picks up injected models with no changes on its end.
 * <p>
 * Must parse with TACZ's own Gson instance, not a fresh one - geo-model JSON relies on a
 * custom TypeAdapter for cube UV data that a plain Gson would mangle.
 * <p>
 * Every discovery step fails soft (log once, return false): a missing geo skin just falls
 * back to the base weapon shape instead of crashing the client.
 */
public final class TaczGeoModelInjector {

    private static volatile int supportState = -1; // -1 unchecked, 0 failed, 1 ok
    private static volatile boolean warningLogged = false;

    private static volatile Object clientAssetsManagerInstance;
    private static volatile Gson taczGson;
    private static volatile Class<?> bedrockModelPojoClass;
    private static volatile Map<ResourceLocation, Object> dataMap;
    private static volatile Set<ResourceLocation> failedDataSet; // best-effort, may stay null

    private static final Set<String> WARNED_PARSE_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * Keys we added, so {@link #reset()} removes exactly those and {@link #inject} knows what
     * it owns. Without it a model from one server survives into the next session and shadows
     * its replacement, since {@code putIfAbsent} would silently keep the stale entry.
     */
    private static final Set<ResourceLocation> INJECTED = ConcurrentHashMap.newKeySet();

    private TaczGeoModelInjector() {
    }

    /**
     * Parses jsonBytes as a BedrockModelPOJO and makes it available under collapsedIdentity.
     * Overwrites our own previous entry; leaves a pack-provided one alone.
     *
     * @return true if a model is available under collapsedIdentity
     */
    public static boolean inject(ResourceLocation collapsedIdentity, byte[] jsonBytes) {
        if (!ensureSupported()) return false;
        try {
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            Object parsed = taczGson.fromJson(json, bedrockModelPojoClass);
            if (parsed == null) return false;

            if (INJECTED.contains(collapsedIdentity)) {
                dataMap.put(collapsedIdentity, parsed);
            } else if (dataMap.putIfAbsent(collapsedIdentity, parsed) == null) {
                INJECTED.add(collapsedIdentity);
            }
            if (failedDataSet != null) {
                failedDataSet.remove(collapsedIdentity);
            }
            return true;
        } catch (RuntimeException e) {
            if (WARNED_PARSE_FAILURES.add(collapsedIdentity.toString())) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to parse/inject network-delivered geo-model '{}'", collapsedIdentity, e);
            }
            return false;
        }
    }

    private static boolean ensureSupported() {
        if (supportState != -1) return supportState == 1;
        synchronized (TaczGeoModelInjector.class) {
            if (supportState != -1) return supportState == 1;
            boolean ok = discover();
            supportState = ok ? 1 : 0;
            if (!ok) warnOnce();
            return ok;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean discover() {
        try {
            Class<?> camClass = Class.forName("com.tacz.guns.client.resource.ClientAssetsManager");

            Object instance = null;
            for (Field field : camClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == camClass) {
                    field.setAccessible(true);
                    instance = field.get(null);
                    break;
                }
            }
            if (instance == null) return false;

            Gson gson = null;
            for (Field field : camClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == Gson.class) {
                    field.setAccessible(true);
                    gson = (Gson) field.get(null);
                    break;
                }
            }
            if (gson == null) return false;

            Class<?> lazyManagerClass = Class.forName("com.tacz.guns.resource.manager.LazyJsonDataManager");
            Class<?> pojoClass = Class.forName("com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO");

            Field bedrockModelField = findLazyManagerField(camClass, lazyManagerClass, pojoClass);
            if (bedrockModelField == null) return false;
            bedrockModelField.setAccessible(true);
            Object bedrockModelManager = bedrockModelField.get(instance);
            if (bedrockModelManager == null) return false;

            Field dataMapField = findDeclaredField(lazyManagerClass, "dataMap");
            if (dataMapField == null) return false;
            dataMapField.setAccessible(true);
            Map<ResourceLocation, Object> map = (Map<ResourceLocation, Object>) dataMapField.get(bedrockModelManager);
            if (map == null) return false;

            Set<ResourceLocation> failed = null;
            Field failedField = findDeclaredField(lazyManagerClass, "failedData");
            if (failedField != null) {
                failedField.setAccessible(true);
                Object value = failedField.get(bedrockModelManager);
                if (value instanceof Set<?>) {
                    failed = (Set<ResourceLocation>) value;
                }
            }

            clientAssetsManagerInstance = instance;
            taczGson = gson;
            bedrockModelPojoClass = pojoClass;
            dataMap = map;
            failedDataSet = failed;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MCPSkins.LOGGER.error("[MCPSkins] Failed to set up network geo-model injection into ClientAssetsManager.", e);
            return false;
        }
    }

    /** Tries the "bedrockModel" field by name first; falls back to scanning by generic
     *  type in case a future TACZ fork renames it. */
    private static Field findLazyManagerField(Class<?> owner, Class<?> lazyManagerClass, Class<?> pojoClass)
            throws ReflectiveOperationException {
        try {
            Field byName = owner.getDeclaredField("bedrockModel");
            if (byName.getType() == lazyManagerClass) return byName;
        } catch (NoSuchFieldException ignored) {
            // fall through to the generic-type scan below
        }
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() != lazyManagerClass) continue;
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length == 1
                    && parameterized.getActualTypeArguments()[0] == pojoClass) {
                return field;
            }
        }
        return null;
    }

    private static Field findDeclaredField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void warnOnce() {
        if (warningLogged) return;
        warningLogged = true;
        MCPSkins.LOGGER.warn(
                "[MCPSkins] Could not hook into ClientAssetsManager for network-delivered geo-models - "
                        + "skins with a geometry override ('_geo.json') will keep the base weapon's shape. "
                        + "Texture/icon/HUD re-skinning is unaffected.");
    }

    /**
     * Removes our injected entries and drops every cached handle, forcing rediscovery on the
     * next {@link #inject}. Called on resource reload and on disconnect.
     * <p>
     * {@link #dataMap} is a live reference to a map TACZ owns. If TACZ ever replaces it rather
     * than clearing it, injections would land in an orphaned map forever, since
     * {@code supportState == 1} blocks rediscovery.
     */
    public static void reset() {
        synchronized (TaczGeoModelInjector.class) {
            Map<ResourceLocation, Object> map = dataMap;
            if (map != null) {
                for (ResourceLocation key : INJECTED) {
                    map.remove(key);
                }
            }
            INJECTED.clear();

            supportState = -1;
            clientAssetsManagerInstance = null;
            taczGson = null;
            bedrockModelPojoClass = null;
            dataMap = null;
            failedDataSet = null;
            WARNED_PARSE_FAILURES.clear();
        }
    }
}