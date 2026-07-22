package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;
import org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Consumable item that unlocks one specific skin when used. The target skin ID is
 * stored per-stack in a {@code SkinToUnlock} custom data tag rather than on the item
 * itself, since one item type serves every skin (see {@link #use}). Prefer granting
 * it via {@code /mcpskins give item <player> <skinId>} over building the NBT by hand.
 * <p>
 * Shift + right-click instead fuses {@link MCPSkinsServerConfig#FUSE_COST} items of this
 * item's rarity (scanned across the whole inventory, not just the held stack) into one
 * random item of the next rarity up - see {@link #fuse}.
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

        SkinDataModels.Rarity[] tiers = SkinDataModels.Rarity.values();
        SkinDataModels.Rarity rarity = lookup.skin().rarity();
        if (rarity.ordinal() < tiers.length - 1) {
            Component nextRarity = rarityLabel(tiers[rarity.ordinal() + 1]);
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.fuse_hint", MCPSkinsServerConfig.fuseCost(), rarityLabel(rarity), nextRarity)
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.mcpskins.fuse_max_rarity_hint").withStyle(ChatFormatting.DARK_GRAY));
        }

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

        if (player.isShiftKeyDown()) {
            return fuse(level, player, hand, stack, skinId, !player.getAbilities().instabuild);
        }

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
        Component skinName = skinNameComponent(lookup.skin());

        if (!previewGun.isEmpty()) {
            Component gunName = TACZSkinHelper.gunDisplayName(lookup.weapon().baseGun()).copy().withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(previewGun))));
            return Component.translatable("message.mcpskins.unlock_success_for", skinName, gunName).withStyle(ChatFormatting.GREEN);
        }

        return Component.translatable("message.mcpskins.unlock_success", skinName).withStyle(ChatFormatting.GREEN);
    }

    /**
     * Skin name component shared by every chat message that names a skin - colored by
     * rarity and clickable, so the player can jump straight to that skin in the Armory
     * (see {@code ArmoryClientCommand}'s "skin" argument) instead of hunting for it.
     */
    private static Component skinNameComponent(SkinDataModels.SkinEntry skin) {
        return Component.literal(skin.name())
                .withStyle(style -> style
                        .withColor(skin.labelColor())
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mcpskins armory " + skin.id()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("tooltip.mcpskins.open_in_armory"))));
    }

    /**
     * Consumes a configured number of unlock items of {@code heldSkinId}'s rarity (scanned
     * across the whole inventory, not just {@code stack}) for one random item of the next
     * rarity up. Both sides run the same checks, so only the roll and grant are server-only.
     */
    private InteractionResultHolder<ItemStack> fuse(Level level, Player player, InteractionHand hand, ItemStack stack, String heldSkinId, boolean consumesItems) {
        if (!MCPSkinsServerConfig.fuseEnabled()) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.fuse_disabled").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.pass(stack);
        }

        SkinDataModels.SkinLookupResult heldLookup = SkinManager.INSTANCE.findSkin(heldSkinId);
        if (heldLookup == null) {
            return InteractionResultHolder.pass(stack);
        }

        int fuseCost = MCPSkinsServerConfig.fuseCost();
        SkinDataModels.Rarity[] tiers = SkinDataModels.Rarity.values();
        SkinDataModels.Rarity rarity = heldLookup.skin().rarity();
        if (rarity.ordinal() == tiers.length - 1) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.fuse_max_rarity").withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }
        SkinDataModels.Rarity targetRarity = tiers[rarity.ordinal() + 1];

        List<SkinDataModels.SkinLookupResult> pool = SkinManager.INSTANCE.getSkinsByRarity(targetRarity);
        if (pool.isEmpty()) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.fuse_no_higher_rarity", rarityLabel(targetRarity)).withStyle(ChatFormatting.YELLOW));
            }
            return InteractionResultHolder.fail(stack);
        }

        // Distinct inventory slots holding a matching-rarity unlock item - NOT the same as
        // the item count, since several unlock items of the same skin stack into one slot
        // (see countItems). Only used for consumeSlots' iteration order.
        List<Integer> matchingSlots = findMatchingSlots(player, rarity);
        int availableCount = countItems(player, matchingSlots);
        if (consumesItems && availableCount < fuseCost) {
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("message.mcpskins.fuse_not_enough", fuseCost, rarityLabel(rarity), availableCount).withStyle(ChatFormatting.RED));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            level.playSound(player, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, 1.0f);
            return InteractionResultHolder.success(stack);
        }

        // Prefer a skin the player doesn't already own; fall back to the full pool once
        // every skin of the target rarity is unlocked
        List<SkinDataModels.SkinLookupResult> unowned = pool.stream()
                .filter(entry -> !SkinAttachment.hasSkin(player, entry.skin().id()))
                .toList();
        List<SkinDataModels.SkinLookupResult> rollPool = unowned.isEmpty() ? pool : unowned;
        SkinDataModels.SkinLookupResult rolled = rollPool.get(player.getRandom().nextInt(rollPool.size()));

        if (consumesItems) {
            consumeSlots(player, matchingSlots, fuseCost);
        }
        grantUnlockItem(player, hand, stack, rolled.skin().id());
        player.sendSystemMessage(buildFuseChatMessage(rarity, rolled));

        // Re-fetch instead of returning `stack` - grantUnlockItem may have replaced the
        // hand's contents outright (see its javadoc).
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /**
     * Distinct inventory slots holding an unlock item of {@code rarity}. A slot may hold
     * more than one, so use {@link #countItems} for the actual quantity, not
     * {@code List#size()} on the result.
     */
    private List<Integer> findMatchingSlots(Player player, SkinDataModels.Rarity rarity) {
        List<Integer> slots = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack invStack = inventory.getItem(i);
            if (invStack.getItem() != this) continue;
            CompoundTag tag = invStack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (!tag.contains("SkinToUnlock")) continue;
            SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(tag.getString("SkinToUnlock"));
            if (lookup != null && lookup.skin().rarity() == rarity) {
                slots.add(i);
            }
        }
        return slots;
    }

    /** Total item count across every slot in {@code slots} (several may stack in one slot). */
    private int countItems(Player player, List<Integer> slots) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot : slots) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * Consumes {@code amount} items across {@code slots}, taking as many as needed from
     * each stack in turn - so {@code amount} can exceed {@code slots.size()}.
     */
    private void consumeSlots(Player player, List<Integer> slots, int amount) {
        Inventory inventory = player.getInventory();
        int remaining = amount;
        for (int slot : slots) {
            if (remaining <= 0) break;
            ItemStack slotStack = inventory.getItem(slot);
            int take = Math.min(remaining, slotStack.getCount());
            slotStack.shrink(take);
            remaining -= take;
        }
    }

    /**
     * Grants one unlock item for {@code skinId}. If {@code heldStack} was fully consumed
     * by {@link #consumeSlots}, the reward goes straight back into {@code hand} instead
     * of through {@link Inventory#add}, which could just as easily merge it into an
     * unrelated stack elsewhere or drop it into a slot the player isn't watching - making
     * a successful fuse look like it consumed items and gave nothing back.
     */
    private void grantUnlockItem(Player player, InteractionHand hand, ItemStack heldStack, String skinId) {
        ItemStack resultStack = new ItemStack(this);
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinToUnlock", skinId);
        resultStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        if (heldStack.isEmpty()) {
            player.setItemInHand(hand, resultStack);
        } else if (!player.getInventory().add(resultStack)) {
            player.drop(resultStack, false);
        }
        // Immediate sync instead of the next per-tick broadcast, same as SkinCommand's "give item"
        player.containerMenu.broadcastChanges();
    }

    private static Component buildFuseChatMessage(SkinDataModels.Rarity fromRarity, SkinDataModels.SkinLookupResult rolled) {
        ItemStack previewGun = TACZSkinHelper.createGunStack(rolled.weapon().baseGun(), rolled.skin().id());
        Component skinName = skinNameComponent(rolled.skin());

        if (!previewGun.isEmpty()) {
            Component gunName = TACZSkinHelper.gunDisplayName(rolled.weapon().baseGun()).copy().withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(previewGun))));
            return Component.translatable("message.mcpskins.fuse_success_for", rarityLabel(fromRarity), skinName, gunName).withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("message.mcpskins.fuse_success", rarityLabel(fromRarity), skinName).withStyle(ChatFormatting.GREEN);
    }

    /** Same "gui.mcpskins.armory.rarity_*" keys the Armory screen already uses, so rarity names stay consistent everywhere. */
    private static Component rarityLabel(SkinDataModels.Rarity rarity) {
        return Component.translatable("gui.mcpskins.armory.rarity_" + rarity.name().toLowerCase(Locale.ROOT))
                .withStyle(style -> style.withColor(rarity.accentColor));
    }
}