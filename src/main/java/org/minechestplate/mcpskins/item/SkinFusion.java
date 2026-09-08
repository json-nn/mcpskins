package org.minechestplate.mcpskins.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.network.SkinFusionPayload;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Trades several {@link SkinUnlockItem}s of one rarity for a single random item of a higher
 * one. Both sides run the same checks, so only the roll and the grant are server-only.
 */
final class SkinFusion {

    private SkinFusion() {
    }

    /**
     * @param consumesItems false in creative, where the checks still run but nothing is spent
     */
    static InteractionResultHolder<ItemStack> attempt(Item unlockItem, Level level, Player player,
                                                      InteractionHand hand, ItemStack stack,
                                                      String heldSkinId, boolean consumesItems) {
        if (!MCPSkinsServerConfig.fuseEnabled()) {
            tell(level, player, ChatFormatting.RED, Component.translatable("message.mcpskins.fuse_disabled"));
            return InteractionResultHolder.pass(stack);
        }

        SkinDataModels.SkinLookupResult heldLookup = SkinManager.INSTANCE.findSkin(heldSkinId);
        if (heldLookup == null) {
            return InteractionResultHolder.pass(stack);
        }

        String locale = UnlockMessages.localeOf(player);
        SkinDataModels.Rarity rarity = RarityManager.INSTANCE.get(heldLookup.skin().rarityId());
        if (!rarity.fusable()) {
            return refuse(level, player, stack, ChatFormatting.RED,
                    Component.translatable("message.mcpskins.fuse_unfusable", rarity.label(locale)));
        }

        List<SkinDataModels.FuseTarget> targets = targetsOf(rarity);
        if (targets.isEmpty()) {
            return refuse(level, player, stack, ChatFormatting.RED,
                    Component.translatable("message.mcpskins.fuse_max_rarity"));
        }

        // Targets with nothing to hand out are dropped before the weighted pick, so a spread
        // can't roll into an empty tier and fail on luck alone.
        List<SkinDataModels.FuseTarget> viable = targets.stream()
                .filter(target -> !SkinManager.INSTANCE.getSkinsByRarity(target.rarityId()).isEmpty())
                .toList();
        if (viable.isEmpty()) {
            SkinDataModels.Rarity named = RarityManager.INSTANCE.get(targets.get(0).rarityId());
            return refuse(level, player, stack, ChatFormatting.YELLOW,
                    Component.translatable("message.mcpskins.fuse_no_higher_rarity", named.label(locale)));
        }

        int fuseCost = costOf(rarity);
        List<Integer> matchingSlots = findMatchingSlots(unlockItem, player, rarity.id());
        int availableCount = countItems(player, matchingSlots);
        if (consumesItems && availableCount < fuseCost) {
            return refuse(level, player, stack, ChatFormatting.RED,
                    Component.translatable("message.mcpskins.fuse_not_enough",
                            fuseCost, rarity.label(locale), availableCount));
        }

        if (level.isClientSide()) {
            // Feedback rides on SkinFusionPayload instead, so it only fires for a fuse the
            // server actually accepted rather than every optimistic client-side pass.
            return InteractionResultHolder.success(stack);
        }

        SkinDataModels.SkinLookupResult rolled = roll(player, viable);

        // Read before consuming, since consumeSlots empties the stacks it takes from.
        List<String> fusedSkinIds = collectFusedSkins(player, matchingSlots, fuseCost, heldSkinId);
        if (consumesItems) {
            consumeSlots(player, matchingSlots, fuseCost);
        }
        grantUnlockItem(unlockItem, player, hand, stack, rolled.skin().id());
        player.sendSystemMessage(UnlockMessages.fused(locale, rarity, rolled));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                SkinFusionPayload.create(player, hand, fusedSkinIds, rolled.skin().id()));

        // Re-fetched, because grantUnlockItem may have replaced the hand's contents outright.
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /**
     * This rarity's declared fuse targets, or the next fusable tier by order when it declares
     * none. Empty means the top of the ladder.
     */
    static List<SkinDataModels.FuseTarget> targetsOf(SkinDataModels.Rarity rarity) {
        if (!rarity.fuseTargets().isEmpty()) {
            // A target that has since been made unfusable stops being a valid outcome.
            return rarity.fuseTargets().stream()
                    .filter(target -> RarityManager.INSTANCE.get(target.rarityId()).fusable())
                    .toList();
        }
        SkinDataModels.Rarity next = RarityManager.INSTANCE.nextByOrder(rarity);
        return next == null ? List.of() : List.of(new SkinDataModels.FuseTarget(next.id(), 1));
    }

    static int costOf(SkinDataModels.Rarity rarity) {
        return rarity.fuseCost() != null ? rarity.fuseCost() : MCPSkinsServerConfig.fuseCost();
    }

