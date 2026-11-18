package org.minechestplate.mcpskins.client.render;

import com.google.gson.JsonElement;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.animation.bedrock.BedrockAnimationFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merges a skin's {@code .animation.json} over the gun's own (same names replace, the rest fall
 * through) and registers it under a synthetic id in {@code ClientAssetsManager.bedrockAnimation},
 * for {@link GunModelPatcher} to point at. Merged on first use, per base, since one skin file can
 * sit over several displays. Discovery fails soft like {@link TaczGeoModelInjector}.
 */
public final class SkinAnimationInjector {

    private record MergeKey(ResourceLocation skinFile, ResourceLocation baseAnimation) {
    }

    private record Merged(ResourceLocation id, BedrockAnimationFile file) {
    }

    private static volatile int supportState = -1;
    private static volatile boolean warningLogged = false;

    private static volatile Object animationManager;
    private static volatile Map<ResourceLocation, Object> dataMap;
    private static volatile Set<ResourceLocation> failedDataSet;
    private static volatile Field animationsField; // BedrockAnimationFile -> Map<String, BedrockAnimation>
    private static volatile Field versionField;

    private static final Map<ResourceLocation, BedrockAnimationFile> RECEIVED = new ConcurrentHashMap<>();

    // Sentinel, compared by identity.
    private static final Merged FAILED = new Merged(null, null);
    private static final Map<MergeKey, Merged> MERGED = new ConcurrentHashMap<>();

    // Ids we added, so reset() removes exactly those.
    private static final Set<ResourceLocation> INJECTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SkinAnimationInjector() {
    }

