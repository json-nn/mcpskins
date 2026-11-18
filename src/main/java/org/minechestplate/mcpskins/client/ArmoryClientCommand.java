package org.minechestplate.mcpskins.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.gui.SkinArmoryScreen;

/**
 * Client-only {@code /mcpskins armory [skin]}; no permission needed. The skin argument is what the
 * clickable names in unlock, fuse and level-up chat lines run.
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
                            Minecraft.getInstance().setScreen(new SkinArmoryScreen());
                            return 1;
                        })
                        .then(Commands.argument("skin", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String skinId = StringArgumentType.getString(context, "skin");
                                    Minecraft.getInstance().setScreen(new SkinArmoryScreen(skinId));
                                    return 1;
                                }))));
    }
}