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
 * Server-to-client: one chunk of a compressed asset transfer.
 * <p>
 * NeoForge caps clientbound payloads around 1 MiB, so assets are split into
 * {@link ServerSkinAssetStore#CHUNK_SIZE} pieces. The data codec is bounded to the same size.
 * transferId only needs to be unique per sender.
 */
public record SkinAssetChunkPayload(long transferId, String path, int index, int totalChunks, byte[] data)
        implements CustomPacketPayload {
    public static final Type<SkinAssetChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "skin_asset_chunk"));

    public static final StreamCodec<FriendlyByteBuf, SkinAssetChunkPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, SkinAssetChunkPayload::transferId,
            ByteBufCodecs.stringUtf8(512), SkinAssetChunkPayload::path,
            ByteBufCodecs.VAR_INT, SkinAssetChunkPayload::index,
            ByteBufCodecs.VAR_INT, SkinAssetChunkPayload::totalChunks,
            ByteBufCodecs.byteArray(ServerSkinAssetStore.CHUNK_SIZE), SkinAssetChunkPayload::data,
            SkinAssetChunkPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> ClientSkinAssetCache.onChunk(transferId, path, index, totalChunks, data));
    }
}