package org.minechestplate.mcpskins.skin;

import com.google.gson.Gson;
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
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, SkinDataModels.WeaponSkins> registry = new HashMap<>();

    public SkinManager() {
        super(new GsonBuilder().create(), "skins");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManager, ProfilerFiller profilerIn) {
        registry.clear();
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
        MCPSkins.LOGGER.info("Loaded {} TACZ weapon skin configs.", registry.size());
    }

    public Map<String, SkinDataModels.WeaponSkins> getRegistry() {
        return registry;
    }

    public void syncFromNetwork(Map<String, SkinDataModels.WeaponSkins> networkData) {
        this.registry.clear();
        this.registry.putAll(networkData);
    }

    /**
     * Resolves the base gun ID for a given skin or gun ID.
     */
    public String getBaseGun(String skinOrGunId) {
        if (skinOrGunId == null) return "";

        String idToMatch = skinOrGunId.startsWith("default:") ? skinOrGunId.substring(8) : skinOrGunId;

        if (registry.containsKey(idToMatch)) {
            return idToMatch;
        }

        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            if (weapon.baseGun().equals(idToMatch)) return weapon.baseGun();
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                String skinActualId = skin.id().startsWith("default:") ? skin.id().substring(8) : skin.id();
                if (skinActualId.equals(idToMatch)) {
                    return weapon.baseGun();
                }
            }
        }
        return skinOrGunId;
    }

    /**
     * Finds a skin by ID along with the weapon it belongs to.
     *
     * @return the matching pair, or {@code null} if the id isn't found
     */
    public SkinDataModels.SkinLookupResult findSkin(String skinId) {
        if (skinId == null) return null;

        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.id().equals(skinId)) {
                    return new SkinDataModels.SkinLookupResult(weapon, skin);
                }
            }
        }
        return null;
    }

    /**
     * All real (non-"default:") skins of a given rarity, across every weapon.
     */
    public List<SkinDataModels.SkinLookupResult> getSkinsByRarity(SkinDataModels.Rarity rarity) {
        List<SkinDataModels.SkinLookupResult> list = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.id().startsWith("default:")) continue;
                if (skin.rarity() == rarity) {
                    list.add(new SkinDataModels.SkinLookupResult(weapon, skin));
                }
            }
        }
        return list;
    }

    /**
     * Returns all known skin IDs, used for command tab-completion.
     */
    public List<String> getAllSkinIds() {
        List<String> list = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                list.add(skin.id());
            }
        }
        return list;
    }
}