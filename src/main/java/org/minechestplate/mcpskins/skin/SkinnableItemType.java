package org.minechestplate.mcpskins.skin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.function.Predicate;

/**
 * One family of skinnable item, described as data rather than hardcoded.
 * <p>
 * TACZ and its addons all share the same shape: a single registered "container" item whose real
 * identity lives in a {@code custom_data} tag. A TACZ rifle is {@code tacz:modern_kinetic_gun}
 * carrying {@code GunId}, and a LesRaisins knife is {@code lrtactical:melee} carrying
 * {@code MeleeWeaponId}. Describing that pair once is what lets the rest of the mod treat every
 * addon the same way.
 *
 * @param target    how the family is identified on the wire and in skin files
 * @param jsonKey   the key a skin pack uses to point at this family
 * @param ownerMod  the mod that must be installed for this family to exist
 * @param itemId    the registered container item
 * @param idTag     the {@code custom_data} tag holding the real id
 * @param extraTags written alongside {@code idTag} when this mod builds a stack from scratch
 */
public record SkinnableItemType(SkinDataModels.SkinTarget target, String jsonKey, String ownerMod,
                                ResourceLocation itemId, String idTag, List<ExtraTag> extraTags) {

    /** A fixed tag a freshly built stack needs in order to behave like a real one. */
    public record ExtraTag(String key, byte value) {
    }

    private static final List<SkinnableItemType> ALL = List.of(
            new SkinnableItemType(SkinDataModels.SkinTarget.GUN, "base_gun", "tacz",
                    ResourceLocation.parse("tacz:modern_kinetic_gun"), "GunId",
                    // Without this a built gun renders mid-reload.
                    List.of(new ExtraTag("HasBulletInBarrel", (byte) 1))),
            new SkinnableItemType(SkinDataModels.SkinTarget.ATTACHMENT, "base_attachment", "tacz",
                    ResourceLocation.parse("tacz:attachment"), "AttachmentId", List.of()),
            new SkinnableItemType(SkinDataModels.SkinTarget.MELEE, "base_melee", "lrtactical",
                    ResourceLocation.parse("lrtactical:melee"), "MeleeWeaponId", List.of()),
            new SkinnableItemType(SkinDataModels.SkinTarget.THROWABLE, "base_throwable", "lrtactical",
                    ResourceLocation.parse("lrtactical:throwable"), "ThrowableId", List.of()),
            new SkinnableItemType(SkinDataModels.SkinTarget.CONSUMABLE, "base_consumable", "lrtactical",
                    ResourceLocation.parse("lrtactical:consumable"), "ConsumableId", List.of()));

    /** Every family, whether or not the mod providing it is installed. */
    public static List<SkinnableItemType> all() {
        return ALL;
    }

    /** Whether the mod that provides this family is present in this instance. */
    public boolean modPresent() {
        return ModList.get().isLoaded(ownerMod);
    }

    public static SkinnableItemType of(SkinDataModels.SkinTarget target) {
        return find(type -> type.target() == target);
    }

    /** The family a skin file's key names, or null when the key is not one of ours. */
    public static SkinnableItemType byJsonKey(String key) {
        return find(type -> type.jsonKey().equals(key));
    }

    /** The family a container item belongs to, or null for anything else. */
    public static SkinnableItemType byItemId(ResourceLocation itemId) {
        return find(type -> type.itemId().equals(itemId));
    }

    /** Applies this family's identity to a fresh tag. */
    public CompoundTag writeTo(CompoundTag tag, String baseId) {
        tag.putString(idTag, baseId);
        for (ExtraTag extra : extraTags) {
            tag.putByte(extra.key(), extra.value());
        }
        return tag;
    }

    private static SkinnableItemType find(Predicate<SkinnableItemType> test) {
        for (SkinnableItemType type : ALL) {
            if (test.test(type)) {
                return type;
            }
        }
        return null;
    }
}
