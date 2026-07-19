package org.minechestplate.mcpskins.skin;

import java.util.List;
import java.util.Locale;

/**
 * Data types describing skins loaded by {@link SkinManager}.
 */
public class SkinDataModels {

    /**
     * Skin rarity, used purely for filtering/sorting and decorative accents in the Armory
     * UI. Does not replace {@link SkinEntry#labelColor}, which remains the single source
     * of truth for the item's tint color everywhere else in the mod.
     */
    public enum Rarity {
        COMMON(0xB0B0B0),
        UNCOMMON(0x55FF55),
        RARE(0x5FD3FF),
        EPIC(0xB47FFF),
        LEGENDARY(0xFFB347);

        /** Accent color for the Armory UI (not used as an item tint). */
        public final int accentColor;

        Rarity(int accentColor) {
            this.accentColor = accentColor;
        }

        /**
         * Parses the {@code "rarity"} JSON/network field, falling back to {@link #COMMON}
         * for blank or unrecognized values.
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
     * A single skin definition.
     *
     * @param id          globally unique skin id (see {@link SkinManager} for the naming scheme)
     * @param name        display name
     * @param labelColor  the single source of truth for the skin's tint color
     * @param rarity      used for filtering/sorting in the Armory, defaults to {@link Rarity#COMMON}
     * @param collection  collection name for grouping in the Armory, empty means none
     * @param description short lore text shown in the Armory info panel, empty means none
     * @param isNew       shows a "NEW" badge in the Armory grid, defaults to {@code false}
     */
    public record SkinEntry(String id, String name, int labelColor, Rarity rarity, String collection,
                            String description, boolean isNew) {

        /** Legacy constructor for callers predating the Armory fields; fills safe defaults. */
        public SkinEntry(String id, String name, int labelColor) {
            this(id, name, labelColor, Rarity.COMMON, "", "", false);
        }

        /** Whether this skin has a non-blank description. */
        public boolean hasDescription() {
            return description != null && !description.isBlank();
        }

        /** Whether this skin belongs to a named collection. */
        public boolean hasCollection() {
            return collection != null && !collection.isBlank();
        }
    }

    public record WeaponSkins(String baseGun, List<SkinEntry> skins) {}

    /**
     * The result of a skin lookup: the matched skin together with the weapon it belongs to.
     */
    public record SkinLookupResult(WeaponSkins weapon, SkinEntry skin) {}
}
