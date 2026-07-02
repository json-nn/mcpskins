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

                skins.add(new SkinDataModels.SkinEntry("default:" + baseGun, "Default", 0xFFFFFF));

                json.getAsJsonArray("skins").forEach(skinElement -> {
                    JsonObject skinObj = skinElement.getAsJsonObject();
                    String id = skinObj.get("id").getAsString();
                    String name = skinObj.get("name").getAsString();
                    int color = Integer.decode(skinObj.get("label_color").getAsString());
                    skins.add(new SkinDataModels.SkinEntry(id, name, color));
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
     * Возвращает base_gun для переданного ID (скина или самой пушки).
     * Разбирает конфликты тегов при смене скинов.
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
     * Возвращает полный список ID скинов для автоподстановки в командах.
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