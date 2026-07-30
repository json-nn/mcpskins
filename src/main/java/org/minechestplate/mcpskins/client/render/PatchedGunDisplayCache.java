package org.minechestplate.mcpskins.client.render;

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
 * Keyed by a stable {@code (baseGunId, skinId)} string, <b>not</b> by the identity of
 * {@code original} - TACZ's object lifetime for it can't be relied on (it can be a
 * different object on two calls a frame apart, or even two calls on the same frame,
 * since {@code GunAnimationStateContext#setCurrentGunItem} calls back into
 * {@code TimelessAPI.getGunDisplay} on every context refresh). This used to trigger a
 * rebuild - a fresh {@code Unsafe}-allocated shallow copy - on every such mismatch,
 * which is wasted work here (texture/icon/HUD only, harmless beyond the cost) but was
 * the same failure mode that made {@link GunModelPatcher} hand out two independent
 * {@code GunDisplayInstance}/state-machine pairs for one logical skin. The cacheKey
 * plus the override values are the actual identity of what this cache holds; only
 * those, or an explicit {@link #clear()}, should invalidate an entry.
 */
public final class PatchedGunDisplayCache {

    /**
     * @param generation the {@link ClientSkinAssetCache#generation()} this entry was built
     *                   at; see the field comment on that counter for why the override
     *                   values alone aren't enough to decide a hit
     */
    private record CacheEntry(ResourceLocation texture, ResourceLocation icon,
                              ResourceLocation hud, ResourceLocation hudEmpty,
                              int generation, GunDisplayInstance patched) {
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

        int generation = ClientSkinAssetCache.generation();
        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.generation() == generation
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)
                && Objects.equals(existing.hud(), hud)
                && Objects.equals(existing.hudEmpty(), hudEmpty)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon, hud, hudEmpty);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(texture, icon, hud, hudEmpty, generation, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            if (existing == null) {
                MCPSkins.LOGGER.info(
                        "[MCPSkins] Built patched GunDisplayInstance for '{}' (build #{} this session).",
                        cacheKey, count);
            } else if (!overridesEqual(existing, texture, icon, hud, hudEmpty)) {
                // Not identity churn anymore (that's no longer a trigger - see class javadoc), so
                // this means an override value actually changed, most likely a network-delivered
                // texture/icon/HUD asset finishing its fetch after the first resolution attempt
                // already ran with a null/fallback value. Logged in full so a rebuild that DOESN'T
                // fit that explanation is easy to spot instead of guessed at.
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

    /**
     * Whether this key's override values are unchanged from the cached entry.
     * <p>
     * Used only to decide whether a rebuild is worth logging. A rebuild triggered purely by
     * the generation counter moving is routine - some other asset finished arriving - and
     * happens once per live key per arrival, so logging those would bury the interesting
     * case: an override value that genuinely changed under a stable generation.
     */
    private static boolean overridesEqual(CacheEntry entry, ResourceLocation texture, ResourceLocation icon,
                                          ResourceLocation hud, ResourceLocation hudEmpty) {
        return Objects.equals(entry.texture(), texture)
                && Objects.equals(entry.icon(), icon)
                && Objects.equals(entry.hud(), hud)
                && Objects.equals(entry.hudEmpty(), hudEmpty);
    }

    /** Clears the cache. Called on client resource reload. */
    public static void clear() {
        CACHE.clear();
        RECREATE_COUNTS.clear();
    }
}