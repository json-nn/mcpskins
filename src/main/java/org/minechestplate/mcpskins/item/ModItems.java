package org.minechestplate.mcpskins.item;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPSkins.MOD_ID);

    public static final DeferredItem<Item> SKIN_UNLOCK_ITEM = ITEMS.register("skin_unlock_item",
            () -> new SkinUnlockItem(new Item.Properties()));

}