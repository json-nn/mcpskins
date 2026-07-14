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
 * ВАЖНО в свете перехода на текстурный оверлей (см. TACZSkinHelper/TimelessAPIMixin):
 * поле {@code "id"} в JSON-конфигах скинов - это одновременно (1) имя PNG-файла в
 * ресурспаке ({@code textures/skins/<base_gun>/<id>.png}) И (2) ключ разблокировки в
 * {@code SkinAttachment.UNLOCKED_SKINS}, который представляет собой ОБЩИЙ ДЛЯ ВСЕХ
 * ПУШЕК {@code Set<String>} без какой-либо привязки к оружию. Поэтому {@code id} должен
 * оставаться уникальным ГЛОБАЛЬНО, по всему модпаку, а не только в пределах одного
 * оружия - иначе скин "cobra" на m4a1 и скин "cobra" на ak47 будут восприниматься как
 * ОДИН И ТОТ ЖЕ разблокированный скин (а также ломается обратный поиск в
 * {@link #getBaseGun}, который иначе не сможет понять, какой из двух оружий имелся в
 * виду). Рекомендуемая схема именования: {@code <base_gun>_<skin_name>}, например
 * {@code "m4a1_cobra"} - и тогда файл в ресурспаке тоже должен называться
 * {@code textures/skins/m4a1/m4a1_cobra.png} (немного длиннее, чем просто "cobra.png",
 * зато без риска коллизии).
 * <p>
 * <b>НОВОЕ (для MCPSkins Armory, см. концепт §5/§8.1):</b> необязательные поля
 * {@code "rarity"}, {@code "collection"}, {@code "description"}, {@code "is_new"} в записи
 * скина. Все ПОЛНОСТЬЮ опциональны - если их нет в JSON, используются безопасные дефолты
 * ({@code common}, пустая строка, пустая строка, {@code false}) ровно тем же способом, каким
 * этот класс уже давно обрабатывает потенциально отсутствующие/некорректные записи (см.
 * try/catch вокруг парсинга каждого файла ниже) - существующие датапаки скинов, у которых
 * этих полей ещё нет, продолжают загружаться без единого предупреждения в логе.
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

                    // Все четыре поля ниже - НОВЫЕ и полностью опциональные (см. javadoc
                    // класса) - has(...) == false просто даёт дефолт, без предупреждения в
                    // лог: отсутствие этих полей в старых датапаках - ожидаемая, штатная
                    // ситуация, а не повод шуметь в консоль.
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
     * Ищет скин по его id сразу вместе с оружием (WeaponSkins), которому он
     * принадлежит - результат нужен и для тултипа, и для сообщения в чат, и для
     * команды выдачи предмета, поэтому логика поиска вынесена сюда, а не
     * продублирована в каждом месте по отдельности (как было раньше в
     * SkinUnlockItem: два идентичных двойных цикла).
     *
     * @return найденная пара (оружие, скин), либо {@code null}, если id не найден -
     *         например, опечатка в NBT предмета, либо скин был удалён из
     *         датапака после того, как предмет с ним уже был выдан игроку.
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
