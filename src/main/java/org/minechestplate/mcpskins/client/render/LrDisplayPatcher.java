package org.minechestplate.mcpskins.client.render;

import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies a LesRaisins display instance with a skin's texture and icon. Fully reflective (no
 * public maven) and only reached from an optional mixin. The instances are plain data holders,
 * so a copy plus two field writes suffices. Cached: this is on the per-frame render path.
 */
public final class LrDisplayPatcher {

    // Field names on MeleeDisplayInstance, ThrowableDisplayInstance and ConsumableDisplayInstance.
    private static final String TEXTURE_FIELD = "texture";
    private static final String SLOT_TEXTURE_FIELD = "slotTexture";

    private record CacheKey(Class<?> type, String baseId, String skinId, int generation) {
    }

    private static final Map<CacheKey, Object> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static volatile Unsafe unsafe;
    private static volatile boolean warned;

    private LrDisplayPatcher() {
    }

    public static void clear() {
        CACHE.clear();
    }

    public static ResourceLocation getTexture(Object display) {
        return read(display, TEXTURE_FIELD);
    }

    public static ResourceLocation getSlotTexture(Object display) {
        return read(display, SLOT_TEXTURE_FIELD);
    }

    public static Object patch(Object display, String baseId, String skinId,
                               ResourceLocation texture, ResourceLocation slotTexture) {
        if (display == null || (texture == null && slotTexture == null)) {
            return null;
        }
        CacheKey key = new CacheKey(display.getClass(), baseId, skinId,
                ClientSkinAssetCache.generation());
        Object cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Object copy = copyOf(display);
        if (copy == null) {
            return null;
        }
        boolean changed = write(copy, TEXTURE_FIELD, texture) | write(copy, SLOT_TEXTURE_FIELD, slotTexture);
        if (!changed) {
            return null;
        }
        CACHE.put(key, copy);
        return copy;
    }

    // allocateInstance: the constructor is private and the real loader re-reads assets.
    private static Object copyOf(Object display) {
        try {
            Unsafe theUnsafe = unsafe();
            Object copy = theUnsafe.allocateInstance(display.getClass());
            // Superclasses too: allocateInstance leaves every field at its default.
            for (Class<?> current = display.getClass(); current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    field.set(copy, field.get(display));
                }
            }
            return copy;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("could not copy " + display.getClass().getSimpleName(), e);
            return null;
        }
    }

    private static ResourceLocation read(Object display, String name) {
        if (display == null) return null;
        Field field = field(display.getClass(), name);
        if (field == null) return null;
        try {
            return field.get(display) instanceof ResourceLocation location ? location : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("could not read " + name, e);
            return null;
        }
    }

    private static boolean write(Object display, String name, ResourceLocation value) {
        if (value == null) return false;
        Field field = field(display.getClass(), name);
        if (field == null) return false;
        try {
            field.set(display, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("could not write " + name, e);
            return false;
        }
    }

    private static Field field(Class<?> owner, String name) {
        return FIELDS.computeIfAbsent(owner.getName() + "#" + name, key -> {
            for (Class<?> current = owner; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                try {
                    Field found = current.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException ignored) {
                }
            }
            warnOnce("no field '" + name + "' on " + owner.getName(), null);
            return null;
        });
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Unsafe local = unsafe;
        if (local == null) {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            local = (Unsafe) field.get(null);
            unsafe = local;
        }
        return local;
    }

    private static void warnOnce(String what, Throwable cause) {
        if (warned) return;
        warned = true;
        MCPSkins.LOGGER.warn("[MCPSkins] LesRaisins display patching disabled: {}. "
                + "Its items will render unskinned.", what, cause);
    }
}
