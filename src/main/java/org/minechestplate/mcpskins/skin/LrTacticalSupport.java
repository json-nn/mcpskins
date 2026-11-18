package org.minechestplate.mcpskins.skin;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.minechestplate.mcpskins.MCPSkins;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether LesRaisins Tactical Equipments loaded a given melee, throwable or consumable, for
 * {@link SkinAvailability}. Reflective (optional mod, no public maven); every failure reads as
 * "available", so a skin is shown rather than silently hidden.
 */
public final class LrTacticalSupport {

    public static final String MOD_ID = "lrtactical";

    private static final String API_CLASS = "me.xjqsh.lrtactical.api.LrTacticalAPI";

    private static final Map<SkinDataModels.SkinTarget, String> INDEX_METHODS = Map.of(
            SkinDataModels.SkinTarget.MELEE, "getMeleeIndexes",
            SkinDataModels.SkinTarget.THROWABLE, "getThrowableIndexes",
            SkinDataModels.SkinTarget.CONSUMABLE, "getConsumableIndexes");

    private static final Map<SkinDataModels.SkinTarget, Method> RESOLVED = new ConcurrentHashMap<>();
    private static volatile Boolean present;
    private static volatile boolean warned;

    private LrTacticalSupport() {
    }

    public static boolean isLoaded() {
        Boolean cached = present;
        if (cached == null) {
            cached = ModList.get().isLoaded(MOD_ID);
            present = cached;
        }
        return cached;
    }

    public static boolean hasItem(SkinDataModels.SkinTarget target, ResourceLocation id) {
        Method accessor = accessor(target);
        if (accessor == null) {
            return true;
        }
        try {
            Object indexes = accessor.invoke(null);
            if (!(indexes instanceof Collection<?> collection)) {
                return true;
            }
            for (Object index : collection) {
                Method getId = index.getClass().getMethod("getId");
                getId.setAccessible(true);
                if (id.equals(getId.invoke(index))) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("could not read the " + target + " index", e);
            return true;
        }
    }

    private static Method accessor(SkinDataModels.SkinTarget target) {
        if (!isLoaded()) {
            return null;
        }
        String name = INDEX_METHODS.get(target);
        if (name == null) {
            return null;
        }
        return RESOLVED.computeIfAbsent(target, key -> {
            try {
                Method method = Class.forName(API_CLASS).getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException e) {
                warnOnce("could not find " + API_CLASS + "." + name + "()", e);
                return null;
            }
        });
    }

    private static void warnOnce(String what, Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        MCPSkins.LOGGER.warn("[MCPSkins] LesRaisins Tactical integration: {}. "
                + "Its skins will be treated as available.", what, cause);
    }
}
