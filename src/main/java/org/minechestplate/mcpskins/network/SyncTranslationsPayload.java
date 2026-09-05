package org.minechestplate.mcpskins.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinTranslations;

import java.util.HashMap;
import java.util.Map;

/** Server-to-client: the merged translation table for the locale the client asked for. */
public record SyncTranslationsPayload(Map<String, String> entries) implements CustomPacketPayload {
    public static final Type<SyncTranslationsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "sync_translations"));

    private static final int MAX_ENTRIES = 16384;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 512;

    public static final StreamCodec<FriendlyByteBuf, SyncTranslationsPayload> CODEC = CustomPacketPayload.codec(
            SyncTranslationsPayload::write, SyncTranslationsPayload::new
    );

    public SyncTranslationsPayload(FriendlyByteBuf buffer) {
        this(read(buffer));
    }

    private static Map<String, String> read(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Skin translation count " + size + " is out of range [0, " + MAX_ENTRIES + "]");
        }
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < size; i++) {
            entries.put(buffer.readUtf(MAX_KEY_LENGTH), buffer.readUtf(MAX_VALUE_LENGTH));
        }
        return entries;
    }

    private void write(FriendlyByteBuf buffer) {
        int written = 0;
        buffer.writeVarInt(Math.min(entries.size(), MAX_ENTRIES));
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (written++ >= MAX_ENTRIES) break;
            // Truncated rather than refused: the reader is bounded, and an over-long key would
            // otherwise make the whole packet undecodable and drop the client.
            buffer.writeUtf(clamp(entry.getKey(), MAX_KEY_LENGTH), MAX_KEY_LENGTH);
            buffer.writeUtf(clamp(entry.getValue(), MAX_VALUE_LENGTH), MAX_VALUE_LENGTH);
        }
        if (entries.size() > MAX_ENTRIES) {
            MCPSkins.LOGGER.warn("[MCPSkins] {} skin translations exceed the {} cap; the rest were dropped.",
                    entries.size(), MAX_ENTRIES);
        }
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> SkinTranslations.acceptFromServer(entries));
    }
}
