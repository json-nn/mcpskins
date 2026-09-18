package org.minechestplate.mcpskins.skin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads skin definitions from {@code data/<namespace>/skins/} and exposes them by base item id.
 * A file targets one TACZ item through {@code base_gun} or {@code base_attachment}.
 * <p>
 * A skin's id doubles as its texture file name and its unlock key in
 * {@link SkinAttachment#UNLOCKED_SKINS}, a set shared across every item, so ids must be
 * globally unique rather than unique per weapon.
 */
public class SkinManager extends SimpleJsonResourceReloadListener {
    public static final SkinManager INSTANCE = new SkinManager();

    /**
     * Everything the registry is looked up by, published as one immutable unit.
     *
     * @param baseGunById skin id, with and without a {@code default:} prefix, to owning base id
     */
    private record Snapshot(Map<String, SkinDataModels.WeaponSkins> registry,
                            Map<String, SkinDataModels.SkinLookupResult> skinsById,
                            Map<String, String> baseGunById,
                            Set<String> defaultUnlockedIds) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Set.of());
    }

    /** Replaced wholesale, never mutated: written on reload and network threads, read on render. */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * Memoized availability-filtered registry. {@link #getRegistry()} sits on the refit overlay's
     * per-frame path, so it must not rebuild the map on every call.
     */
    private record FilteredView(Snapshot source, int generation, boolean filtering,
                                Map<String, SkinDataModels.WeaponSkins> registry) {
    }

    private volatile FilteredView filteredView;

    public SkinManager() {
        super(new GsonBuilder().create(), "skins");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManager, ProfilerFiller profilerIn) {
        Map<String, SkinDataModels.WeaponSkins> registry = new HashMap<>();
        objectIn.forEach((location, element) -> {
            try {
                JsonObject json = element.getAsJsonObject();

                boolean attachment = json.has("base_attachment");
                if (attachment && json.has("base_gun")) {
                    MCPSkins.LOGGER.error("Skin config {} sets both base_gun and base_attachment; skipping.", location);
                    return;
                }
                String baseGun = json.get(attachment ? "base_attachment" : "base_gun").getAsString();
                SkinDataModels.SkinTarget target = attachment
                        ? SkinDataModels.SkinTarget.ATTACHMENT
                        : SkinDataModels.SkinTarget.GUN;

                List<SkinDataModels.SkinEntry> skins = new ArrayList<>();

                skins.add(new SkinDataModels.SkinEntry(
                        "default:" + baseGun, "Default", 0xFFFFFF,
                        SkinDataModels.DEFAULT_RARITY_ID, "", "", false, 1, "", true));

                json.getAsJsonArray("skins").forEach(skinElement -> {
                    JsonObject skinObj = skinElement.getAsJsonObject();
                    String id = skinObj.get("id").getAsString();
                    String name = skinObj.get("name").getAsString();
                    int color = Integer.decode(skinObj.get("label_color").getAsString());

                    String rarityId = skinObj.has("rarity")
                            ? skinObj.get("rarity").getAsString().trim().toLowerCase(Locale.ROOT)
                            : SkinDataModels.DEFAULT_RARITY_ID;
                    String collection = skinObj.has("collection") ? skinObj.get("collection").getAsString() : "";
                    String description = skinObj.has("description") ? skinObj.get("description").getAsString() : "";
                    boolean isNew = skinObj.has("is_new") && skinObj.get("is_new").getAsBoolean();
                    int weight = skinObj.has("weight") ? Math.max(1, skinObj.get("weight").getAsInt()) : 1;
                    String lockedText = skinObj.has("locked_text")
                            ? skinObj.get("locked_text").getAsString() : "";
                    boolean unlocked = skinObj.has("unlocked") && skinObj.get("unlocked").getAsBoolean();

                    skins.add(new SkinDataModels.SkinEntry(id, name, color, rarityId, collection,
                            description, isNew, weight, lockedText, unlocked));
                });

                SkinDataModels.WeaponSkins previous =
                        registry.put(baseGun, new SkinDataModels.WeaponSkins(baseGun, skins, target));
                if (previous != null) {
                    MCPSkins.LOGGER.warn("Two skin configs both target '{}'; {} wins.", baseGun, location);
                }
            } catch (Exception e) {
                MCPSkins.LOGGER.error("Failed to parse TACZ skin config: {}", location, e);
            }
        });
        publish(registry);
        MCPSkins.LOGGER.info("Loaded {} TACZ weapon skin configs.", registry.size());
    }

    /** Builds the lookup indices and publishes the new state as one atomic replacement. */
    private void publish(Map<String, SkinDataModels.WeaponSkins> registry) {
        Map<String, SkinDataModels.SkinLookupResult> skinsById = new HashMap<>();
        Map<String, String> baseGunById = new HashMap<>();
        Set<String> defaultUnlocked = new HashSet<>();

        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            baseGunById.put(weapon.baseGun(), weapon.baseGun());
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.unlockedByDefault() && !SkinAttachment.isDefaultEntry(skin.id())) {
                    defaultUnlocked.add(skin.id());
                }
                skinsById.putIfAbsent(skin.id(), new SkinDataModels.SkinLookupResult(weapon, skin));
                // Both spellings, so getBaseGun still resolves a "default:" prefixed id.
                baseGunById.putIfAbsent(skin.id(), weapon.baseGun());
                baseGunById.putIfAbsent(TACZSkinHelper.bareSkinId(skin.id()), weapon.baseGun());
            }
        }

        snapshot = new Snapshot(Map.copyOf(registry), Map.copyOf(skinsById), Map.copyOf(baseGunById),
                Set.copyOf(defaultUnlocked));
    }

    /** Computed once per reload rather than scanned per player, then filtered on availability. */
    public Set<String> getDefaultUnlockedIds() {
        Set<String> ids = snapshot.defaultUnlockedIds();
        if (!SkinAvailability.isEnabled()) {
            return ids;
        }
        Set<String> available = new HashSet<>();
        for (String skinId : ids) {
            if (SkinAvailability.isSkinAvailable(skinId)) {
                available.add(skinId);
            }
        }
        return available;
    }

    /** Whether a base id names an attachment. Unknown ids read as guns. */
    public SkinDataModels.SkinTarget targetOf(String baseId) {
        SkinDataModels.WeaponSkins weapon = snapshot.registry().get(TACZSkinHelper.bareSkinId(baseId));
        return weapon == null ? SkinDataModels.SkinTarget.GUN : weapon.target();
    }

    /**
     * Immutable - safe to hand around and iterate. A reload publishes a new map.
     * <p>
     * Items TACZ has not loaded are left out, so this is what gets synced to clients and what the
     * UIs read. A client holds the already filtered copy it was sent.
     */
    public Map<String, SkinDataModels.WeaponSkins> getRegistry() {
        Snapshot current = snapshot;
        boolean filtering = SkinAvailability.isEnabled();
        int generation = SkinAvailability.generation();

        FilteredView cached = filteredView;
        if (cached != null && cached.source() == current
                && cached.generation() == generation && cached.filtering() == filtering) {
            return cached.registry();
        }

        Map<String, SkinDataModels.WeaponSkins> registry = current.registry();
        if (filtering) {
            Map<String, SkinDataModels.WeaponSkins> available = new HashMap<>();
            registry.forEach((baseId, weapon) -> {
                if (SkinAvailability.isAvailable(weapon)) {
                    available.put(baseId, weapon);
                }
            });
            registry = Map.copyOf(available);
        }
        filteredView = new FilteredView(current, generation, filtering, registry);
        return registry;
    }

    /** The registry exactly as parsed, availability ignored. */
    public Map<String, SkinDataModels.WeaponSkins> getRawRegistry() {
        return snapshot.registry();
    }

    public void syncFromNetwork(Map<String, SkinDataModels.WeaponSkins> networkData) {
        publish(new HashMap<>(networkData));
    }

    /** Base item id for a skin or item id. An unknown id is echoed back verbatim. */
    public String getBaseGun(String skinOrGunId) {
        if (skinOrGunId == null) return "";

        String idToMatch = TACZSkinHelper.bareSkinId(skinOrGunId);
        String baseGun = snapshot.baseGunById().get(idToMatch);
        return baseGun != null ? baseGun : skinOrGunId;
    }

    /** @return the skin with the item it belongs to, or null if the id is unknown */
    public SkinDataModels.SkinLookupResult findSkin(String skinId) {
        SkinDataModels.SkinLookupResult lookup = findSkinRaw(skinId);
        return lookup != null && SkinAvailability.isAvailable(lookup.weapon()) ? lookup : null;
    }

    /**
     * Ignores availability. Only for callers that must tell an id no pack declares from one whose
     * weapon simply is not installed; everything else wants {@link #findSkin}.
     */
    public SkinDataModels.SkinLookupResult findSkinRaw(String skinId) {
        if (skinId == null) return null;
        return snapshot.skinsById().get(skinId);
    }

    /**
     * Matched on the raw rarity id, so a skin pointing at an undefined tier stays in its own
     * group rather than silently joining the fallback tier's fuse pool.
     */
    public List<SkinDataModels.SkinLookupResult> getSkinsByRarity(String rarityId) {
        List<SkinDataModels.SkinLookupResult> list = new ArrayList<>();
        for (SkinDataModels.SkinLookupResult result : snapshot.skinsById().values()) {
            if (SkinAttachment.isDefaultEntry(result.skin().id())) continue;
            if (!SkinAvailability.isAvailable(result.weapon())) continue;
            if (result.skin().rarityId().equals(rarityId)) {
                list.add(result);
            }
        }
        return list;
    }

    /** Every non-empty collection name in load order. Matched exactly, since they are free text. */
    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.hasCollection() && !names.contains(skin.collection())) {
                    names.add(skin.collection());
                }
            }
        }
        return names;
    }

    /** Real skin ids in a collection, stock entries excluded. Empty when nothing matches. */
    public List<String> getSkinIdsInCollection(String collection) {
        List<String> ids = new ArrayList<>();
        if (collection == null || collection.isBlank()) return ids;
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (!SkinAttachment.isDefaultEntry(skin.id()) && collection.equals(skin.collection())) {
                    ids.add(skin.id());
                }
            }
        }
        return ids;
    }

    /** For command tab-completion. Skins for items TACZ has not loaded are left out. */
    public List<String> getAllSkinIds() {
        List<String> list = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                list.add(skin.id());
            }
        }
        return list;
    }
}