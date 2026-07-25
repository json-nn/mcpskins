package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches patched {@link GunDisplayInstance} objects so {@link GunDisplayInstancePatcher}
 * doesn't rebuild one via Unsafe on every call to {@code TimelessAPI.getGunDisplay}.
 * <p>
 * Keyed by a stable {@code (baseGunId, skinId)} string rather than instance identity,
 * since TACZ's object lifetime for the original can't be relied on; a mismatch just
 * triggers a rebuild.
 */
public final class PatchedGunDisplayCache {

    private record CacheEntry(GunDisplayInstance original, ResourceLocation texture, ResourceLocation icon,
                              ResourceLocation hud, ResourceLocation hudEmpty, GunDisplayInstance patched) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> RECREATE_COUNTS = new ConcurrentHashMap<>();

    private PatchedGunDisplayCache() {
    }

    /**
     * @param cacheKey stable key for the (weapon, skin) pair, typically
     *                 {@code baseGunId + "\u0000" + skinId}
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance original,
                                                 ResourceLocation texture, ResourceLocation icon,
                                                 ResourceLocation hud, ResourceLocation hudEmpty) {
        if (original == null) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.original() == original
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)
                && Objects.equals(existing.hud(), hud)
                && Objects.equals(existing.hudEmpty(), hudEmpty)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon, hud, hudEmpty);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(original, texture, icon, hud, hudEmpty, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            MCPSkins.LOGGER.info(
                    "[MCPSkins] Rebuilt patched GunDisplayInstance for '{}' (rebuild #{} this session).",
                    cacheKey, count);
        } else {
            CACHE.remove(cacheKey);
        }
        return patched;
    }

    /** Clears the cache. Called on client resource reload. */
    public static void clear() {
        CACHE.clear();
        RECREATE_COUNTS.clear();
    }
}
