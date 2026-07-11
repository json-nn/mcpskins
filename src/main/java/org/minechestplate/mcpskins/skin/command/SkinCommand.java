package org.minechestplate.mcpskins.skin.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

public class SkinCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcpskins")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("give")
                        .then(Commands.literal("skin")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("gunId", StringArgumentType.string())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SkinManager.INSTANCE.getAllSkinIds(), builder))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    String id = StringArgumentType.getString(context, "gunId");

                                                    SkinAttachment.unlockSkin(target, id);

                                                    context.getSource().sendSuccess(() -> Component.translatable("commands.mcpskins.give_skin.success", id, target.getName().getString()), true);
                                                    return 1;
                                                }))))
                        // Выдаёт САМ ПРЕДМЕТ (SkinUnlockItem) с корректно собранным NBT,
                        // в отличие от "give skin" (который разблокирует скин напрямую,
                        // без физического предмета). Раньше правильный NBT-формат для
                        // ручного /give приходилось подбирать по устаревшему примеру в
                        // комментарии ModItems - эта команда решает ту же задачу надёжно:
                        // skinId проверяется по реестру (SkinManager.findSkin) и
                        // предлагается автоподстановкой, так что несуществующий id
                        // просто нельзя случайно выдать.
                        .then(Commands.literal("item")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("skinId", StringArgumentType.string())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SkinManager.INSTANCE.getAllSkinIds(), builder))
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                                    String skinId = StringArgumentType.getString(context, "skinId");

                                                    SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
                                                    if (lookup == null) {
                                                        context.getSource().sendFailure(Component.translatable("commands.mcpskins.give_item.unknown_skin", skinId));
                                                        return 0;
                                                    }

                                                    ItemStack unlockStack = new ItemStack(ModItems.SKIN_UNLOCK_ITEM.get());
                                                    CompoundTag tag = new CompoundTag();
                                                    tag.putString("SkinToUnlock", skinId);
                                                    unlockStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                                                    // Тот же паттерн, что и в ванильном /give: пытаемся положить в
                                                    // инвентарь, а если он полон - роняем предмет под ноги игроку.
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

                                            SkinAttachment.clearSkins(target);

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

                                                    boolean removed = SkinAttachment.revokeSkin(target, id);

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