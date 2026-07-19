package org.minechestplate.mcpskins.item;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Registers this mod's items.
 */
public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPSkins.MOD_ID);

    // Prefer "/mcpskins give item <player> <skinId>" over a manual /give: it validates
    // the skinId against SkinManager and offers tab-completion, so a bad id can't be given.
    public static final DeferredItem<Item> SKIN_UNLOCK_ITEM = ITEMS.register("skin_unlock_item",
            () -> new SkinUnlockItem(new Item.Properties()));

}