package org.minechestplate.mcpskins.skin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.client.gui.SkinArmoryScreen;

/**
 * Хоткей для открытия {@link SkinArmoryScreen} - "Точки входа" из §3 концепта ("Хоткей
 * ({@code KeyMapping}), регистрируется в {@code ClientModEvents}, работает даже без оружия в
 * руках"). Регистрация самого {@link KeyMapping} живёт в {@code ClientModEvents} (MOD-шина,
 * {@code RegisterKeyMappingsEvent}) - см. {@link org.minechestplate.mcpskins.ClientModEvents
 * #registerKeyMappings}; этот класс отвечает только за то, ЧТО происходит при нажатии, тем же
 * разделением ответственности, что уже используется для {@code ClientHeldGunRefresher}
 * (отдельный tick-обработчик на GAME-шине, а не всё в одном месте).
 * <p>
 * Namespace клавиши: {@code key.mcpskins.open_armory} / {@code key.categories.mcpskins} -
 * переводы этих строк нужно добавить в lang-файл (см. итоговый список новых ключей).
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ArmoryKeybinds {

    public static final String CATEGORY = "key.categories.mcpskins";

    // K по умолчанию - не пересекается со штатными биндами TACZ (R/G/B/V заняты
    // перезарядкой/гранатой/прицелом/переключением режима огня в дефолтном ганпаке) и с
    // ванильными биндами. При конфликте с другим модом игрок как обычно перебиндит в
    // "Управление" -> "MCPSkins".
    public static final KeyMapping OPEN_ARMORY = new KeyMapping(
            "key.mcpskins.open_armory", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private ArmoryKeybinds() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // consumeClick() обязательно вызывается в цикле (см. javadoc KeyMapping) - иначе
        // повторные быстрые нажатия между тиками могли бы "потеряться".
        while (OPEN_ARMORY.consumeClick()) {
            // Не перебиваем уже открытый экран (инвентарь, чат, другой мод и т.д.) - открытие
            // поверх произвольного чужого экрана могло бы привести к рассинхронизации его
            // собственного состояния (тот же принцип осторожности, что и в
            // ClientHeldGunRefresher про "не форсировать в произвольный момент").
            if (mc.screen == null) {
                mc.setScreen(new SkinArmoryScreen());
            }
        }
    }
}
