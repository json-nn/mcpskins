package org.minechestplate.mcpskins.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;

public class SkinJsonStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String getBaseGunForSkin(String skinId) {
        if (SkinManager.INSTANCE.getRegistry().containsKey(skinId)) return skinId;
        for (SkinDataModels.WeaponSkins weapon : SkinManager.INSTANCE.getRegistry().values()) {
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.id().equals(skinId)) return weapon.baseGun();
            }
        }
        return skinId;
    }

    public static void savePlayerSkin(Player player, String skinId) {
        if (player.level().isClientSide()) return;

        String baseGun = getBaseGunForSkin(skinId);
        String safeBaseGun = baseGun.replace(":", "_");

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Используем UUID вместо никнейма
        String playerUUID = player.getUUID().toString();

        File rootDir = ServerLifecycleHooks.getCurrentServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        File playerDir = new File(rootDir, "mcpskins/" + playerUUID + "/skin");
        playerDir.mkdirs();

        File weaponFile = new File(playerDir, safeBaseGun + ".json");

        Set<String> existingSkins = new HashSet<>();
        if (weaponFile.exists()) {
            try (FileReader reader = new FileReader(weaponFile)) {
                existingSkins = GSON.fromJson(reader, new TypeToken<HashSet<String>>(){}.getType());
            } catch (Exception e) {
                MCPSkins.LOGGER.error("Не удалось прочитать JSON скинов для UUID {}", playerUUID, e);
            }
        }

        if (existingSkins == null) existingSkins = new HashSet<>();
        existingSkins.add(skinId);

        try (FileWriter writer = new FileWriter(weaponFile)) {
            GSON.toJson(existingSkins, writer);
        } catch (Exception e) {
            MCPSkins.LOGGER.error("Не удалось сохранить JSON скинов для UUID {}", playerUUID, e);
        }
    }
}