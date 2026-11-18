package org.minechestplate.mcpskins.client.render;

import com.tacz.guns.client.resource.GunDisplayInstance;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Patched {@link GunDisplayInstance}s per (gun, skin), compared on the overrides and on the
 * {@link GunModelPatcher} instance underneath. Never keyed on {@code original}'s identity (TACZ
 * changes it within a frame) or on the asset generation. See docs/ai/tacz-integration.md.
 */
public final class PatchedGunDisplayCache {

    private record CacheEntry(ResourceLocation texture, ResourceLocation icon,
                              ResourceLocation hud, ResourceLocation hudEmpty,
                              GunDisplayInstance rebuilt, GunDisplayInstance patched) {
    }

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> RECREATE_COUNTS = new ConcurrentHashMap<>();

    private PatchedGunDisplayCache() {
    }

    // rebuilt is original when it came from GunModelPatcher, null when it is TACZ's.
    public static GunDisplayInstance getOrCreate(String cacheKey, GunDisplayInstance original, GunDisplayInstance rebuilt,
                                                 ResourceLocation texture, ResourceLocation icon,
                                                 ResourceLocation hud, ResourceLocation hudEmpty) {
        if (original == null) return null;

        CacheEntry existing = CACHE.get(cacheKey);
        if (existing != null
                && existing.rebuilt() == rebuilt
                && Objects.equals(existing.texture(), texture)
                && Objects.equals(existing.icon(), icon)
                && Objects.equals(existing.hud(), hud)
                && Objects.equals(existing.hudEmpty(), hudEmpty)) {
            return existing.patched();
        }

        GunDisplayInstance patched = GunDisplayInstancePatcher.withOverrides(original, texture, icon, hud, hudEmpty);
        if (patched != null) {
            CACHE.put(cacheKey, new CacheEntry(texture, icon, hud, hudEmpty, rebuilt, patched));
            int count = RECREATE_COUNTS.merge(cacheKey, 1, Integer::sum);
            if (existing == null) {
                MCPSkins.LOGGER.info(
                        "[MCPSkins] Built patched GunDisplayInstance for '{}' (build #{} this session).",
                        cacheKey, count);
            } else {
                // Usually a late asset replacing a fallback.
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

    public static void clear() {
        CACHE.clear();
        RECREATE_COUNTS.clear();
    }
}