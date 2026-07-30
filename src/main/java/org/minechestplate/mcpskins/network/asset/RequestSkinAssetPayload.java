package org.minechestplate.mcpskins.network.asset;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Client-to-server: "send me the raw bytes behind this asset path, if it exists."
 * <p>
 * path is the "namespace:relative/path" key a skin pack file would have under assets/,
 * e.g. {@code "mcpskins:textures/skins/rifle/cobra.png"}. Only ever used as a lookup key
 * into {@link ServerSkinAssetStore}'s index - never treated as a real filesystem path.
 * <p>
 * Handled on the network thread - NOT the default. NeoForge runs payload handlers on the
 * main thread unless the registrar opts into {@code HandlerThread.NETWORK} (see
 * {@code MCPSkins#registerNetworking}); skipping {@link IPayloadContext#enqueueWork} does
 * NOT get you off the main thread by itself, it only skips an extra hop that would otherwise
 * happen from whichever thread you're already on. {@link ServerSkinAssetStore#handleRequest}
 * does blocking file/zip I/O and Deflate compression, which is exactly the "resource
 * intensive" case NeoForge's docs point at {@code HandlerThread.NETWORK} for - left on the
 * main thread, it stalls the entire server's tick loop for every first-time asset request.
 */
public record RequestSkinAssetPayload(String path) implements CustomPacketPayload {
    public static final Type<RequestSkinAssetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "request_skin_asset"));

    /** Generous but bounded - real keys are short file paths, never anywhere near this. */
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