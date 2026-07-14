package org.minechestplate.mcpskins.skin.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.client.gui.SkinArmoryScreen;

/**
 * {@code /mcpskins armory} - вторая "точка входа" из §3 концепта, помимо хоткея
 * ({@link ArmoryKeybinds}). Намеренно РЕГИСТРИРУЕТСЯ КАК КЛИЕНТСКАЯ команда (через
 * {@link RegisterClientCommandsEvent}, а не {@code RegisterCommandsEvent}, который используют
 * серверные команды вроде {@code SkinCommand}) - открытие GUI-экрана целиком клиентское
 * действие, для него не нужны ни поход на сервер, ни право {@code permission(4)}, которое
 * требует {@code SkinCommand} для выдачи/снятия скинов. Работает даже в одиночной игре без
 * читов и на любом сервере, независимо от прав игрока.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ArmoryClientCommand {

    private ArmoryClientCommand() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mcpskins")
                .then(Commands.literal("armory")
                        .executes(context -> {
                            // Специально БЕЗ проверки "mc.screen == null" (в отличие от
                            // хоткея в ArmoryKeybinds) - в момент выполнения команды из чата
                            // экран чата ещё технически "открыт", он закроется сам сразу после
                            // отправки сообщения. Явная команда - явное намерение, поэтому
                            // открываем экран безусловно.
                            Minecraft.getInstance().setScreen(new SkinArmoryScreen());
                            return 1;
                        })));
    }
}
