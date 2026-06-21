package org.minechestplate.mcpskins.skin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

public record ApplySkinPayload(String skinId) implements CustomPacketPayload {
    public static final Type<ApplySkinPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "apply_skin"));

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
            if (context.player() instanceof ServerPlayer player) {
                if (!SkinAttachment.hasSkin(player, skinId) && !player.hasPermissions(2)) {
                    return;
                }
                ItemStack mainHand = player.getMainHandItem();
                ItemStack newWeapon = TACZSkinHelper.applySkinSafely(mainHand, skinId);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, newWeapon);
            }
        });
    }
}