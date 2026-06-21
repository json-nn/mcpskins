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
import org.minechestplate.mcpskins.skin.SkinDataModels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkinManager extends SimpleJsonResourceReloadListener {
    public static final SkinManager INSTANCE = new SkinManager();
    private static final Gson GSON = new GsonBuilder().create();

    // Теперь ключ - это String
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
                // Читаем ID как строки!
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
}