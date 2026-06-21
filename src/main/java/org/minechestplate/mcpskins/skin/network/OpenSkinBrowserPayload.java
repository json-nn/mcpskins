package org.minechestplate.mcpskins.skin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;

public record OpenSkinBrowserPayload() implements CustomPacketPayload {
    public static final Type<OpenSkinBrowserPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "open_skin_browser"));

    // Так как пакет не несет в себе дополнительных данных, используем unit-кодек
    public static final StreamCodec<FriendlyByteBuf, OpenSkinBrowserPayload> CODEC = StreamCodec.unit(new OpenSkinBrowserPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            // Безопасно вызываем клиентский код через изолированный обработчик
            ClientPayloadHandler.handleOpenSkinBrowser();
        });
    }
}