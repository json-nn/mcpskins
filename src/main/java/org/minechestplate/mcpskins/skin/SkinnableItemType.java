package org.minechestplate.mcpskins.skin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.function.Predicate;

/**
 * A skinnable family: one container item whose real id is a {@code custom_data} tag (e.g.
 * {@code tacz:modern_kinetic_gun} with {@code GunId}). Adding an addon's family is one entry in {@link #ALL}.
 *
 * @param jsonKey   the {@code base_*} key a skin file uses
 * @param ownerMod  the mod that must be installed for this family to exist
 * @param itemId    the registered container item
 * @param idTag     the {@code custom_data} tag holding the real id
 * @param extraTags written alongside {@code idTag} when building a stack from scratch
 */
public record SkinnableItemType(SkinDataModels.SkinTarget target, String jsonKey, String ownerMod,
                                ResourceLocation itemId, String idTag, List<ExtraTag> extraTags) {

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

    public static List<SkinnableItemType> all() {
        return ALL;
    }

    public boolean modPresent() {
        return ModList.get().isLoaded(ownerMod);
    }

    public static SkinnableItemType of(SkinDataModels.SkinTarget target) {
        return find(type -> type.target() == target);
    }

    public static SkinnableItemType byJsonKey(String key) {
        return find(type -> type.jsonKey().equals(key));
    }

    public static SkinnableItemType byItemId(ResourceLocation itemId) {
        return find(type -> type.itemId().equals(itemId));
    }

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
