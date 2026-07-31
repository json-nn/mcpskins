package org.minechestplate.mcpskins.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * Client-to-server: apply a skin to, or remove one from, the held weapon.
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

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            ItemStack mainHand = player.getMainHandItem();

            // The held item's GunId is always the true base gun, used directly here
            String heldBaseGun = TACZSkinHelper.getGunId(mainHand);
            if (heldBaseGun == null) {
                return; // not a TACZ weapon (or empty hand)
            }

            // Null means "strip the skin component". On the unequip path nothing from the
            // packet is trusted or even read - the outcome is derived entirely from the
            // weapon the player is actually holding.
            String appliedSkinId = null;
            if (!unequip) {
                if (skinId == null || skinId.isBlank()) {
                    return;
                }
                // Stock entries are not equippable; they exist only so the UIs have
                // something to draw for "no skin". Asking for one is a malformed request -
                // the client should have set unequip instead.
                if (SkinAttachment.isDefaultEntry(skinId)) {
                    return;
                }
                // Must name a skin that actually exists in the loaded registry
                if (SkinManager.INSTANCE.findSkin(skinId) == null) {
                    return;
                }
                if (!SkinAttachment.hasSkin(player, skinId)
                        && !player.hasPermissions(MCPSkinsServerConfig.equipBypassPermissionLevel())) {
                    return;
                }
                String requestedBaseGun = SkinManager.INSTANCE.getBaseGun(skinId);
                if (!heldBaseGun.equals(requestedBaseGun)) {
                    return; // reject skins that belong to a different gun
                }
                appliedSkinId = skinId;
            }

            ItemStack newWeapon = TACZSkinHelper.applySkin(mainHand, appliedSkinId);
            if (newWeapon == mainHand || newWeapon.isEmpty()) {
                return;
            }

            // Only show "owner" lore when a skin is actually applied, not on the stock weapon
            boolean isStock = unequip;

            ItemLore currentLore = newWeapon.get(DataComponents.LORE);
            List<Component> newLines = new ArrayList<>();
            if (currentLore != null) {
                for (Component line : currentLore.lines()) {
                    if (!isSkinOwnerLoreLine(line)) {
                        newLines.add(line);
                    }
                }
            }

            if (!isStock) {
                String playerName = player.getName().getString();
                Component ownerLore = Component.translatable(OWNER_LORE_KEY,
                                Component.literal(playerName).withStyle(net.minecraft.ChatFormatting.GOLD))
                        .withStyle(net.minecraft.ChatFormatting.GRAY);
                newLines.add(ownerLore);
            }

            newWeapon.set(DataComponents.LORE, new ItemLore(newLines));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, newWeapon);
        });
    }

    private static boolean isSkinOwnerLoreLine(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                && OWNER_LORE_KEY.equals(contents.getKey());
    }
}