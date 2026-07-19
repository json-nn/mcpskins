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
 * Registers the client-side "/mcpskins armory" command that opens {@link SkinArmoryScreen}.
 * A purely client-side alternative to the {@link ArmoryKeybinds} hotkey - no server
 * round-trip or permission level required, works in any world.
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
                            // Unlike the hotkey, no "screen == null" guard is needed here:
                            // an explicit command is explicit intent, so open unconditionally.
                            Minecraft.getInstance().setScreen(new SkinArmoryScreen());
                            return 1;
                        })));
    }
}
