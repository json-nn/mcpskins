package org.minechestplate.mcpskins.skin;

import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import org.minechestplate.mcpskins.api.UnlockSource;
import org.minechestplate.mcpskins.api.event.SkinLevelUpEvent;
import org.minechestplate.mcpskins.item.UnlockMessages;
import org.minechestplate.mcpskins.network.ApplySkinPayload;

/**
 * Server side: a TACZ gun kill counts toward the root of the worn skin's family, only if the
 * player owns that exact skin (a looted skinned gun must not level anything).
 */
public final class SkinLeveling {

    private SkinLeveling() {
    }

    public static void onGunKill(EntityKillByGunEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!(event.getAttacker() instanceof ServerPlayer player)) return;
        ResourceLocation gunId = event.getGunId();
        if (gunId == null) return;

        // The event carries no stack: find the gun by id, main hand first.
        InteractionHand hand = handHolding(player, gunId.toString());
        if (hand == null) return;
        String skinId = TACZSkinHelper.getSkinId(player.getItemInHand(hand));
        if (skinId == null || !SkinAttachment.hasSkin(player, skinId)) return;

        SkinDataModels.SkinLookupResult rootLookup = SkinManager.INSTANCE.findRoot(skinId);
        if (rootLookup == null || rootLookup.skin().levels().isEmpty()) return;
        SkinDataModels.SkinEntry root = rootLookup.skin();

        SkinProgress.Counters before = SkinProgress.get(player, root.id());
        SkinProgress.Counters after = before.plusKill(event.isHeadShot(), event.getKilledEntity() instanceof Player);
        SkinProgress.put(player, root.id(), after);

        int from = SkinProgress.levelFor(root, before);
        int to = SkinProgress.levelFor(root, after);
        String switchTo = null;
        for (int level = from + 1; level <= to; level++) {
            String variantId = root.levels().get(level - SkinDataModels.SkinLevel.FIRST_LEVEL).variant();
            SkinAttachment.unlockSkin(player, variantId, UnlockSource.LEVEL_UP);

            SkinDataModels.SkinLookupResult variant = SkinManager.INSTANCE.findSkin(variantId);
            if (variant != null) {
                player.sendSystemMessage(UnlockMessages.levelUp(UnlockMessages.localeOf(player), root, level,
                        variant.skin()));
            }
            NeoForge.EVENT_BUS.post(new SkinLevelUpEvent(player, root.id(), variantId, level));
            if (SkinAttachment.hasSkin(player, variantId)) {
                switchTo = variantId;
            }
        }
        if (switchTo != null) {
            autoSwitch(player, hand, root, switchTo);
        }
    }

    // Only if the gun still wears the family. SkinEquipEvent can cancel it; the variant stays unlocked either way.
    private static void autoSwitch(ServerPlayer player, InteractionHand hand, SkinDataModels.SkinEntry root,
                                   String variantId) {
        String worn = TACZSkinHelper.getSkinId(player.getItemInHand(hand));
        if (worn == null) return;
        SkinDataModels.SkinLookupResult wornRoot = SkinManager.INSTANCE.findRoot(worn);
        if (wornRoot == null || !wornRoot.skin().id().equals(root.id())) return;
        ApplySkinPayload.applyToHand(player, hand, variantId);
    }

    private static InteractionHand handHolding(ServerPlayer player, String gunId) {
        if (gunId.equals(TACZSkinHelper.getGunId(player.getMainHandItem()))) return InteractionHand.MAIN_HAND;
        if (gunId.equals(TACZSkinHelper.getGunId(player.getOffhandItem()))) return InteractionHand.OFF_HAND;
        return null;
    }
}
