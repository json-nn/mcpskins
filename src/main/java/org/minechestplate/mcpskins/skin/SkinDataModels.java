package org.minechestplate.mcpskins.skin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** Data types describing skins loaded by {@link SkinManager}. */
public class SkinDataModels {

    public static final String DEFAULT_RARITY_ID = "common";

    /**
     * A rarity tier, built in or datapack-defined.
     *
     * @param order       position in the ladder; built-ins are spaced 100 apart so custom tiers
     *                    can slot between them
     * @param fuseCost    overrides the server config's global cost, or null to use it
     * @param fuseTargets weighted targets, or empty to fuse into the next tier by order
     */
    public record Rarity(String id, String displayName, String translationKey, int accentColor,
                         int order, boolean fusable, Integer fuseCost, List<FuseTarget> fuseTargets) {

        public Rarity {
            fuseTargets = fuseTargets == null ? List.of() : List.copyOf(fuseTargets);
        }

        /** Resolved through {@code skin_lang}, then {@code translation_key}, then the literal name. */
        public MutableComponent label() {
            return styled(SkinTranslations.rarityName(this));
        }

        /** Server side, where the text is addressed to one player. */
        public MutableComponent label(String locale) {
            return styled(SkinTranslations.rarityName(locale, this));
        }

        private MutableComponent styled(String translated) {
            MutableComponent text;
            if (!translated.equals(displayName)) {
                text = Component.literal(translated);
            } else if (translationKey == null || translationKey.isBlank()) {
                text = Component.literal(displayName);
            } else {
                text = Component.translatable(translationKey);
            }
            return text.withStyle(style -> style.withColor(accentColor));
        }
    }

    public record FuseTarget(String rarityId, int weight) {}

    /**
     * A single skin definition.
     *
     * @param id                globally unique across every weapon and attachment
     * @param rarityId          resolved on read, never at load: rarities and skins are separate
     *                          reload listeners with no ordering between them
     * @param weight            relative likelihood of being rolled by a fuse against its tier-mates
     * @param lockedText        how the skin is earned, shown while locked. Descriptive only
     * @param unlockedByDefault granted to every player on first join
     */
    public record SkinEntry(String id, String name, int labelColor, String rarityId, String collection,
                            String description, boolean isNew, int weight, String lockedText,
                            boolean unlockedByDefault) {

        public boolean hasDescription() {
            return notBlank(description);
        }

        public boolean hasCollection() {
            return notBlank(collection);
        }

        public boolean hasLockedText() {
            return notBlank(lockedText);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public enum SkinTarget { GUN, ATTACHMENT }

    /** @param baseGun a GunId, or an AttachmentId when {@code target} is {@link SkinTarget#ATTACHMENT} */
    public record WeaponSkins(String baseGun, List<SkinEntry> skins, SkinTarget target) {

        public WeaponSkins(String baseGun, List<SkinEntry> skins) {
            this(baseGun, skins, SkinTarget.GUN);
        }

        public boolean isAttachment() {
            return target == SkinTarget.ATTACHMENT;
        }
    }

    public record SkinLookupResult(WeaponSkins weapon, SkinEntry skin) {}
}
