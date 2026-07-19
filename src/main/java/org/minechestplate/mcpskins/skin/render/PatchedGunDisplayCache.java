package org.minechestplate.mcpskins.skin.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches patched {@link GunDisplayInstance} objects so {@link GunDisplayInstancePatcher}
 * doesn't rebuild one (via Unsafe) on every call to {@code TimelessAPI.getGunDisplay}.
 * <p>
 * Keyed by a stable {@code (baseGunId, skinId)} string rather than by the identity of the
 * original {@link GunDisplayInstance}, since TACZ's object lifetime for that instance
 * can't be relied on. If the original does change, the cache entry detects the mismatch
 * and rebuilds automatically.
 */
public final class PatchedGunDisplayCache {

    private record CacheEntry(GunDisplayInstance original, ResourceLocation texture,
                              ResourceLocation icon, GunDisplayInstance patched) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    // Tracks how often each key's patch is rebuilt, for diagnosing cache misses
    private static final Map<String, Integer> RECREATE_COUNTS = new ConcurrentHashMap<>();

    private PatchedGunDisplayCache() {
    }

    /**
     * @param cacheKey stable key for the (weapon, skin) pair, typically
     *                 {@code baseGunId + "\u0000" + skinId}
     */
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance original,
                                                 ResourceLocation texture, ResourceLocation icon) {
        if (original == null) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.original() == original
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(original, texture, icon, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            MCPSkins.LOGGER.info(
                    "[MCPSkins] Rebuilt patched GunDisplayInstance for '{}' (rebuild #{} this session).",
                    cacheKey, count);
        } else {
            // withOverrides couldn't produce a patch - don't leave a stale entry cached
            CACHE.remove(cacheKey);
        }
        return patched;
    }

    /**
     * Clears the cache. Called on client resource reload.
     */
    public static void clear() {
        CACHE.clear();
        RECREATE_COUNTS.clear();
    }
}