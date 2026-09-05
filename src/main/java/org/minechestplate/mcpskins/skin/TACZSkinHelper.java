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
 * Helper for creating and re-skinning TACZ item stacks, both guns and attachments.
 * <p>
 * The item's own {@code GunId} / {@code AttachmentId} is never swapped; it always matches the
 * physical item. Which skin is shown is controlled entirely by the separate
 * {@link SkinComponents#SKIN_ID} component, which
 * {@link org.minechestplate.mcpskins.mixin.TimelessAPIMixin} reads to swap in the matching
 * assets (see {@link org.minechestplate.mcpskins.client.render.SkinAssetResolver}).
 */
public class TACZSkinHelper {
    // Base item shared by all TACZ weapons
    public static final ResourceLocation TACZ_GUN_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");

    /** Base item shared by all TACZ attachments; the AttachmentId tag picks which one. */
    public static final ResourceLocation TACZ_ATTACHMENT_ITEM = ResourceLocation.parse("tacz:attachment");

    private static final String GUN_ID_TAG = "GunId";
    private static final String ATTACHMENT_ID_TAG = "AttachmentId";

    /**
     * Creates a display stack for the given gun ID, with no skin applied.
     */
    public static ItemStack createGunStack(String gunId) {
        return createGunStack(gunId, null);
    }

    /**
     * Creates a display stack with a skin applied, for UI previews (refit carousel,
     * skin browser, etc.) so the icon shows the re-skinned texture directly.
     *
     * @param gunId  raw GunId of the weapon (no "default:" prefix)
     * @param skinId skin id from the registry; a "default:" prefix or {@code null} means no skin
     */
    public static ItemStack createGunStack(String gunId, String skinId) {
        Item item = BuiltInRegistries.ITEM.get(TACZ_GUN_ITEM);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);

        CompoundTag tag = new CompoundTag();
        // Strip the prefix so TACZ's 3D renderer can find the model
        String actualGunId = bareSkinId(gunId);

        tag.putString("GunId", actualGunId);
        tag.putByte("HasBulletInBarrel", (byte) 1);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        applySkinComponent(stack, actualGunId, skinId);
        return stack;
    }

    /**
     * Applies a skin to a weapon stack, preserving attachments, ammo, and other
     * components. The stack's GunId is left untouched; only
     * {@link SkinComponents#SKIN_ID} changes.
     *
     * @param newSkinId skin id from the registry; "default:&lt;gunId&gt;" removes the skin
     */
    public static ItemStack applySkin(ItemStack originalWeapon, String newSkinId) {
        if (originalWeapon.isEmpty()) return originalWeapon;

        String baseId = getTaczId(originalWeapon);
        if (baseId == null) return originalWeapon;

        ItemStack skinnedWeapon = originalWeapon.copy();
        applySkinComponent(skinnedWeapon, baseId, newSkinId);
        return skinnedWeapon;
    }

    /** Display stack for an attachment, mirroring {@link #createGunStack}. */
    public static ItemStack createAttachmentStack(String attachmentId, String skinId) {
        Item item = BuiltInRegistries.ITEM.get(TACZ_ATTACHMENT_ITEM);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);
        String actualId = bareSkinId(attachmentId);

        CompoundTag tag = new CompoundTag();
        tag.putString(ATTACHMENT_ID_TAG, actualId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        applySkinComponent(stack, actualId, skinId);
        return stack;
    }

    /** Display stack for either kind of base item. */
    public static ItemStack createStack(String baseId, String skinId, SkinDataModels.SkinTarget target) {
        return target == SkinDataModels.SkinTarget.ATTACHMENT
                ? createAttachmentStack(baseId, skinId)
                : createGunStack(baseId, skinId);
    }

    /**
     * Shared write path for the skin component, used by both {@link #createGunStack}
     * and {@link #applySkin} so the "no skin" rule stays consistent between them.
     */
    private static void applySkinComponent(ItemStack stack, String baseGunId, String skinId) {
        String bare = skinId == null ? null : bareSkinId(skinId);
        if (bare == null || bare.isBlank() || bare.equals(baseGunId)) {
            stack.remove(SkinComponents.SKIN_ID.get());
        } else {
            stack.set(SkinComponents.SKIN_ID.get(), bare);
        }
    }

    /**
     * Raw GunId of a TACZ weapon stack, or {@code null} if it isn't one.
     * <p>
     * Runs several times per weapon per frame, so it reads the tag directly rather than via
     * {@link CustomData#copyTag()}, which deep-copies the whole compound to read one string.
     * {@code getUnsafe()} is deprecated but correct for a read we never mutate.
     */
    @SuppressWarnings("deprecation")
    public static String getGunId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains(GUN_ID_TAG)) return null;
        return data.getUnsafe().getString(GUN_ID_TAG);
    }

    /** Raw AttachmentId of a TACZ attachment stack, or {@code null} if it isn't one. */
    @SuppressWarnings("deprecation")
    public static String getAttachmentId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains(ATTACHMENT_ID_TAG)) return null;
        return data.getUnsafe().getString(ATTACHMENT_ID_TAG);
    }

    /** The skinnable TACZ id on a stack, gun or attachment, or {@code null} for anything else. */
    public static String getTaczId(ItemStack stack) {
        String gunId = getGunId(stack);
        return gunId != null ? gunId : getAttachmentId(stack);
    }

    /**
     * Read-only peek at a string in a stack's {@code CUSTOM_DATA}, or {@code null} if absent.
     * Same {@code copyTag()} reasoning as {@link #getGunId} - callers run per item render and
     * per inventory slot.
     */
    @SuppressWarnings("deprecation")
    public static String readCustomString(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains(key)) return null;
        return data.getUnsafe().getString(key);
    }

    /**
     * Raw ID of the currently applied skin, or {@code null} if the weapon is stock.
     */
    public static String getSkinId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String value = stack.get(SkinComponents.SKIN_ID.get());
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Strips the "default:" prefix from a skin or gun id, if present. */
    public static String bareSkinId(String id) {
        if (id == null) return null;
        return id.startsWith("default:") ? id.substring(8) : id;
    }

    /**
     * Localized weapon name from a raw GunId, safe to call server-side - unlike
     * {@code ItemStack#getHoverName()}, which needs client-only gun-pack display data.
     * <p>
     * Assumes TACZ's "&lt;namespace&gt;.gun.&lt;path&gt;.name" convention; worth spot-checking
     * against third-party gun packs.
     */
    public static Component gunDisplayName(String gunId) {
        String bare = bareSkinId(gunId);
        if (bare == null || bare.isBlank()) {
            return Component.literal(gunId == null ? "" : gunId);
        }
        ResourceLocation id = ResourceLocation.tryParse(bare);
        if (id == null) {
            // Not a valid "namespace:path" id - nothing sane to build a key from
            return Component.literal(bare);
        }
        return Component.translatable(id.getNamespace() + ".gun." + id.getPath());
    }
}