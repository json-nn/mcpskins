package org.minechestplate.mcpskins.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinProgress;

import java.util.HashMap;
import java.util.Map;

/** Server to client: all leveling counters by root id, on login, respawn, dimension change and every counted kill. */
public record SyncSkinProgressPayload(Map<String, SkinProgress.Counters> progress) implements CustomPacketPayload {
    public static final Type<SyncSkinProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "sync_skin_progress"));

    public static final StreamCodec<FriendlyByteBuf, SyncSkinProgressPayload> CODEC = CustomPacketPayload.codec(
            SyncSkinProgressPayload::write, SyncSkinProgressPayload::new
    );

    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_SKIN_ID_LENGTH = 256;

    public SyncSkinProgressPayload(FriendlyByteBuf buffer) {
        this(readMap(buffer));
    }

    // Never pre-size from the wire value (see SyncUnlocksPayload).
    private static Map<String, SkinProgress.Counters> readMap(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Skin progress size " + size + " is out of range [0, " + MAX_ENTRIES + "]");
        }
        Map<String, SkinProgress.Counters> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String rootId = buffer.readUtf(MAX_SKIN_ID_LENGTH);
            map.put(rootId, new SkinProgress.Counters(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
        }
        return map;
    }

    // Leaves out entries the reader would refuse, so the packet always decodes.
    public void write(FriendlyByteBuf buffer) {
        Map<String, SkinProgress.Counters> sendable = new HashMap<>();
        for (Map.Entry<String, SkinProgress.Counters> entry : progress.entrySet()) {
            if (sendable.size() >= MAX_ENTRIES) break;
            if (entry.getKey() != null && entry.getKey().length() <= MAX_SKIN_ID_LENGTH && entry.getValue() != null) {
                sendable.put(entry.getKey(), entry.getValue());
            }
        }
        buffer.writeVarInt(sendable.size());
        for (Map.Entry<String, SkinProgress.Counters> entry : sendable.entrySet()) {
            buffer.writeUtf(entry.getKey(), MAX_SKIN_ID_LENGTH);
            buffer.writeVarInt(Math.max(0, entry.getValue().kills()));
            buffer.writeVarInt(Math.max(0, entry.getValue().headshots()));
            buffer.writeVarInt(Math.max(0, entry.getValue().playerKills()));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            Player clientPlayer = context.player();
            if (clientPlayer != null) {
                clientPlayer.setData(SkinAttachment.SKIN_PROGRESS, new HashMap<>(progress));
            }
        });
    }
}
