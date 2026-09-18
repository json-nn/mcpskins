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
 * Hides skins whose base gun or attachment no gunpack actually provides, so a skin for a weapon
 * that is not installed never reaches a UI, a fuse pool, or {@code /mcpskins give all}.
 * <p>
 * <b>Why this is lazy rather than done at load.</b> TACZ rebuilds its common index through
 * {@code AddReloadListenerEvent}, and that handler swaps {@code CommonAssetsManager.INSTANCE} for a
 * brand new empty manager <em>before</em> registering the listeners that fill it. Every gun
 * therefore reads as missing for the whole reload, whatever order the listeners run in, so asking
 * during {@link SkinManager}'s own apply would hide the entire registry every time. By the first
 * query after a reload the index is populated, so the answer is computed then and memoized.
 * <p>
 * Server side only. A remote client has no common index of its own worth trusting, and the
 * registry it was sent was already filtered, so the check there always answers "available".
 */
public final class SkinAvailability {

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    /** Base ids reported hidden last time, so a repeat reload with the same result stays quiet. */
    private static volatile List<String> lastReported = List.of();

    /** Bumped on every invalidate, so cached filtered views know to rebuild. */
    private static volatile int generation;

    private SkinAvailability() {
    }

    /** Called when the skin registry or the gunpack set may have changed. */
    public static void invalidate() {
        CACHE.clear();
        generation++;
    }

    /** Cache key for anything holding a filtered view of the registry. */
    public static int generation() {
        return generation;
    }

    public static boolean isEnabled() {
        return MCPSkinsServerConfig.hideUnavailableSkins() && ServerLifecycleHooks.getCurrentServer() != null;
    }

    /** Whether TACZ has the gun or attachment this skin set is painted on. */
    public static boolean isAvailable(SkinDataModels.WeaponSkins weapon) {
        return weapon != null && isAvailable(weapon.baseGun(), weapon.isAttachment());
    }

    /** Whether the item a skin id belongs to is loaded. Unknown skin ids read as unavailable. */
    public static boolean isSkinAvailable(String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkinRaw(skinId);
        return lookup != null && isAvailable(lookup.weapon());
    }

    private static boolean isAvailable(String baseId, boolean attachment) {
        if (!isEnabled()) {
            return true;
        }
        Boolean cached = CACHE.get(baseId);
        if (cached != null) {
            return cached;
        }
        boolean available = lookUp(baseId, attachment);
        CACHE.put(baseId, available);
        return available;
    }

    private static boolean lookUp(String baseId, boolean attachment) {
        ResourceLocation id = ResourceLocation.tryParse(TACZSkinHelper.bareSkinId(baseId));
        if (id == null) {
            return false;
        }
        try {
            return attachment
                    ? TimelessAPI.getCommonAttachmentIndex(id).isPresent()
                    : TimelessAPI.getCommonGunIndex(id).isPresent();
        } catch (RuntimeException e) {
            // Fail open: a broken lookup must not blank out every skin on the server.
            MCPSkins.LOGGER.warn("[MCPSkins] Could not ask TACZ about '{}'; treating it as available.", baseId, e);
            return true;
        }
    }

    /**
     * Logs one line naming the base ids being hidden, and nothing at all when the answer is the
     * same as last time. The dedup is required, not just tidy: the only safe place to call this is
     * the datapack sync hook, which also fires once per player login.
     */
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
