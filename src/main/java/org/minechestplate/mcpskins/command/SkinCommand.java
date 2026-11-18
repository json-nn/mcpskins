package org.minechestplate.mcpskins.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.api.RevokeSource;
import org.minechestplate.mcpskins.api.UnlockResult;
import org.minechestplate.mcpskins.api.UnlockSource;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.item.SkinUnlockItem;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

/** Server admin tree {@code /mcpskins give|take}. {@code /mcpskins armory} is client side, in ArmoryClientCommand. */
public class SkinCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcpskins")
                .requires(source -> source.hasPermission(MCPSkinsServerConfig.adminCommandPermissionLevel()))
                .then(Commands.literal("give")
                        .then(Commands.literal("skin")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("gunId", StringArgumentType.string())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SkinManager.INSTANCE.getAllSkinIds(), builder))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    String id = StringArgumentType.getString(context, "gunId");

                                                    UnlockResult result = SkinAttachment.unlockSkin(target, id, UnlockSource.COMMAND);
                                                    if (result == UnlockResult.UNKNOWN_SKIN) {
                                                        context.getSource().sendFailure(Component.translatable("commands.mcpskins.give_item.unknown_skin", id));
                                                        return 0;
                                                    }
                                                    if (result == UnlockResult.UNAVAILABLE) {
                                                        context.getSource().sendFailure(Component.translatable("commands.mcpskins.unavailable_skin", id));
                                                        return 0;
                                                    }
                                                    if (result == UnlockResult.CANCELLED) {
                                                        context.getSource().sendFailure(Component.translatable("commands.mcpskins.give_skin.cancelled", id, target.getName().getString()));
                                                        return 0;
                                                    }

                                                    context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.give_skin.success", id, target.getName().getString()), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("item")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("skinId", StringArgumentType.string())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SkinManager.INSTANCE.getAllSkinIds(), builder))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    String skinId = StringArgumentType.getString(context, "skinId");

                                                    SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
                                                    if (lookup == null) {
                                                        boolean exists = SkinManager.INSTANCE.findSkinRaw(skinId) != null;
                                                        context.getSource().sendFailure(exists
                                                                ? Component.translatable("commands.mcpskins.unavailable_skin", skinId)
                                                                : Component.translatable("commands.mcpskins.give_item.unknown_skin", skinId));
                                                        return 0;
                                                    }

                                                    ItemStack unlockStack = SkinUnlockItem.create(skinId);

                                                    boolean added = target.getInventory().add(unlockStack);
                                                    if (!added) {
                                                        target.drop(unlockStack, false);
                                                    }
                                                    target.containerMenu.broadcastChanges();

                                                    context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.give_item.success", lookup.skin().name(), target.getName().getString()), true);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("all")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");

                                            SkinAttachment.unlockAllSkins(target);

                                            context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.give_all.success", target.getName().getString()), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("take")
                        .then(Commands.literal("skins")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> {
                                            ServerPlayer target = EntityArgument.getPlayer(context, "player");

                                            SkinAttachment.clearSkins(target, RevokeSource.COMMAND);

                                            context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.take_all.success", target.getName().getString()), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("skin")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("skinId", StringArgumentType.string())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SkinManager.INSTANCE.getAllSkinIds(), builder))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    String id = StringArgumentType.getString(context, "skinId");

                                                    boolean removed = SkinAttachment.revokeSkin(target, id, RevokeSource.COMMAND);

                                                    if (removed) {
                                                        context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.take_skin.success", id, target.getName().getString()), true);
                                                        return 1;
                                                    } else {
                                                        context.getSource().sendFailure(Component.translatable("commands.mcpskins.take_skin.not_found", target.getName().getString(), id));
                                                        return 0;
                                                    }
                                                }))))));
    }
}