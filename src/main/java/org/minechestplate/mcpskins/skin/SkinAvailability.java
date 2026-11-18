package org.minechestplate.mcpskins.skin;

import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides skins whose base item is not loaded, from every UI, fuse pool and {@code /mcpskins give all}.
 * Lazy and memoized, never at load: TACZ's common index is empty during the whole reload. Server
 * side only; a client's registry arrives already filtered. See docs/ai/data-and-ownership.md.
 */
public final class SkinAvailability {

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    // Last reported hidden ids, so an identical result stays quiet.
    private static volatile List<String> lastReported = List.of();

    private static volatile int generation;

    private SkinAvailability() {
    }

    public static void invalidate() {
        CACHE.clear();
        generation++;
    }

    // Bumped on every invalidate; key filtered views on it.
    public static int generation() {
        return generation;
    }

    public static boolean isEnabled() {
        return MCPSkinsServerConfig.hideUnavailableSkins() && ServerLifecycleHooks.getCurrentServer() != null;
    }

    public static boolean isAvailable(SkinDataModels.WeaponSkins weapon) {
        return weapon != null && isAvailable(weapon.baseGun(), weapon.target());
    }

    public static boolean isSkinAvailable(String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkinRaw(skinId);
        return lookup != null && isAvailable(lookup.weapon());
    }

    private static boolean isAvailable(String baseId, SkinDataModels.SkinTarget target) {
        if (!isEnabled()) {
            return true;
        }
        String cacheKey = target + "|" + baseId;
        Boolean cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean available = lookUp(baseId, target);
        CACHE.put(cacheKey, available);
        return available;
    }

    private static boolean lookUp(String baseId, SkinDataModels.SkinTarget target) {
        ResourceLocation id = ResourceLocation.tryParse(TACZSkinHelper.bareSkinId(baseId));
        if (id == null) {
            return false;
        }
        SkinnableItemType type = SkinnableItemType.of(target);
        if (type != null && !type.modPresent()) {
            return false;
        }
        try {
            return switch (target) {
                case GUN -> TimelessAPI.getCommonGunIndex(id).isPresent();
                case ATTACHMENT -> TimelessAPI.getCommonAttachmentIndex(id).isPresent();
                case MELEE, THROWABLE, CONSUMABLE -> LrTacticalSupport.hasItem(target, id);
            };
        } catch (RuntimeException e) {
            // Fail open: a broken lookup must not hide every skin.
            MCPSkins.LOGGER.warn("[MCPSkins] Could not check '{}'; treating it as available.", baseId, e);
            return true;
        }
    }

    // Logs hidden ids only when they changed: the datapack sync hook also fires per login.
    public static void report(Map<String, SkinDataModels.WeaponSkins> registry) {
        if (!isEnabled()) {
            lastReported = List.of();
            return;
        }
        List<String> hidden = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            if (!isAvailable(weapon)) {
                hidden.add(weapon.baseGun());
            }
        }
        Collections.sort(hidden);
        if (hidden.equals(lastReported)) {
            return;
        }
        lastReported = List.copyOf(hidden);
        if (!hidden.isEmpty()) {
            MCPSkins.LOGGER.info("[MCPSkins] Hiding skins for {} item(s) TACZ has not loaded: {}. "
                            + "Install the matching gunpack, or set hideUnavailableSkins=false to show them anyway.",
                    hidden.size(), String.join(", ", hidden));
        }
    }
}
