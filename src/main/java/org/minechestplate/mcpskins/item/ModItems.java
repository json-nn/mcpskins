package org.minechestplate.mcpskins.item;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.minechestplate.mcpskins.MCPSkins;

public class ModItems {
    // В NeoForge 1.21.1 для предметов есть удобный типизированный класс DeferredRegister.Items
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCPSkins.MOD_ID);

    // Регистрируем сам предмет.
    //
    // ВАЖНО про выдачу этого предмета: не собирайте /give вручную по старому примеру
    // (id "create_armorer_skins:galaxy/pistol_auto_stress_galaxy" ниже в истории коммитов
    // не работал, потому что это id из ЧУЖОГО ганпака в формате "namespace:path", а не id
    // из вашего собственного реестра скинов - см. javadoc SkinManager про формат id,
    // ожидается плоская строка вроде "m4a1_cobra", БЕЗ двоеточий и слэшей).
    //
    // Вместо ручного /give используйте команду:
    //   /mcpskins give item <player> <skinId>
    // (см. SkinCommand.java) - она сама проверяет skinId по SkinManager.findSkin()
    // и предлагает автоподстановку по Tab, так что несуществующий/опечатанный id
    // просто нельзя выдать.
    public static final DeferredItem<Item> SKIN_UNLOCK_ITEM = ITEMS.register("skin_unlock_item",
            () -> new SkinUnlockItem(new Item.Properties()));

}