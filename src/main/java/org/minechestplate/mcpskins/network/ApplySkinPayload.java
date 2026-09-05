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
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client-to-server: apply a skin to, or remove one from, the held gun or attachment.
 * <p>
 * Removal is signalled by {@link #unequip}, never by a magic id. Sending
 * {@code "default:<gunId>"} for it used to make {@code SkinAttachment.hasSkin} report "owned"
 * on the prefix alone, which let any locked skin through the ownership gate.
 */
public record ApplySkinPayload(String skinId, boolean unequip) implements CustomPacketPayload {
    public static final Type<ApplySkinPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "apply_skin"));

    // Matched by translation key rather than rendered text, so it works regardless of language
    private static final String OWNER_LORE_KEY = "tooltip.mcpskins.skin_owner";

    /** Bounded; readUtf()'s 32767 default is far beyond any real skin id. */
    private static final int MAX_SKIN_ID_LENGTH = 256;

    /** Equipping is a deliberate UI action; well above human speed, just a flood ceiling. */
    private static final int MAX_PER_SECOND = 20;

    public static final StreamCodec<FriendlyByteBuf, ApplySkinPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH), ApplySkinPayload::skinId,
            ByteBufCodecs.BOOL, ApplySkinPayload::unequip,
            ApplySkinPayload::new
    );

    /** Server still verifies ownership. */
    public static ApplySkinPayload equip(String skinId) {
        return new ApplySkinPayload(skinId, false);
    }

    /** Returns the held weapon to stock. Carries no skin id. */
    public static ApplySkinPayload removeSkin() {
        return new ApplySkinPayload("", true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Runs on the network thread; everything touching game state goes through enqueueWork. */
    public void handleData(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        // Dropped here, so a flood never reaches the tick loop at all.
        if (!ServerboundRateLimiter.allow(player.getUUID(), MAX_PER_SECOND)) return;

        context.enqueueWork(() -> {
            // Null means "strip the skin". On the unequip path nothing from the packet is
            // trusted or even read; the outcome comes entirely from the weapon in hand.
            String appliedSkinId = null;
            String requiredBaseGun = null;
            if (!unequip) {
                if (skinId == null || skinId.isBlank()) {
                    return;
                }
                // Stock entries are not equippable; they exist only so the UIs have something
                // to draw for "no skin". Asking for one means the client should have set
                // unequip instead.
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
                return; // empty hands, or the skin belongs to an item the player isn't holding
            }
            ItemStack weapon = player.getItemInHand(hand);

            // Re-applying the skin already shown is a no-op: skip the lore rebuild and the
            // inventory re-sync a spam client would otherwise force on every packet.
            String targetBare = appliedSkinId == null ? null : TACZSkinHelper.bareSkinId(appliedSkinId);
            if (Objects.equals(TACZSkinHelper.getSkinId(weapon), targetBare)) {
                return;
            }

            ItemStack newWeapon = TACZSkinHelper.applySkin(weapon, appliedSkinId);
            if (newWeapon == weapon || newWeapon.isEmpty()) {
                return;
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

            // Only show "owner" lore when a skin is actually applied, not on the stock weapon
            if (!unequip) {
                Component ownerLore = Component.translatable(OWNER_LORE_KEY,
                                Component.literal(player.getName().getString()).withStyle(ChatFormatting.GOLD))
                        .withStyle(ChatFormatting.GRAY);
                newLines.add(ownerLore);
            }

            newWeapon.set(DataComponents.LORE, new ItemLore(newLines));
            player.setItemInHand(hand, newWeapon);
        });
    }

    /**
     * The hand holding {@code baseId}, or either hand holding any skinnable TACZ item when it
     * is null. Both UIs equip from the offhand too, so the server has to resolve the same way
     * instead of assuming the main hand.
     */
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