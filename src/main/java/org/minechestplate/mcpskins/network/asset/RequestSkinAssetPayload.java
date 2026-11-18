package org.minechestplate.mcpskins.network.asset;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Client to server: the bytes behind {@code path}, a {@code namespace:path} key under a pack's
 * assets/ (e.g. {@code mcpskins:textures/skins/rifle/cobra.png}). Only ever an index key, never a
 * filesystem path. Runs on the network thread (registrar {@code HandlerThread.NETWORK}), because
 * {@link ServerSkinAssetStore#handleRequest} does blocking zip I/O and Deflate.
 */
public record RequestSkinAssetPayload(String path) implements CustomPacketPayload {
    public static final Type<RequestSkinAssetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "request_skin_asset"));

    private static final int MAX_PATH_LENGTH = 512;

    public static final StreamCodec<FriendlyByteBuf, RequestSkinAssetPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_PATH_LENGTH), RequestSkinAssetPayload::path,
            RequestSkinAssetPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        ServerSkinAssetStore.INSTANCE.handleRequest(player, path);
    }
}