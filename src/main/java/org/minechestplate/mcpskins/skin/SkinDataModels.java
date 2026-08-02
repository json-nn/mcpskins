package org.minechestplate.mcpskins.skin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Data types describing skins loaded by {@link SkinManager}.
 */
public class SkinDataModels {

    /** Rarity assumed when a skin declares none. Always exists - see {@link RarityManager}. */
    public static final String DEFAULT_RARITY_ID = "common";

    /**
     * A rarity tier, defined either as one of {@link RarityManager}'s built-ins or by a
     * datapack. Used for filtering/sorting and accent colors in the Armory, and for the fuse
     * ladder in {@code SkinUnlockItem}. Does not replace {@link SkinEntry#labelColor}, which
     * remains the source of truth for the item's tint.
     *
     * @param order      position in the ladder; the built-ins are spaced 100 apart so custom
     *                   tiers can slot between them
     * @param fusable    false excludes this tier from fusing in both directions
     * @param fuseCost   overrides the server config's global cost, or null to use it
     * @param fuseTargets explicit weighted targets, or empty to fuse into the next tier by order
     */
    public record Rarity(String id, String displayName, String translationKey, int accentColor,
                         int order, boolean fusable, Integer fuseCost, List<FuseTarget> fuseTargets) {

        public Rarity {
            fuseTargets = fuseTargets == null ? List.of() : List.copyOf(fuseTargets);
        }

        /** Localized where a translation key was given, literal otherwise, always accent-colored. */
        public MutableComponent label() {
            MutableComponent text = translationKey == null || translationKey.isBlank()
                    ? Component.literal(displayName)
                    : Component.translatable(translationKey);
            return text.withStyle(style -> style.withColor(accentColor));
        }
    }

    /** One possible outcome of fusing, weighted against the other targets of the same rarity. */
    public record FuseTarget(String rarityId, int weight) {}

    /**
     * A single skin definition.
     *
     * @param id          globally unique skin id (see {@link SkinManager} for the naming scheme)
     * @param name        display name
     * @param labelColor  the single source of truth for the skin's tint color
     * @param rarityId    resolved through {@link RarityManager}, never stored resolved - the two
     *                    datapack folders load without a guaranteed order between them
     * @param collection  collection name for grouping in the Armory, empty means none
     * @param description short lore text shown in the Armory info panel, empty means none
     * @param isNew       shows a "NEW" badge in the Armory grid, defaults to {@code false}
     * @param weight      relative likelihood of being rolled by a fuse against its tier-mates;
     *                    higher is more common, defaults to 1
     */
    public record SkinEntry(String id, String name, int labelColor, String rarityId, String collection,
                            String description, boolean isNew, int weight) {

        /** Legacy constructor for callers predating the Armory fields; fills safe defaults. */
        public SkinEntry(String id, String name, int labelColor) {
            this(id, name, labelColor, DEFAULT_RARITY_ID, "", "", false, 1);
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
