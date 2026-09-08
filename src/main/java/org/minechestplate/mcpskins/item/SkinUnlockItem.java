package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.network.SyncUnlocksPayload;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Consumable item that unlocks one skin. The target is stored per-stack in a
 * {@code SkinToUnlock} custom data tag, since one item type serves every skin; prefer
 * {@code /mcpskins give item <player> <skinId>} over building that tag by hand.
 * <p>
 * Shift + right-click fuses instead, see {@link SkinFusion}.
 */
public class SkinUnlockItem extends Item {

    public SkinUnlockItem(Properties properties) {
        super(properties);
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

        // Skin and weapon names are datapack content, so they go in as literals rather than keys.
        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(lookup.skin().labelColor()));

        // Built via createGunStack so the preview carries SKIN_ID and renders re-skinned.
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
            // Heaviest target, so a weighted spread advertises its most likely outcome.
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

        // isOwnedOrDefault, not hasSkin: unlocking a weapon's stock entry would only put a
        // "default:" id into the unlock set that nothing ever reads.
        if (SkinAttachment.isOwnedOrDefault(player, skinId)) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.already_have_skin").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (!level.isClientSide()) {
            SkinAttachment.unlockSkin(player, skinId);
            PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncUnlocksPayload(new ArrayList<>(player.getData(SkinAttachment.UNLOCKED_SKINS))));
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
