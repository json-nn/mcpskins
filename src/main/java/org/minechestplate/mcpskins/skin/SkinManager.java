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
import java.util.Map;

/**
 * Loads skin definitions from datapacks (JSON files under {@code data/../skins/}) and
 * exposes a lookup registry keyed by base gun ID.
 * <p>
 * A skin's {@code "id"} doubles as (1) its texture file name and (2) its unlock key in
 * {@link SkinAttachment#UNLOCKED_SKINS}, a set shared across all weapons. IDs must
 * therefore be globally unique, not just unique per weapon - the recommended scheme is
 * {@code <base_gun>_<skin_name>} (e.g. {@code "m4a1_cobra"}).
 * <p>
 * The {@code rarity}, {@code collection}, {@code description}, and {@code is_new} fields
 * are optional; datapacks that predate them still load cleanly with sane defaults.
 */
public class SkinManager extends SimpleJsonResourceReloadListener {
    public static final SkinManager INSTANCE = new SkinManager();

    /**
     * Everything the registry is looked up by, published as one immutable unit.
     * <p>
     * The two index maps exist because {@link #findSkin} and {@link #getBaseGun} used to scan
     * every skin of every weapon, and both are called from the render path (per weapon, per
     * frame) and from packet handlers. They're built once per load alongside the registry.
     *
     * @param registry     baseGun -&gt; that weapon's skins
     * @param skinsById    skin id -&gt; the (weapon, skin) pair it belongs to
     * @param baseGunById  skin id, with and without a {@code default:} prefix -&gt; owning baseGun
     */
    private record Snapshot(Map<String, SkinDataModels.WeaponSkins> registry,
                            Map<String, SkinDataModels.SkinLookupResult> skinsById,
                            Map<String, String> baseGunById) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of());
    }

    /**
     * Replaced wholesale, never mutated in place.
     * <p>
     * This was a plain {@link HashMap} written by {@link #apply} on the reload thread and by
     * {@link #syncFromNetwork} on the client main thread, and read from the render thread and
     * from packet handlers - with {@code clear()}-then-repopulate in the middle, so readers
     * could see a half-built registry. Worse, {@link #getRegistry()} handed the live map
     * straight to {@code SyncRegistryPayload}, which serializes it on a netty encode thread:
     * a {@code /reload} landing at the wrong moment was a {@link java.util.ConcurrentModificationException}
     * mid-packet. A volatile reference to an immutable snapshot makes every read consistent
     * and every publish atomic.
     */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public SkinManager() {
        super(new GsonBuilder().create(), "skins");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManager, ProfilerFiller profilerIn) {
        // Built locally, published atomically at the end - readers never see a partial load.
        Map<String, SkinDataModels.WeaponSkins> registry = new HashMap<>();
        objectIn.forEach((location, element) -> {
            try {
                JsonObject json = element.getAsJsonObject();
                String baseGun = json.get("base_gun").getAsString();

                List<SkinDataModels.SkinEntry> skins = new ArrayList<>();

                skins.add(new SkinDataModels.SkinEntry(
                        "default:" + baseGun, "Default", 0xFFFFFF,
                        SkinDataModels.Rarity.COMMON, "", "", false));

                json.getAsJsonArray("skins").forEach(skinElement -> {
                    JsonObject skinObj = skinElement.getAsJsonObject();
                    String id = skinObj.get("id").getAsString();
                    String name = skinObj.get("name").getAsString();
                    int color = Integer.decode(skinObj.get("label_color").getAsString());

                    // Optional fields - missing in older datapacks is expected, not an error
                    SkinDataModels.Rarity rarity = skinObj.has("rarity")
                            ? SkinDataModels.Rarity.byName(skinObj.get("rarity").getAsString())
                            : SkinDataModels.Rarity.COMMON;
                    String collection = skinObj.has("collection") ? skinObj.get("collection").getAsString() : "";
                    String description = skinObj.has("description") ? skinObj.get("description").getAsString() : "";
                    boolean isNew = skinObj.has("is_new") && skinObj.get("is_new").getAsBoolean();

                    skins.add(new SkinDataModels.SkinEntry(id, name, color, rarity, collection, description, isNew));
                });

                registry.put(baseGun, new SkinDataModels.WeaponSkins(baseGun, skins));
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

        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            baseGunById.put(weapon.baseGun(), weapon.baseGun());
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                skinsById.putIfAbsent(skin.id(), new SkinDataModels.SkinLookupResult(weapon, skin));
                // Indexed under both spellings so getBaseGun keeps resolving a "default:"
                // prefixed id the same way its old linear scan did.
                baseGunById.putIfAbsent(skin.id(), weapon.baseGun());
                baseGunById.putIfAbsent(TACZSkinHelper.bareSkinId(skin.id()), weapon.baseGun());
            }
        }

        snapshot = new Snapshot(Map.copyOf(registry), Map.copyOf(skinsById), Map.copyOf(baseGunById));
    }

    /**
     * The live registry. Safe to hand around and iterate: it's immutable, and a reload
     * publishes a whole new map rather than mutating this one.
     */
    public Map<String, SkinDataModels.WeaponSkins> getRegistry() {
        return snapshot.registry();
    }

    public void syncFromNetwork(Map<String, SkinDataModels.WeaponSkins> networkData) {
        publish(new HashMap<>(networkData));
    }

    /**
     * Resolves the base gun ID for a given skin or gun ID.
     */
    public String getBaseGun(String skinOrGunId) {
        if (skinOrGunId == null) return "";

        String idToMatch = TACZSkinHelper.bareSkinId(skinOrGunId);
        String baseGun = snapshot.baseGunById().get(idToMatch);
        // Unchanged fallback: an id we don't know is echoed back verbatim, prefix included.
        return baseGun != null ? baseGun : skinOrGunId;
    }

    /**
     * Finds a skin by ID along with the weapon it belongs to.
     *
     * @return the matching pair, or {@code null} if the id isn't found
     */
    public SkinDataModels.SkinLookupResult findSkin(String skinId) {
        if (skinId == null) return null;
        return snapshot.skinsById().get(skinId);
    }

    /**
     * All real (non-"default:") skins of a given rarity, across every weapon.
     */
    public List<SkinDataModels.SkinLookupResult> getSkinsByRarity(SkinDataModels.Rarity rarity) {
        List<SkinDataModels.SkinLookupResult> list = new ArrayList<>();
        for (SkinDataModels.SkinLookupResult result : snapshot.skinsById().values()) {
            if (SkinAttachment.isDefaultEntry(result.skin().id())) continue;
            if (result.skin().rarity() == rarity) {
                list.add(result);
            }
        }
        return list;
    }

    /**
     * Returns all known skin IDs, used for command tab-completion.
     */
    public List<String> getAllSkinIds() {
        List<String> list = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                list.add(skin.id());
            }
        }
        return list;
    }
}