    /** Prefers a skin the player doesn't own, falling back once the whole tier is unlocked. */
    private static SkinDataModels.SkinLookupResult roll(Player player, List<SkinDataModels.FuseTarget> viable) {
        String targetRarityId = weightedPick(viable, SkinDataModels.FuseTarget::weight, player.getRandom()).rarityId();
        List<SkinDataModels.SkinLookupResult> pool = SkinManager.INSTANCE.getSkinsByRarity(targetRarityId);
        List<SkinDataModels.SkinLookupResult> unowned = pool.stream()
                .filter(entry -> !SkinAttachment.hasSkin(player, entry.skin().id()))
                .toList();
        return weightedPick(unowned.isEmpty() ? pool : unowned,
                entry -> entry.skin().weight(), player.getRandom());
    }

    private static InteractionResultHolder<ItemStack> refuse(Level level, Player player, ItemStack stack,
                                                             ChatFormatting color, Component message) {
        tell(level, player, color, message);
        return InteractionResultHolder.fail(stack);
    }

    /** Both sides run the checks, so only the server side of a shared refusal speaks. */
    private static void tell(Level level, Player player, ChatFormatting color, Component message) {
        if (!level.isClientSide()) {
            player.sendSystemMessage(message.copy().withStyle(color));
        }
    }

    /** Distinct slots, not a count: several unlock items of one skin share a slot. */
    private static List<Integer> findMatchingSlots(Item unlockItem, Player player, String rarityId) {
        List<Integer> slots = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack invStack = inventory.getItem(i);
            if (invStack.getItem() != unlockItem) continue;
            String slotSkinId = TACZSkinHelper.readCustomString(invStack, "SkinToUnlock");
            if (slotSkinId == null) continue;
            SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(slotSkinId);
            if (lookup != null && lookup.skin().rarityId().equals(rarityId)) {
                slots.add(i);
            }
        }
        return slots;
    }

    /** Weights are clamped to at least 1, so a zero-weight entry stays reachable. */
    private static <T> T weightedPick(List<T> items, ToIntFunction<T> weightOf, RandomSource random) {
        int total = 0;
        for (T item : items) {
            total += Math.max(1, weightOf.applyAsInt(item));
        }
        int roll = random.nextInt(total);
        for (T item : items) {
            roll -= Math.max(1, weightOf.applyAsInt(item));
            if (roll < 0) return item;
        }
        return items.get(items.size() - 1);
    }

    private static int countItems(Player player, List<Integer> slots) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot : slots) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * Skin ids of the items {@link #consumeSlots} is about to take, one per item and in the same
     * order, so the fuse effect can tint each orbiting item. Falls back to the held skin, since a
     * creative fuse consumes nothing yet still animates.
     */
    private static List<String> collectFusedSkins(Player player, List<Integer> slots, int amount, String heldSkinId) {
        List<String> skinIds = new ArrayList<>();
        Inventory inventory = player.getInventory();
        int remaining = amount;
        for (int slot : slots) {
            if (remaining <= 0 || skinIds.size() >= SkinFusionPayload.MAX_RING_ITEMS) break;
            ItemStack slotStack = inventory.getItem(slot);
            String slotSkinId = TACZSkinHelper.readCustomString(slotStack, "SkinToUnlock");
            int take = Math.min(remaining, slotStack.getCount());
            for (int i = 0; i < take && skinIds.size() < SkinFusionPayload.MAX_RING_ITEMS; i++) {
                skinIds.add(slotSkinId == null ? heldSkinId : slotSkinId);
            }
            remaining -= take;
        }
        if (skinIds.isEmpty()) {
            skinIds.add(heldSkinId);
        }
        return skinIds;
    }

    /** Takes as many as needed from each stack in turn, so {@code amount} may exceed the slot count. */
    private static void consumeSlots(Player player, List<Integer> slots, int amount) {
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
     * A fully consumed hand gets the reward back directly: {@link Inventory#add} could merge it
     * into an unrelated stack or a slot the player isn't watching, making a successful fuse look
     * like it took the items and gave nothing back.
     */
    private static void grantUnlockItem(Item unlockItem, Player player, InteractionHand hand,
                                        ItemStack heldStack, String skinId) {
        ItemStack resultStack = new ItemStack(unlockItem);
        CompoundTag tag = new CompoundTag();
        tag.putString("SkinToUnlock", skinId);
        resultStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        if (heldStack.isEmpty()) {
            player.setItemInHand(hand, resultStack);
        } else if (!player.getInventory().add(resultStack)) {
            player.drop(resultStack, false);
        }
        player.containerMenu.broadcastChanges();
    }
}
