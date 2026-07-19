package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumable item that unlocks one specific skin when used. The target skin ID is
 * stored per-stack in a {@code SkinToUnlock} custom data tag rather than on the item
 * itself, since one item type serves every skin (see {@link #use}). Prefer granting
 * it via {@code /mcpskins give item <player> <skinId>} over building the NBT by hand.
 */
public class SkinUnlockItem extends Item {

    public SkinUnlockItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (!data.contains("SkinToUnlock")) {
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.empty_unlock_item").withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        String skinId = data.copyTag().getString("SkinToUnlock");
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);

        if (lookup == null) {
            // Unknown skin id - either a typo in the NBT or the datapack hasn't loaded yet
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.unknown_skin", skinId).withStyle(ChatFormatting.RED));
            super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
            return;
        }

        // Skin/weapon names are dynamic datapack content, so they stay as literals passed
        // into the translatable template rather than being hardcoded text
        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(lookup.skin().labelColor()));

        // Built via createGunStack rather than by hand, so SkinComponents.SKIN_ID is set and
        // TimelessAPIMixin renders the correct re-skinned texture in the preview
        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        MutableComponent line;
        if (!previewGun.isEmpty()) {
            Component gunName = previewGun.getHoverName().copy().withStyle(style -> style.withColor(ChatFormatting.YELLOW));
            line = Component.translatable("tooltip.mcpskins.unlocks_for", skinName, gunName).withStyle(ChatFormatting.GRAY);
        } else {
            line = Component.translatable("tooltip.mcpskins.unlocks", skinName).withStyle(ChatFormatting.GRAY);
        }
        // No HoverEvent on the preview stack here - Minecraft doesn't render nested tooltips
        tooltipComponents.add(line);
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

        if (!data.contains("SkinToUnlock")) {
            return InteractionResultHolder.pass(stack); // no NBT set, nothing to do
        }

        String skinId = data.copyTag().getString("SkinToUnlock");

        if (SkinAttachment.hasSkin(player, skinId)) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.already_have_skin").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (!level.isClientSide()) {
            SkinAttachment.unlockSkin(player, skinId);
            PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncUnlocksPayload(new ArrayList<>(player.getData(SkinAttachment.UNLOCKED_SKINS))));

            player.sendSystemMessage(buildUnlockChatMessage(skinId));
        } else {
            // Played client-side for instant feedback, no round-trip to the server needed
            level.playSound(player, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5f, 1.5f);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * Builds the chat message shown on a successful unlock, with the skin name colored
     * by its label color and the weapon name showing a hover preview with the skin applied.
     */
    private static Component buildUnlockChatMessage(String skinId) {
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);

        if (lookup == null) {
            // Fallback for a bad skinId - the unlock in SkinAttachment already happened above
            return Component.translatable("message.mcpskins.skin_unlocked_fallback", skinId).withStyle(ChatFormatting.GREEN);
        }

        ItemStack previewGun = TACZSkinHelper.createGunStack(lookup.weapon().baseGun(), lookup.skin().id());
        final int labelColor = lookup.skin().labelColor();

        Component skinName = Component.literal(lookup.skin().name())
                .withStyle(style -> style.withColor(labelColor).withBold(true));

        if (!previewGun.isEmpty()) {
            Component gunName = previewGun.getHoverName().copy().withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(previewGun))));
            return Component.translatable("message.mcpskins.unlock_success_for", skinName, gunName).withStyle(ChatFormatting.GREEN);
        }

        return Component.translatable("message.mcpskins.unlock_success", skinName).withStyle(ChatFormatting.GREEN);
    }
}