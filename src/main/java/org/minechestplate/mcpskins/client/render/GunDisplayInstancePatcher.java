package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies a {@link GunDisplayInstance} with an overridden texture, icon or HUD icon. Geometry is
 * not a simple field once loaded, so {@link GunModelPatcher} handles that separately.
 * <p>
 * The class is neither {@code Cloneable} nor a record, and its constructor does real asset
 * loading, so copies go through {@code Unsafe#allocateInstance} plus a reflective field copy.
 * Each override applies independently: one failing field leaves the rest working.
 * <p>
 * Never forces the lazy texture load, which raced with resource pack reloads;
 * {@link #isBaseReadyToPatch} waits for TACZ's own flags instead.
 */
public final class GunDisplayInstancePatcher {

    private static final String TEXTURE_FIELD_NAME = "modelTexture";
    private static final String HUD_FIELD_NAME = "hudTexture";
    private static final String HUD_EMPTY_FIELD_NAME = "hudEmptyTexture";

    private static final String MODEL_LOADED_FIELD_NAME = "modelLoaded";
    private static final String MODEL_LOAD_FAILED_FIELD_NAME = "modelLoadFailed";
    private static volatile boolean loadFlagWarningLogged = false;

    private static volatile boolean textureFieldSearched = false;
    private static volatile Field cachedTextureField;
    private static volatile boolean textureFieldWarningLogged = false;

    private static final String[] ICON_FIELD_CANDIDATES = {
            "icon", "slotTexture", "iconTexture", "slotIcon", "invTexture",
            "inventoryTexture", "guiTexture", "slot"
    };

    private static volatile boolean iconFieldSearched = false;
    private static volatile Field cachedIconField;
    private static volatile boolean iconFieldWarningLogged = false;

    private GunDisplayInstancePatcher() {
    }

    /** Directly, not through the getter, which would force the lazy load as a side effect. */
    public static ResourceLocation getTexture(GunDisplayInstance instance) {
        if (instance == null) return null;
        Field field = resolveTextureField();
        if (field == null) {
            warnMissingTextureFieldOnce();
            return instance.getModelTexture();
        }
        try {
            Object value = field.get(instance);
            return value instanceof ResourceLocation location ? location : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field resolveTextureField() {
        if (textureFieldSearched) return cachedTextureField;
        synchronized (GunDisplayInstancePatcher.class) {
            if (textureFieldSearched) return cachedTextureField;
            Field found;
            try {
                found = findField(GunDisplayInstance.class, TEXTURE_FIELD_NAME);
                found.setAccessible(true);
            } catch (NoSuchFieldException e) {
                found = null;
            }
            cachedTextureField = found;
            textureFieldSearched = true;
            return found;
        }
    }

    private static void warnMissingTextureFieldOnce() {
        if (textureFieldWarningLogged) return;
        synchronized (GunDisplayInstancePatcher.class) {
            if (textureFieldWarningLogged) return;
            textureFieldWarningLogged = true;
            MCPSkins.LOGGER.warn(
                    "Field '{}' not found on GunDisplayInstance for this TACZ version - falling back "
                            + "to getModelTexture(), which means the resource-pack-reload race guard is "
                            + "currently inactive. Decompile GunDisplayInstance.class and fix "
                            + "TEXTURE_FIELD_NAME if the field was renamed.",
                    TEXTURE_FIELD_NAME);
        }
    }

    public static ResourceLocation getIcon(GunDisplayInstance instance) {
        if (instance == null) return null;
        Field field = resolveIconField();
        if (field == null) return null;
        try {
            Object value = field.get(instance);
            return value instanceof ResourceLocation location ? location : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** The getter is fine here: {@code hudTexture} is set synchronously, with no lazy load. */
    public static ResourceLocation getHud(GunDisplayInstance instance) {
        return instance != null ? instance.getHUDTexture() : null;
    }

    public static ResourceLocation getHudEmpty(GunDisplayInstance instance) {
        return instance != null ? instance.getHudEmptyTexture() : null;
    }

    private static boolean isBaseReadyToPatch(GunDisplayInstance instance) {
        Boolean loaded = readBooleanField(instance, MODEL_LOADED_FIELD_NAME);
        if (loaded == null) {
            warnMissingLoadFlagOnce();
            return true;
        }
        if (!loaded) return false;

        Boolean failed = readBooleanField(instance, MODEL_LOAD_FAILED_FIELD_NAME);
        return failed == null || !failed;
    }

    private static Boolean readBooleanField(GunDisplayInstance instance, String fieldName) {
        try {
            Field field = findField(GunDisplayInstance.class, fieldName);
            field.setAccessible(true);
            Object value = field.get(instance);
            return value instanceof Boolean bool ? bool : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void warnMissingLoadFlagOnce() {
        if (loadFlagWarningLogged) return;
        synchronized (GunDisplayInstancePatcher.class) {
            if (loadFlagWarningLogged) return;
            loadFlagWarningLogged = true;
            MCPSkins.LOGGER.warn(
                    "Field '{}' not found on GunDisplayInstance for this TACZ version - the "
                            + "resource-pack-reload race guard is disabled, skin patches apply without "
                            + "checking readiness. Decompile GunDisplayInstance.class and fix "
                            + "MODEL_LOADED_FIELD_NAME/MODEL_LOAD_FAILED_FIELD_NAME if renamed.",
                    MODEL_LOADED_FIELD_NAME);
        }
    }

    private static final Set<String> WARNED_WRITE_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * @param  instance any override may be null to leave that asset untouched
     * @return null only if the original isn't ready to copy yet, or the copy itself failed
     */
    public static GunDisplayInstance withOverrides(GunDisplayInstance instance, ResourceLocation textureOverride,
                                                   ResourceLocation iconOverride, ResourceLocation hudOverride, ResourceLocation hudEmptyOverride) {
        if (instance == null) return null;
        if (textureOverride == null && iconOverride == null && hudOverride == null && hudEmptyOverride == null) {
            return instance;
        }

        if (!isBaseReadyToPatch(instance)) {
            return null;
        }

        GunDisplayInstance copy = shallowCopy(instance);
        if (copy == null) return null;

        if (textureOverride != null) {
            tryWriteField(copy, TEXTURE_FIELD_NAME, textureOverride);
        }
        if (iconOverride != null) {
            Field iconField = resolveIconField();
            if (iconField != null) {
                trySetField(iconField, copy, iconOverride, "icon");
            }
        }
        if (hudOverride != null) {
            tryWriteField(copy, HUD_FIELD_NAME, hudOverride);
        }
        if (hudEmptyOverride != null) {
            tryWriteField(copy, HUD_EMPTY_FIELD_NAME, hudEmptyOverride);
        }
        return copy;
    }

    private static void tryWriteField(Object target, String fieldName, Object value) {
        try {
            writeField(target, fieldName, value);
        } catch (ReflectiveOperationException e) {
            warnWriteFailureOnce(fieldName, e);
        }
    }

    private static void trySetField(Field field, Object target, Object value, String label) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            warnWriteFailureOnce(label, e);
        }
    }

    private static void warnWriteFailureOnce(String label, ReflectiveOperationException e) {
        if (!WARNED_WRITE_FAILURES.add(label)) return;
        MCPSkins.LOGGER.error(
                "Failed to write GunDisplayInstance field '{}' via reflection - that specific "
                        + "override, and only that one, stays the base weapon's asset. The field may "
                        + "have been renamed in a newer TACZ version.",
                label, e);
    }

    public static GunDisplayInstance withTextureOverride(GunDisplayInstance instance, ResourceLocation texture) {
        return withOverrides(instance, texture, null, null, null);
    }

    private static Field resolveIconField() {
        if (iconFieldSearched) return cachedIconField;
        synchronized (GunDisplayInstancePatcher.class) {
            if (iconFieldSearched) return cachedIconField;
            Field found = null;
            for (String name : ICON_FIELD_CANDIDATES) {
                try {
                    Field field = findField(GunDisplayInstance.class, name);
                    if (field.getType() == ResourceLocation.class) {
                        field.setAccessible(true);
                        found = field;
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }
            cachedIconField = found;
            iconFieldSearched = true;
            if (found == null && !iconFieldWarningLogged) {
                iconFieldWarningLogged = true;
                MCPSkins.LOGGER.warn(
                        "Optional 'custom skin icon' feature disabled: no ResourceLocation field found "
                                + "under any expected name {} in GunDisplayInstance for this TACZ version. "
                                + "3D model re-texturing keeps working normally - only the 2D inventory "
                                + "icon stays the base weapon's. To enable it, decompile "
                                + "GunDisplayInstance.class, find the real field for textures/gun/slot/*.png, "
                                + "and add it first to GunDisplayInstancePatcher.ICON_FIELD_CANDIDATES.",
                        Arrays.toString(ICON_FIELD_CANDIDATES));
            }
            return found;
        }
    }

    private static GunDisplayInstance shallowCopy(GunDisplayInstance instance) {
        try {
            Unsafe unsafe = getUnsafe();
            Object rawCopy = unsafe.allocateInstance(GunDisplayInstance.class);
            GunDisplayInstance copy = (GunDisplayInstance) rawCopy;
            // The whole superclass chain: allocateInstance leaves every field at its default,
            // so an inherited one would silently stay null in the copy.
            for (Class<?> current = GunDisplayInstance.class; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    field.set(copy, field.get(instance));
                }
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("Failed to copy GunDisplayInstance via Unsafe. The environment may " +
                    "be blocking sun.misc.Unsafe - skin overrides won't work, but this won't crash.", e);
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

    private static void writeField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
