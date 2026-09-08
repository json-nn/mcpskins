package org.minechestplate.mcpskins.skin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Creates and re-skins TACZ item stacks, guns and attachments alike.
 * <p>
 * An item's own {@code GunId} / {@code AttachmentId} is never swapped. Which skin is shown comes
 * entirely from the separate {@link SkinComponents#SKIN_ID} component, which
 * {@link org.minechestplate.mcpskins.mixin.TimelessAPIMixin} reads to swap in the matching assets.
 */
public class TACZSkinHelper {

    public static final ResourceLocation TACZ_GUN_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");
    public static final ResourceLocation TACZ_ATTACHMENT_ITEM = ResourceLocation.parse("tacz:attachment");

    private static final String GUN_ID_TAG = "GunId";
    private static final String ATTACHMENT_ID_TAG = "AttachmentId";
    private static final String DEFAULT_PREFIX = "default:";

    public static ItemStack createGunStack(String gunId) {
        return createGunStack(gunId, null);
    }

    /** @param skinId registry id; a {@code default:} prefix or null means no skin */
    public static ItemStack createGunStack(String gunId, String skinId) {
        String baseId = bareSkinId(gunId);
        CompoundTag tag = new CompoundTag();
        tag.putString(GUN_ID_TAG, baseId);
        tag.putByte("HasBulletInBarrel", (byte) 1);
        return createStack(TACZ_GUN_ITEM, tag, baseId, skinId);
    }

    public static ItemStack createAttachmentStack(String attachmentId, String skinId) {
        String baseId = bareSkinId(attachmentId);
        CompoundTag tag = new CompoundTag();
        tag.putString(ATTACHMENT_ID_TAG, baseId);
        return createStack(TACZ_ATTACHMENT_ITEM, tag, baseId, skinId);
    }

    public static ItemStack createStack(String baseId, String skinId, SkinDataModels.SkinTarget target) {
        return target == SkinDataModels.SkinTarget.ATTACHMENT
                ? createAttachmentStack(baseId, skinId)
                : createGunStack(baseId, skinId);
    }

    private static ItemStack createStack(ResourceLocation itemId, CompoundTag tag, String baseId, String skinId) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        applySkinComponent(stack, baseId, skinId);
        return stack;
    }

    /** Preserves attachments, ammo and every other component; only the skin component changes. */
    public static ItemStack applySkin(ItemStack original, String newSkinId) {
        if (original.isEmpty()) return original;

        String baseId = getTaczId(original);
        if (baseId == null) return original;

        ItemStack skinned = original.copy();
        applySkinComponent(skinned, baseId, newSkinId);
        return skinned;
    }

    private static void applySkinComponent(ItemStack stack, String baseId, String skinId) {
        String bare = bareSkinId(skinId);
        if (bare == null || bare.isBlank() || bare.equals(baseId)) {
            stack.remove(SkinComponents.SKIN_ID.get());
        } else {
            stack.set(SkinComponents.SKIN_ID.get(), bare);
        }
    }

    public static String getGunId(ItemStack stack) {
        return readCustomString(stack, GUN_ID_TAG);
    }

    public static String getAttachmentId(ItemStack stack) {
        return readCustomString(stack, ATTACHMENT_ID_TAG);
    }

    /** The skinnable TACZ id on a stack, or null for anything else. */
    public static String getTaczId(ItemStack stack) {
        String gunId = getGunId(stack);
        return gunId != null ? gunId : getAttachmentId(stack);
    }

    /**
     * Reads the tag directly rather than through {@link CustomData#copyTag()}, which deep-copies
     * the whole compound to read one string. Callers run per item render and per inventory slot.
     */
    @SuppressWarnings("deprecation")
    public static String readCustomString(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return data.contains(key) ? data.getUnsafe().getString(key) : null;
    }

    /** Null when the item is stock. */
    public static String getSkinId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String value = stack.get(SkinComponents.SKIN_ID.get());
        return value == null || value.isBlank() ? null : value;
    }

    public static String bareSkinId(String id) {
        if (id == null) return null;
        return id.startsWith(DEFAULT_PREFIX) ? id.substring(DEFAULT_PREFIX.length()) : id;
    }

    /**
     * Safe server-side, unlike {@code ItemStack#getHoverName()}, which needs client-only gun-pack
     * display data. Assumes TACZ's {@code <namespace>.gun.<path>} key convention.
     */
    public static Component gunDisplayName(String gunId) {
        String bare = bareSkinId(gunId);
        if (bare == null || bare.isBlank()) {
            return Component.literal(gunId == null ? "" : gunId);
        }
        ResourceLocation id = ResourceLocation.tryParse(bare);
        return id == null
                ? Component.literal(bare)
                : Component.translatable(id.getNamespace() + ".gun." + id.getPath());
    }
}
