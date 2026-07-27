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
 * Server-to-client: "the path you asked about doesn't exist." Cached client-side as a
 * terminal negative result for the rest of the session.
 */
public record SkinAssetMissingPayload(String path) implements CustomPacketPayload {
    public static final Type<SkinAssetMissingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "skin_asset_missing"));

    public static final StreamCodec<FriendlyByteBuf, SkinAssetMissingPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(512), SkinAssetMissingPayload::path,
            SkinAssetMissingPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> ClientSkinAssetCache.onMissing(path));
    }
}