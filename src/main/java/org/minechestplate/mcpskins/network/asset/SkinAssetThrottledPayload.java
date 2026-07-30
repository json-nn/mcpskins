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
 * Server-to-client: "I have this asset, but you've asked for too much too fast - try again
 * in {@code retryAfterMillis}."
 * <p>
 * This exists so the rate limiter has something to say. It used to simply drop the request
 * and return, which left the client's entry on PENDING with no timeout and no retry - so a
 * single throttled request meant that texture silently never loaded again for the whole
 * session, and the weapon quietly kept its base skin. Every other outcome (bytes, or
 * {@link SkinAssetMissingPayload}) already produced a reply; this was the one path that
 * didn't, and a state machine with one silent dead end is a state machine that hangs.
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
