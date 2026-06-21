package org.minechestplate.mcpskins.skin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public class TACZSkinHelper {
    // Базовый предмет для ВСЕХ пушек в TACZ
    public static final ResourceLocation TACZ_GUN_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");

    /**
     * Создает предмет для отображения в GUI на основе GunId
     */
    public static ItemStack createGunStack(String gunId) {
        Item item = BuiltInRegistries.ITEM.get(TACZ_GUN_ITEM);
        if (item == null) return ItemStack.EMPTY;

        ItemStack stack = new ItemStack(item);

        CompoundTag tag = new CompoundTag();
        // Отрезаем префикс, если он есть, чтобы 3D рендер TACZ нашел модель
        String actualId = gunId.startsWith("default:") ? gunId.substring(8) : gunId;

        tag.putString("GunId", actualId);
        tag.putByte("HasBulletInBarrel", (byte) 1);

        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /**
     * Заменяет скин в руках игрока, СОХРАНЯЯ все обвесы и патроны.
     */
    public static ItemStack applySkinSafely(ItemStack originalWeapon, String newSkinId) {
        if (originalWeapon.isEmpty() || !originalWeapon.is(BuiltInRegistries.ITEM.get(TACZ_GUN_ITEM))) {
            return originalWeapon;
        }

        ItemStack skinnedWeapon = originalWeapon.copy();

        CustomData currentData = skinnedWeapon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = currentData.copyTag();

        // Также отрезаем префикс перед выдачей пушки в руки
        String actualId = newSkinId.startsWith("default:") ? newSkinId.substring(8) : newSkinId;
        tag.putString("GunId", actualId);

        skinnedWeapon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        return skinnedWeapon;
    }
}