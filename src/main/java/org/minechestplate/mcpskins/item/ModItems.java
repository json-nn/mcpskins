package org.minechestplate.mcpskins.item;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

public class ModItems {
    // В NeoForge 1.21.1 для предметов есть удобный типизированный класс DeferredRegister.Items
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPSkins.MOD_ID);

    // Регистрируем сам предмет
    public static final DeferredItem<Item> SKIN_UNLOCK_ITEM = ITEMS.register("skin_unlock_item",
            () -> new SkinUnlockItem(new Item.Properties())); // /give @p mcpskins:skin_unlock_item[minecraft:custom_data={SkinToUnlock:"tacz:glock_17"}]


}