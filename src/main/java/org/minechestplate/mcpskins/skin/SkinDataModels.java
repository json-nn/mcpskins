package org.minechestplate.mcpskins.skin;

import java.util.List;
import java.util.Locale;

public class SkinDataModels {

    /**
     * Редкость скина - НОВОЕ поле (см. концепт "MCPSkins Armory", §5 "Чего не хватает в
     * данных"). Используется ТОЛЬКО для фильтра/сортировки в {@code SkinArmoryScreen} и для
     * необязательной decorative-подсветки в новом экране.
     * <p>
     * <b>Важно:</b> это НЕ заменяет существующее поле {@code labelColor} у {@link SkinEntry} -
     * оно остаётся единственным источником правды для цвета, который уже используется
     * повсеместно (карусель рефита, тултип разблокирующего предмета, чат-сообщение, цвет
     * предмета через {@code ClientModEvents#registerItemColors}). Авто-вывод цвета из редкости,
     * который предлагался как опция в концепте, сознательно НЕ реализован - он сломал бы
     * визуал уже существующих скинов, у которых {@code label_color} задан вручную и осознанно
     * автором датапака. {@code rarity} живёт независимо и влияет только на новые фичи
     * (фильтр/сортировка/акцентная рамка в Armory).
     */
    public enum Rarity {
        COMMON(0xB0B0B0),
        UNCOMMON(0x55FF55),
        RARE(0x5FD3FF),
        EPIC(0xB47FFF),
        LEGENDARY(0xFFB347);

        /** Резервный акцентный цвет для UI Armory-экрана (НЕ используется как labelColor). */
        public final int accentColor;

        Rarity(int accentColor) {
            this.accentColor = accentColor;
        }

        /**
         * Разбор значения поля {@code "rarity"} из JSON/сети с тихим фолбэком на
         * {@link #COMMON} для пустых/неизвестных/опечатанных значений - тот же принцип
         * "не найдено - тихий дефолт, не краш", что уже используется по всему проекту
         * (см. {@code SkinAssetResolver}/{@code GunModelPatcher}).
         */
        public static Rarity byName(String raw) {
            if (raw == null || raw.isBlank()) return COMMON;
            try {
                return Rarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return COMMON;
            }
        }
    }

    /**
     * @param id          глобально уникальный id скина (см. javadoc {@link SkinManager} про
     *                    формат {@code <base_gun>_<skin_name>})
     * @param name        отображаемое имя скина
     * @param labelColor  ЕДИНСТВЕННЫЙ источник правды для цвета (см. javadoc {@link Rarity})
     * @param rarity      НОВОЕ - редкость для фильтра/сортировки в Armory, по умолчанию
     *                    {@link Rarity#COMMON}
     * @param collection  НОВОЕ - имя коллекции (например "Jungle Ops") для группировки/фильтра
     *                    в Armory, по умолчанию пустая строка (значит "без коллекции")
     * @param description НОВОЕ - короткая лорная строка под инфо-панелью Armory, по умолчанию
     *                    пустая строка (значит "нет описания", инфо-панель просто не покажет
     *                    эту строку)
     * @param isNew       НОВОЕ - бейдж "NEW" в сетке Armory, по умолчанию {@code false}
     */
    public record SkinEntry(String id, String name, int labelColor, Rarity rarity, String collection,
                            String description, boolean isNew) {

        /**
         * Совместимость с существующим кодом/вызовами (старый 3-аргументный конструктор,
         * который использовался до появления Armory) - все новые поля получают безопасные
         * дефолты, ровно как того требует §5 концепта ("Все поля - с дефолтами, чтобы
         * существующие датапаки скинов не сломались").
         */
        public SkinEntry(String id, String name, int labelColor) {
            this(id, name, labelColor, Rarity.COMMON, "", "", false);
        }

        /** {@code true}, если у скина есть непустая лорная строка для инфо-панели. */
        public boolean hasDescription() {
            return description != null && !description.isBlank();
        }

        /** {@code true}, если скин относится к именованной коллекции. */
        public boolean hasCollection() {
            return collection != null && !collection.isBlank();
        }
    }

    public record WeaponSkins(String baseGun, List<SkinEntry> skins) {}

    /**
     * Результат поиска скина по его id вместе с оружием (WeaponSkins), которому он
     * принадлежит. Раньше SkinUnlockItem дважды (в appendHoverText и в use()) содержал
     * буквально одинаковый двойной цикл "по всем WeaponSkins в registry -> по всем
     * SkinEntry внутри" - вынесли в {@link SkinManager#findSkin}, чтобы такая логика
     * поиска жила в одном месте и не расходилась при правках.
     * <p>
     * ПЕРЕИСПОЛЬЗУЕТСЯ в {@code SkinArmoryScreen} для глобального поиска по всем
     * оружиям сразу (см. его javadoc) - удобно, что пара (оружие, скин) уже есть готовым
     * типом, а не кортежем/массивом.
     */
    public record SkinLookupResult(WeaponSkins weapon, SkinEntry skin) {}
}
