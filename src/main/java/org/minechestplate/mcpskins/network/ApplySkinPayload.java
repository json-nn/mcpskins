package org.minechestplate.mcpskins.network;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.api.event.SkinEquipEvent;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client to server: apply or remove the held item's skin. Removal is the {@link #unequip} flag,
 * never a magic id; ownership must never be decided by id shape.
 */
public record ApplySkinPayload(String skinId, boolean unequip) implements CustomPacketPayload {
    public static final Type<ApplySkinPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "apply_skin"));

    // Matched by translation key, not rendered text, so it holds in every language.
    private static final String OWNER_LORE_KEY = "tooltip.mcpskins.skin_owner";

    private static final int MAX_SKIN_ID_LENGTH = 256;

    private static final int MAX_PER_SECOND = 20;

    public static final StreamCodec<FriendlyByteBuf, ApplySkinPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH), ApplySkinPayload::skinId,
            ByteBufCodecs.BOOL, ApplySkinPayload::unequip,
            ApplySkinPayload::new
    );

    public static ApplySkinPayload equip(String skinId) {
        return new ApplySkinPayload(skinId, false);
    }

    public static ApplySkinPayload removeSkin() {
        return new ApplySkinPayload("", true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Network thread; game state only inside enqueueWork, so a flood never reaches the tick loop.
    public void handleData(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!ServerboundRateLimiter.allow(player.getUUID(), MAX_PER_SECOND)) return;

        context.enqueueWork(() -> {
            // Null strips the skin; that path reads nothing from the packet.
            String appliedSkinId = null;
            String requiredBaseGun = null;
            if (!unequip) {
                if (skinId == null || skinId.isBlank()) {
                    return;
                }
                // Stock entries are UI-only; removal must use unequip.
                if (SkinAttachment.isDefaultEntry(skinId)) {
                    return;
                }
                if (SkinManager.INSTANCE.findSkin(skinId) == null) {
                    return;
                }
                if (!SkinAttachment.hasSkin(player, skinId)
                        && !player.hasPermissions(MCPSkinsServerConfig.equipBypassPermissionLevel())) {
                    return;
                }
                requiredBaseGun = SkinManager.INSTANCE.getBaseGun(skinId);
                if (requiredBaseGun == null) {
                    return;
                }
                appliedSkinId = skinId;
            }

            InteractionHand hand = findHand(player, requiredBaseGun);
            if (hand == null) {
                return;
            }
            applyToHand(player, hand, appliedSkinId);
        });
    }

    // Posts SkinEquipEvent; the caller has done every ownership check. Also used by level-up auto-switch.
    public static boolean applyToHand(ServerPlayer player, InteractionHand hand, String appliedSkinId) {
        ItemStack weapon = player.getItemInHand(hand);

        // No-op equips must not force a lore rebuild and re-sync.
        String targetBare = appliedSkinId == null ? null : TACZSkinHelper.bareSkinId(appliedSkinId);
        String previousSkinId = TACZSkinHelper.getSkinId(weapon);
        if (Objects.equals(previousSkinId, targetBare)) {
            return false;
        }

        // Last step before the stack changes, so cancelling leaves it untouched.
        SkinEquipEvent event = new SkinEquipEvent(player, hand, TACZSkinHelper.getTaczId(weapon),
                previousSkinId, appliedSkinId);
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            return false;
        }

        ItemStack newWeapon = TACZSkinHelper.applySkin(weapon, appliedSkinId);
        if (newWeapon == weapon || newWeapon.isEmpty()) {
            return false;
        }

        ItemLore currentLore = newWeapon.get(DataComponents.LORE);
        List<Component> newLines = new ArrayList<>();
        if (currentLore != null) {
            for (Component line : currentLore.lines()) {
                if (!isSkinOwnerLoreLine(line)) {
                    newLines.add(line);
                }
            }
        }

        if (appliedSkinId != null) {
            Component ownerLore = Component.translatable(OWNER_LORE_KEY,
                            Component.literal(player.getName().getString()).withStyle(ChatFormatting.GOLD))
                    .withStyle(ChatFormatting.GRAY);
            newLines.add(ownerLore);
        }

        newWeapon.set(DataComponents.LORE, new ItemLore(newLines));
        player.setItemInHand(hand, newWeapon);
        return true;
    }

    // Main hand first, then offhand: both UIs equip from either.
    private static InteractionHand findHand(ServerPlayer player, String baseId) {
        for (InteractionHand hand : InteractionHand.values()) {
            String heldId = TACZSkinHelper.getTaczId(player.getItemInHand(hand));
            if (heldId != null && (baseId == null || baseId.equals(heldId))) {
                return hand;
            }
        }
        return null;
    }

    private static boolean isSkinOwnerLoreLine(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                && OWNER_LORE_KEY.equals(contents.getKey());
    }
}