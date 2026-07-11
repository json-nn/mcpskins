package org.minechestplate.mcpskins.skin;

import java.util.List;

public class SkinDataModels {
    public record SkinEntry(String id, String name, int labelColor) {}
    public record WeaponSkins(String baseGun, List<SkinEntry> skins) {}

    /**
     * Результат поиска скина по его id вместе с оружием (WeaponSkins), которому он
     * принадлежит. Раньше SkinUnlockItem дважды (в appendHoverText и в use()) содержал
     * буквально одинаковый двойной цикл "по всем WeaponSkins в registry -> по всем
     * SkinEntry внутри" - вынесли в {@link SkinManager#findSkin}, чтобы такая логика
     * поиска жила в одном месте и не расходилась при правках.
     */
    public record SkinLookupResult(WeaponSkins weapon, SkinEntry skin) {}
}