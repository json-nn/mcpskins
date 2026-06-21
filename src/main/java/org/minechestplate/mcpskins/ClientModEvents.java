package org.minechestplate.mcpskins;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

@EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            // tintIndex 0 соответствует "layer0" в нашем JSON (наш фон)
            if (tintIndex == 0) {
                CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

                if (data.contains("SkinToUnlock")) {
                    String skinId = data.copyTag().getString("SkinToUnlock");

                    // Ищем цвет скина в реестре
                    for (SkinDataModels.WeaponSkins weapon : SkinManager.INSTANCE.getRegistry().values()) {
                        for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                            if (skin.id().equals(skinId)) {
                                // Маска | 0xFF000000 гарантирует, что альфа-канал будет равен FF (непрозрачный)
                                return skin.labelColor() | 0xFF000000;
                            }
                        }
                    }
                }
            }
            // Если скин не найден или это tintIndex 1 (белые линии), возвращаем непрозрачный белый
            return 0xFFFFFFFF; // Использовать 0xFFFFFFFF вместо 0xFFFFFF

        }, ModItems.SKIN_UNLOCK_ITEM.get());
    }
}