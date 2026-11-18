package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import org.minechestplate.mcpskins.api.UnlockResult;
import org.minechestplate.mcpskins.api.UnlockSource;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.Comparator;
import java.util.List;

/**
 * One item type for every skin: the target is the {@code SkinToUnlock} custom data string.
 * Shift + right-click fuses instead ({@link SkinFusion}).
 */
public class SkinUnlockItem extends Item {

    public SkinUnlockItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(String skinId) {
        ItemStack stack = new ItemStack(ModItems.SKIN_UNLOCK_ITEM.get());
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinToUnlock", skinId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        String skinId = TACZSkinHelper.readCustomString(stack, "SkinToUnlock");

        if (skinId == null) {
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.empty_unlock_item").withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);

        if (lookup == null) {
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.unknown_skin", skinId).withStyle(ChatFormatting.RED));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(lookup.skin().labelColor()));

        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        MutableComponent line;
        if (!previewGun.isEmpty()) {
            Component gunName = previewGun.getHoverName().copy().withStyle(style -> style.withColor(ChatFormatting.YELLOW));
            line = Component.translatable("tooltip.mcpskins.unlocks_for", skinName, gunName).withStyle(ChatFormatting.GRAY);
        } else {
            line = Component.translatable("tooltip.mcpskins.unlocks", skinName).withStyle(ChatFormatting.GRAY);
        }
        tooltipComponents.add(line);

        appendFuseHint(tooltipComponents, RarityManager.INSTANCE.get(lookup.skin().rarityId()));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    private static void appendFuseHint(List<Component> tooltipComponents, SkinDataModels.Rarity rarity) {
        List<SkinDataModels.FuseTarget> targets = SkinFusion.targetsOf(rarity);
        Component hint;
        if (!rarity.fusable()) {
            hint = Component.translatable("tooltip.mcpskins.fuse_unfusable_hint");
        } else if (targets.isEmpty()) {
            hint = Component.translatable("tooltip.mcpskins.fuse_max_rarity_hint");
        } else {
            SkinDataModels.Rarity likeliest = RarityManager.INSTANCE.get(
                    targets.stream().max(Comparator.comparingInt(SkinDataModels.FuseTarget::weight))
                            .orElse(targets.get(0)).rarityId());
            hint = Component.translatable("tooltip.mcpskins.fuse_hint",
                    SkinFusion.costOf(rarity), rarity.label(), likeliest.label());
        }
        tooltipComponents.add(hint.copy().withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String skinId = TACZSkinHelper.readCustomString(stack, "SkinToUnlock");

        if (skinId == null) {
            return InteractionResultHolder.pass(stack);
        }

        if (player.isShiftKeyDown()) {
            return SkinFusion.attempt(this, level, player, hand, stack, skinId, !player.getAbilities().instabuild);
        }

        // isOwnedOrDefault, not hasSkin: a stock entry counts as owned, so no "default:" id is ever added.
        if (SkinAttachment.isOwnedOrDefault(player, skinId)) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.already_have_skin").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        // Both sides check, so the client never predicts a consume the server refuses.
        if (SkinManager.INSTANCE.findSkin(skinId) == null) {
            if (!level.isClientSide()) {
                boolean exists = SkinManager.INSTANCE.findSkinRaw(skinId) != null;
                player.sendSystemMessage(Component.translatable(exists
                        ? "message.mcpskins.unavailable_skin"
                        : "message.mcpskins.unknown_skin", skinId).withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (!level.isClientSide()) {
            UnlockResult result = SkinAttachment.unlockSkin(player, skinId, UnlockSource.UNLOCK_ITEM);
            if (result != UnlockResult.UNLOCKED) {
                // Cancelled by a listener, which only the server knows: undo the client's optimistic consume.
                player.containerMenu.broadcastFullState();
                return InteractionResultHolder.fail(stack);
            }
            player.sendSystemMessage(UnlockMessages.unlocked(UnlockMessages.localeOf(player), skinId));
        } else {
            level.playSound(player, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.5f);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
