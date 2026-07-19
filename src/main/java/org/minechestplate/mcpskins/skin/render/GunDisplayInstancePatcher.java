package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Reflection-based patcher that creates a modified copy of a {@link GunDisplayInstance}
 * with an overridden texture and/or 2D inventory icon, for TACZ's fork at
 * MUKSC/TACZ-1.21.1 (neoforge/1.21.1).
 * <p>
 * {@code GunDisplayInstance} is neither {@code Cloneable} nor a record, and its only
 * constructor does expensive parsing/asset loading, so a copy is built by allocating an
 * instance via {@code sun.misc.Unsafe#allocateInstance} (bypassing the constructor
 * entirely, the same trick used by Gson/Objenesis/Netty) and copying every declared
 * field over by reflection - a manual shallow clone.
 * <p>
 * Only the texture and icon fields are overridden; the model/geometry is intentionally
 * left untouched, since geometry isn't stored as a simple field reference after loading
 * (replacing it correctly would mean reimplementing a large chunk of TACZ's private
 * asset-loading pipeline). Full geometry replacement is instead handled by
 * {@link GunModelPatcher}.
 * <p>
 * <b>Lazy-loading caveat:</b> the base instance loads its texture/model lazily, tracked
 * by private {@code modelLoaded}/{@code modelLoadFailed} flags. Copying before the
 * original has loaded would carry over "not loaded" flags, and the copy's own lazy-load
 * check would then silently overwrite our texture override. This method never forces
 * that load itself - see {@link #isBaseReadyToPatch}, which only reads TACZ's own flags
 * and returns {@code null} (skip patching for now) if loading isn't done yet. An earlier
 * version forced the load via the public {@code getModelTexture()} getter, which has the
 * side effect of triggering the same lazy load - that caused a race with resource pack
 * reloads that could leave a weapon rendering with no model at all.
 * <p>
 * The 2D inventory icon field's exact name isn't verified against this fork's decompiled
 * source, so {@link #resolveIconField} tries a list of plausible names
 * ({@link #ICON_FIELD_CANDIDATES}) and uses whichever one exists with type
 * {@link ResourceLocation}. If none match, the icon override feature silently no-ops
 * (with one log warning) while texture overrides keep working normally. If that warning
 * appears, decompile {@code GunDisplayInstance.class} from the fork's jar, find the real
 * field name backing {@code textures/gun/slot/*.png}, and add it first to
 * {@link #ICON_FIELD_CANDIDATES}.
 */
public final class GunDisplayInstancePatcher {

    private static final String TEXTURE_FIELD_NAME = "modelTexture";

    /** Private lazy-load flags read by {@link #isBaseReadyToPatch}, never written by this class. */
    private static final String MODEL_LOADED_FIELD_NAME = "modelLoaded";
    private static final String MODEL_LOAD_FAILED_FIELD_NAME = "modelLoadFailed";
    private static volatile boolean loadFlagWarningLogged = false;

    // Cached texture field for non-forcing reads in getTexture()
    private static volatile boolean textureFieldSearched = false;
    private static volatile Field cachedTextureField;
    private static volatile boolean textureFieldWarningLogged = false;

    /**
     * Plausible names for the 2D icon field, tried in order; the first one that exists
     * with type {@link ResourceLocation} is used. Add the verified name first once known.
     */
    private static final String[] ICON_FIELD_CANDIDATES = {
            "icon", "slotTexture", "iconTexture", "slotIcon", "invTexture",
            "inventoryTexture", "guiTexture", "slot"
    };

    // Icon field is searched once per session and cached - see resolveIconField()
    private static volatile boolean iconFieldSearched = false;
    private static volatile Field cachedIconField;
    private static volatile boolean iconFieldWarningLogged = false;

    private GunDisplayInstancePatcher() {
    }

    /**
     * Reads the base weapon's texture without triggering a lazy load, via reflection
     * on the private {@code modelTexture} field rather than the public
     * {@code getModelTexture()} getter (which has the side effect of forcing the load -
     * see the class javadoc for why that caused resource-pack-reload races). Falls back
     * to the forcing getter, with a one-time warning, if the field can't be found.
     */
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

    /**
     * Current value of the 2D inventory icon field, or {@code null} if it couldn't be
     * found by reflection (see {@link #ICON_FIELD_CANDIDATES}).
     */
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

    /**
     * Whether the base instance has finished its own lazy texture/model load, so it's
     * safe to copy. Never forces the load itself - see the class javadoc. If the
     * {@code modelLoaded} field can't be found, treats the instance as ready (with a
     * one-time warning) rather than blocking skins entirely.
     */
    private static boolean isBaseReadyToPatch(GunDisplayInstance instance) {
        Boolean loaded = readBooleanField(instance, MODEL_LOADED_FIELD_NAME);
        if (loaded == null) {
            warnMissingLoadFlagOnce();
            return true;
        }
        if (!loaded) return false;

        Boolean failed = readBooleanField(instance, MODEL_LOAD_FAILED_FIELD_NAME);
        // A missing "failed" field isn't treated as blocking - modelLoaded==true is enough
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

    /**
     * Returns a copy of {@code instance} with its texture overridden (required) and,
     * optionally, its 2D inventory icon overridden. The model/geometry is never touched
     * here - see {@link GunModelPatcher} for that. Returns {@code null} if the original
     * isn't ready to copy yet (lazy load still in progress) or if reflection fails.
     */
    public static GunDisplayInstance withOverrides(GunDisplayInstance instance, ResourceLocation textureOverride, ResourceLocation iconOverride) {
        if (instance == null) return null;
        if (textureOverride == null && iconOverride == null) return instance;

        if (!isBaseReadyToPatch(instance)) {
            return null;
        }

        Field iconField = iconOverride != null ? resolveIconField() : null;

        GunDisplayInstance copy = shallowCopy(instance);
        if (copy == null) return null;
        try {
            if (textureOverride != null) {
                writeField(copy, TEXTURE_FIELD_NAME, textureOverride);
            }
            if (iconField != null) {
                iconField.set(copy, iconOverride);
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("Failed to apply skin texture/icon override via reflection. " +
                    "Check TEXTURE_FIELD_NAME/ICON_FIELD_CANDIDATES in GunDisplayInstancePatcher - " +
                    "the fields may have been renamed in a newer TACZ version.", e);
            return null;
        }
    }

    /**
     * Convenience overload for texture-only overrides.
     */
    public static GunDisplayInstance withTextureOverride(GunDisplayInstance instance, ResourceLocation texture) {
        return withOverrides(instance, texture, null);
    }

    /**
     * Searches {@link #ICON_FIELD_CANDIDATES} by reflection and caches the result for
     * the session. Logs one warning on failure, not one per call.
     */
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
                    // try the next candidate
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

    /**
     * Manual shallow copy via {@code Unsafe#allocateInstance} (the constructor is never
     * called) plus a field-by-field reflective copy. {@code GunDisplayInstance} is
     * neither {@code Cloneable} nor a record, so neither {@code Object.clone()} nor a
     * canonical record constructor is an option here.
     */
    private static GunDisplayInstance shallowCopy(GunDisplayInstance instance) {
        try {
            Unsafe unsafe = getUnsafe();
            Object rawCopy = unsafe.allocateInstance(GunDisplayInstance.class);
            GunDisplayInstance copy = (GunDisplayInstance) rawCopy;
            for (Field field : GunDisplayInstance.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(instance));
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            MCPSkins.LOGGER.error("Failed to copy GunDisplayInstance via Unsafe. The environment may " +
                    "be blocking sun.misc.Unsafe - skin overrides won't work, but this won't crash.", e);
            return null;
        }
    }

    private static Unsafe getUnsafe() throws ReflectiveOperationException {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
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