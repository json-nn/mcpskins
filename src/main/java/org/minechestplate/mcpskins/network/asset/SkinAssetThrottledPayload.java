package org.minechestplate.mcpskins.network.asset;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;

/**
 * Server-to-client: "I have this, but you're asking too fast - retry in
 * {@code retryAfterMillis}."
 * <p>
 * Exists so the rate limiter has something to say. Dropping the request silently left the
 * client on PENDING forever, and that asset never loaded again for the session.
 */
public record SkinAssetThrottledPayload(String path, int retryAfterMillis) implements CustomPacketPayload {
    public static final Type<SkinAssetThrottledPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "skin_asset_throttled"));

    public static final StreamCodec<FriendlyByteBuf, SkinAssetThrottledPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(512), SkinAssetThrottledPayload::path,
            ByteBufCodecs.VAR_INT, SkinAssetThrottledPayload::retryAfterMillis,
            SkinAssetThrottledPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> ClientSkinAssetCache.onThrottled(path, retryAfterMillis));
    }
}
