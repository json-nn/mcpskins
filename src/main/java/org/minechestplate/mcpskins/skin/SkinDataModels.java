package org.minechestplate.mcpskins.skin;

import java.util.List;

public class SkinDataModels {
    public record SkinEntry(String id, String name, int labelColor) {}
    public record WeaponSkins(String baseGun, List<SkinEntry> skins) {}
}