    public static boolean accept(ResourceLocation skinFile, byte[] jsonBytes) {
        if (!ensureSupported()) return false;
        try {
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            // Lenient, like TACZ reading its own pack files.
            JsonElement element = GsonHelper.fromJson(ClientAssetsManager.GSON, new StringReader(json), JsonElement.class, true);
            BedrockAnimationFile parsed = ClientAssetsManager.GSON.fromJson(element, BedrockAnimationFile.class);
            if (parsed == null || parsed.getAnimations() == null || parsed.getAnimations().isEmpty()) {
                warnOnce("empty|" + skinFile, "[MCPSkins] Skin animation file '{}' has no animations; ignoring it.", skinFile);
                return false;
            }
            RECEIVED.put(skinFile, parsed);
            MERGED.keySet().removeIf(key -> key.skinFile().equals(skinFile));
            return true;
        } catch (RuntimeException e) {
            if (WARNED.add("parse|" + skinFile)) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to parse network-delivered skin animation '{}'", skinFile, e);
            }
            return false;
        }
    }

    // Main thread only.
    public static ResourceLocation merged(ResourceLocation skinFile, ResourceLocation baseAnimation) {
        if (skinFile == null) return null;
        BedrockAnimationFile skin = RECEIVED.get(skinFile);
        if (skin == null || !ensureSupported()) return null;

        MergeKey key = new MergeKey(skinFile, baseAnimation);
        Merged merged = MERGED.computeIfAbsent(key, k -> build(k, skin));
        if (merged == FAILED) return null;

        // TACZ clears its maps on its own reloads; re-register if ours went with them.
        if (dataMap.get(merged.id()) != merged.file()) {
            register(merged);
        }
        return merged.id();
    }

    private static Merged build(MergeKey key, BedrockAnimationFile skin) {
        ResourceLocation id = syntheticId(key);
        if (id == null) {
            warnOnce("id|" + key, "[MCPSkins] Skin animation '{}' over '{}' produced an invalid id; ignoring it.",
                    key.skinFile(), key.baseAnimation());
            return FAILED;
        }

        Map<String, Object> animations = new LinkedHashMap<>();
        String version = null;
        if (key.baseAnimation() != null) {
            BedrockAnimationFile base = ClientAssetsManager.INSTANCE.getBedrockAnimations(key.baseAnimation());
            if (base == null || base.getAnimations() == null) {
                // A glTF-animated gun or a broken pack: no Bedrock base to fall through to.
                warnOnce("base|" + key, "[MCPSkins] Skin animation '{}' needs a Bedrock base animation file, but "
                        + "'{}' is not one. Skin keeps the weapon's own animations.", key.skinFile(), key.baseAnimation());
                return FAILED;
            }
            animations.putAll(base.getAnimations());
            version = base.getVersion();
        }
        animations.putAll(skin.getAnimations());
        if (skin.getVersion() != null) version = skin.getVersion();

        try {
            BedrockAnimationFile file = new BedrockAnimationFile();
            animationsField.set(file, animations);
            if (versionField != null) versionField.set(file, version);
            Merged merged = new Merged(id, file);
            register(merged);
            return merged;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (WARNED.add("merge|" + key)) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to merge skin animation '{}' over '{}'.",
                        key.skinFile(), key.baseAnimation(), e);
            }
            return FAILED;
        }
    }

    private static ResourceLocation syntheticId(MergeKey key) {
        ResourceLocation base = key.baseAnimation();
        String basePart = base == null ? "none" : base.getNamespace() + "/" + base.getPath();
        ResourceLocation skin = key.skinFile();
        return ResourceLocation.tryBuild(MCPSkins.MOD_ID,
                "skin_anim/" + basePart + "/" + skin.getNamespace() + "/" + skin.getPath());
    }

    private static void register(Merged merged) {
        // TACZ writes this map under the manager's monitor, partly from its preload thread.
        synchronized (animationManager) {
            dataMap.put(merged.id(), merged.file());
            if (failedDataSet != null) {
                failedDataSet.remove(merged.id());
            }
        }
        INJECTED.add(merged.id());
    }

    private static boolean ensureSupported() {
        if (supportState != -1) return supportState == 1;
        synchronized (SkinAnimationInjector.class) {
            if (supportState != -1) return supportState == 1;
            boolean ok = discover();
            supportState = ok ? 1 : 0;
            if (!ok && !warningLogged) {
                warningLogged = true;
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Could not hook into ClientAssetsManager for skin animation overrides - "
                                + "skins with an '.animation.json' keep the weapon's own animations. "
                                + "Every other skin override is unaffected.");
            }
            return ok;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean discover() {
        try {
            Class<?> lazyManagerClass = Class.forName("com.tacz.guns.resource.manager.LazyJsonDataManager");

            Field managerField = findManagerField(lazyManagerClass);
            if (managerField == null) return false;
            managerField.setAccessible(true);
            Object manager = managerField.get(ClientAssetsManager.INSTANCE);
            if (manager == null) return false;

            Field dataMapField = findDeclaredField(lazyManagerClass, "dataMap");
            if (dataMapField == null) return false;
            dataMapField.setAccessible(true);
            Map<ResourceLocation, Object> map = (Map<ResourceLocation, Object>) dataMapField.get(manager);
            if (map == null) return false;

            Set<ResourceLocation> failed = null;
            Field failedField = findDeclaredField(lazyManagerClass, "failedData");
            if (failedField != null) {
                failedField.setAccessible(true);
                if (failedField.get(manager) instanceof Set<?> set) {
                    failed = (Set<ResourceLocation>) set;
                }
            }

            Field animations = findFileField("animations", Map.class);
            if (animations == null) return false;
            Field version = findFileField("version", String.class);

            animationManager = manager;
            dataMap = map;
            failedDataSet = failed;
            animationsField = animations;
            versionField = version;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MCPSkins.LOGGER.error("[MCPSkins] Failed to set up skin animation injection into ClientAssetsManager.", e);
            return false;
        }
    }

    // By name, then by generic type in case a port renames it.
    private static Field findManagerField(Class<?> lazyManagerClass) {
        Class<?> owner = ClientAssetsManager.class;
        try {
            Field byName = owner.getDeclaredField("bedrockAnimation");
            if (byName.getType() == lazyManagerClass) return byName;
        } catch (NoSuchFieldException ignored) {
        }
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() != lazyManagerClass) continue;
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType parameterized
                    && parameterized.getActualTypeArguments().length == 1
                    && parameterized.getActualTypeArguments()[0] == BedrockAnimationFile.class) {
                return field;
            }
        }
        return null;
    }

    private static Field findFileField(String name, Class<?> type) {
        Field match = null;
        for (Field field : BedrockAnimationFile.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            if (field.getName().equals(name)) {
                match = field;
                break;
            }
            match = match == null ? field : match;
        }
        if (match != null) match.setAccessible(true);
        return match;
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

    private static void warnOnce(String key, String message, Object... args) {
        if (WARNED.add(key)) {
            MCPSkins.LOGGER.warn(message, args);
        }
    }

    public static void reset() {
        synchronized (SkinAnimationInjector.class) {
            Map<ResourceLocation, Object> map = dataMap;
            Object manager = animationManager;
            if (map != null && manager != null) {
                synchronized (manager) {
                    for (ResourceLocation id : INJECTED) {
                        map.remove(id);
                    }
                }
            }
            INJECTED.clear();
            RECEIVED.clear();
            MERGED.clear();

            supportState = -1;
            animationManager = null;
            dataMap = null;
            failedDataSet = null;
            animationsField = null;
            versionField = null;
            WARNED.clear();
        }
    }
}
