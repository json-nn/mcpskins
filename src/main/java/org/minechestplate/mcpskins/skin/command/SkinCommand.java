package org.minechestplate.mcpskins.skin.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.network.OpenSkinBrowserPayload;

public class SkinCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skin")
                .executes(context -> {
                    if (context.getSource().getEntity() instanceof ServerPlayer player) {
                        PacketDistributor.sendToPlayer(player, new OpenSkinBrowserPayload());
                        return 1;
                    } else {
                        context.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок!"));
                        return 0;
                    }
                }));

        dispatcher.register(Commands.literal("mcpskins")
                .then(Commands.literal("give").then(Commands.literal("skin")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("gunId", StringArgumentType.string())
                                        .executes(context -> {
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                            String id = StringArgumentType.getString(context, "gunId");

                                            SkinAttachment.unlockSkin(target, id);

                                            context.getSource().sendSuccess(() -> Component.literal("Unlocked skin " + id + " for " + target.getName().getString()), true);
                                            return 1;
                                        }))))).then(Commands.literal("take").then(Commands.literal("skins")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");

                                    SkinAttachment.clearSkins(target);

                                    context.getSource().sendSuccess(() -> Component.literal("Cleared skins for " + target.getName().getString()), true);
                                    return 1;
                                })))));
    }
}