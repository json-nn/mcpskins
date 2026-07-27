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
 * Handled straight on the network thread, no {@link IPayloadContext#enqueueWork} - it's
 * just a cache lookup and a reply, no need to hop onto the main tick thread.
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