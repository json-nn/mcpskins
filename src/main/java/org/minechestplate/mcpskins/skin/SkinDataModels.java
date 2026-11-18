package org.minechestplate.mcpskins.skin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class SkinDataModels {

    public static final String DEFAULT_RARITY_ID = "common";

    // Built-in orders are 100 apart so custom tiers fit between. A null fuseCost uses the global config cost.
    public record Rarity(String id, String displayName, String translationKey, int accentColor,
                         int order, boolean fusable, Integer fuseCost, List<FuseTarget> fuseTargets) {

        public Rarity {
            fuseTargets = fuseTargets == null ? List.of() : List.copyOf(fuseTargets);
        }

        // Resolved through skin_lang, then translation_key, then the literal name.
        public MutableComponent label() {
            return styled(SkinTranslations.rarityName(this));
        }

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

    // key is the JSON and wire spelling.
    public enum LevelCounter {
        KILLS("kills"), HEADSHOTS("headshots"), PLAYER_KILLS("player_kills");

        private final String key;

        LevelCounter(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static LevelCounter byKey(String key) {
            for (LevelCounter counter : values()) {
                if (counter.key.equals(key)) return counter;
            }
            return null;
        }
    }

    // The root is level 1, so levels[i] is level i + 2. requires is never empty.
    public record SkinLevel(String variant, Map<LevelCounter, Integer> requires) {

        public static final int FIRST_LEVEL = 2;

        public SkinLevel {
            requires = requires == null || requires.isEmpty()
                    ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(requires));
        }

        public int required(LevelCounter counter) {
            return requires.getOrDefault(counter, 0);
        }
    }

    // rarityId is resolved on read, never at load: the rarity and skin reload listeners are unordered.
    // Level variants are not granted with the root.
    public record SkinEntry(String id, String name, int labelColor, String rarityId, String collection,
                            String description, boolean isNew, int weight, String lockedText,
                            boolean unlockedByDefault, String author, String subtitle, boolean limited,
                            List<String> variants, String rootId, List<SkinLevel> levels) {

        public SkinEntry {
            author = author == null ? "" : author;
            subtitle = subtitle == null ? "" : subtitle;
            rootId = rootId == null ? "" : rootId;
            variants = variants == null ? List.of() : List.copyOf(variants);
            levels = levels == null ? List.of() : List.copyOf(levels);
        }

        // No metadata, variants or levels, as for the synthesized stock entry.
        public SkinEntry(String id, String name, int labelColor, String rarityId, String collection,
                         String description, boolean isNew, int weight, String lockedText,
                         boolean unlockedByDefault) {
            this(id, name, labelColor, rarityId, collection, description, isNew, weight, lockedText,
                    unlockedByDefault, "", "", false, List.of(), "", List.of());
        }

        public boolean hasDescription() {
            return notBlank(description);
        }

        public boolean hasAuthor() {
            return notBlank(author);
        }

        public boolean hasSubtitle() {
            return notBlank(subtitle);
        }

        public boolean isVariant() {
            return notBlank(rootId);
        }

        public boolean hasVariants() {
            return !variants.isEmpty();
        }

        public boolean isLevelVariant(String variantId) {
            for (SkinLevel level : levels) {
                if (level.variant().equals(variantId)) return true;
            }
            return false;
        }

        public List<String> unconditionedVariants() {
            if (levels.isEmpty()) return variants;
            List<String> out = new ArrayList<>();
            for (String variant : variants) {
                if (!isLevelVariant(variant)) out.add(variant);
            }
            return out;
        }

        public int levelOf(String variantId) {
            for (int i = 0; i < levels.size(); i++) {
                if (levels.get(i).variant().equals(variantId)) return SkinLevel.FIRST_LEVEL + i;
            }
            return 0;
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

    // Item family a skin paints (SkinnableItemType). On the wire as an ordinal: append only.
    public enum SkinTarget { GUN, ATTACHMENT, MELEE, THROWABLE, CONSUMABLE }

    // baseGun is a GunId, or an AttachmentId when target is ATTACHMENT.
    public record WeaponSkins(String baseGun, List<SkinEntry> skins, SkinTarget target) {

        public WeaponSkins(String baseGun, List<SkinEntry> skins) {
            this(baseGun, skins, SkinTarget.GUN);
        }

        public boolean isAttachment() {
            return target == SkinTarget.ATTACHMENT;
        }

        public boolean isAccessory() {
            return target != SkinTarget.GUN;
        }
    }

    public record SkinLookupResult(WeaponSkins weapon, SkinEntry skin) {}
}
