package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload;

import java.util.ArrayList;
import java.util.List;

public class SkinUnlockItem extends Item {

    public SkinUnlockItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (data.contains("SkinToUnlock")) {
            String skinId = data.copyTag().getString("SkinToUnlock");

            SkinDataModels.WeaponSkins targetWeapon = null;
            SkinDataModels.SkinEntry targetSkin = null;

            outer:
            for (SkinDataModels.WeaponSkins weapon : SkinManager.INSTANCE.getRegistry().values()) {
                for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                    if (skin.id().equals(skinId)) {
                        targetWeapon = weapon;
                        targetSkin = skin;
                        break outer;
                    }
                }
            }

            if (targetSkin != null && targetWeapon != null) {
                final int finalColor = targetSkin.labelColor();

                tooltipComponents.add(Component.literal("Unlocks ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(targetSkin.name()).withStyle(style -> style.withColor(finalColor)))
                        .append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(targetWeapon.baseGun()).withStyle(ChatFormatting.YELLOW)));
            } else {
                tooltipComponents.add(Component.literal("Unknown skin: " + skinId).withStyle(ChatFormatting.RED));
            }
        } else {
            tooltipComponents.add(Component.literal("Empty Can Data").withStyle(ChatFormatting.DARK_GRAY));
        }

        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (data.contains("SkinToUnlock")) {
            String skinId = data.copyTag().getString("SkinToUnlock");

            if (!SkinAttachment.hasSkin(player, skinId)) {
                // ЛОГИКА СЕРВЕРА
                if (!level.isClientSide()) {
                    SkinAttachment.unlockSkin(player, skinId);
                    PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncUnlocksPayload(new ArrayList<>(player.getData(SkinAttachment.UNLOCKED_SKINS))));
                    player.sendSystemMessage(Component.literal("Скин " + skinId + " успешно разблокирован!").withStyle(ChatFormatting.GREEN));
                }
                // ЛОГИКА КЛИЕНТА
                else {
                    level.playSound(player, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.5f);
                }

                // ОБЩАЯ ЛОГИКА (Выполняется и на клиенте, и на сервере)
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1); // Теперь предмет корректно исчезнет на обеих сторонах синхронно
                }

                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
            } else {
                if (!level.isClientSide()) {
                    player.sendSystemMessage(Component.literal("У вас уже есть этот скин!").withStyle(ChatFormatting.RED));
                }
                return InteractionResultHolder.fail(stack);
            }
        }

        return InteractionResultHolder.pass(stack);
    }
}