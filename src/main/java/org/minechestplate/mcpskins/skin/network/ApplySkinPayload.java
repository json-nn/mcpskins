package org.minechestplate.mcpskins.skin.network;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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
 * Client-to-server packet requesting a skin be applied to the held weapon.
 */
public record ApplySkinPayload(String skinId) implements CustomPacketPayload {
    public static final Type<ApplySkinPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "apply_skin"));

    // Matched by translation key rather than rendered text, so it works regardless of language
    private static final String OWNER_LORE_KEY = "tooltip.mcpskins.skin_owner";

    public static final StreamCodec<FriendlyByteBuf, ApplySkinPayload> CODEC = CustomPacketPayload.codec(
            ApplySkinPayload::write, ApplySkinPayload::new
    );

    public ApplySkinPayload(FriendlyByteBuf buffer) {
        this(buffer.readUtf());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(skinId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            if (!SkinAttachment.hasSkin(player, skinId)
                    && !player.hasPermissions(MCPSkinsServerConfig.equipBypassPermissionLevel())) {
                return;
            }

            ItemStack mainHand = player.getMainHandItem();

            // The held item's GunId is always the true base gun, used directly here
            String heldBaseGun = TACZSkinHelper.getGunId(mainHand);
            if (heldBaseGun == null) {
                return; // not a TACZ weapon (or empty hand)
            }

            String requestedBaseGun = SkinManager.INSTANCE.getBaseGun(skinId);
            if (!heldBaseGun.equals(requestedBaseGun)) {
                return; // reject skins that belong to a different gun
            }

            ItemStack newWeapon = TACZSkinHelper.applySkin(mainHand, skinId);
            if (newWeapon == mainHand || newWeapon.isEmpty()) {
                return;
            }

            // Only show "owner" lore when a skin is actually applied, not on the stock weapon
            boolean isStock = TACZSkinHelper.bareSkinId(skinId).equals(heldBaseGun);

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