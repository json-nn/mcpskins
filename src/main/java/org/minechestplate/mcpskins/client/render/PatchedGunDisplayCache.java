package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches patched {@link GunDisplayInstance} objects so {@link GunDisplayInstancePatcher}
 * doesn't rebuild one via Unsafe on every {@code TimelessAPI.getGunDisplay} call.
 * <p>
 * Keyed by {@code (baseGunId, skinId)} and the override values, deliberately <b>not</b> by
 * {@code original}'s identity: TACZ hands back different objects for the same weapon, even
 * within a frame, because {@code setCurrentGunItem} re-enters {@code getGunDisplay} on every
 * context refresh. Comparing identity meant a rebuild per call - wasteful here, and the same
 * mistake that made {@link GunModelPatcher} hand out duplicate state machines.
 * <p>
 * Not keyed on {@link ClientSkinAssetCache#generation()} either. A late-arriving asset changes
 * what {@link SkinAssetResolver} returns, so the override comparison already catches it. Keying
 * on that global counter rebuilt every entry whenever any unrelated asset finished streaming,
 * which replayed the held weapon's draw animation - and its sound - once per skin browsed.
 */
public final class PatchedGunDisplayCache {

    private record CacheEntry(ResourceLocation texture, ResourceLocation icon,
                              ResourceLocation hud, ResourceLocation hudEmpty,
                              GunDisplayInstance patched) {
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
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)
                && Objects.equals(existing.hud(), hud)
                && Objects.equals(existing.hudEmpty(), hudEmpty)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon, hud, hudEmpty);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(texture, icon, hud, hudEmpty, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            if (existing == null) {
                MCPSkins.LOGGER.info(
                        "[MCPSkins] Built patched GunDisplayInstance for '{}' (build #{} this session).",
                        cacheKey, count);
            } else {
                // An override changed - normally an asset arriving after the first resolution
                // ran with a fallback. Logged in full so anything else stands out.
                MCPSkins.LOGGER.info(
                        "[MCPSkins] Rebuilt patched GunDisplayInstance for '{}' (rebuild #{} this session) - "
                                + "texture {} -> {}, icon {} -> {}, hud {} -> {}, hudEmpty {} -> {}.",
                        cacheKey, count,
                        existing.texture(), texture, existing.icon(), icon,
                        existing.hud(), hud, existing.hudEmpty(), hudEmpty);
            }
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