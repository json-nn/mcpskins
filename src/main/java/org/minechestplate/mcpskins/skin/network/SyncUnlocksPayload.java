package org.minechestplate.mcpskins.skin.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-to-client packet that syncs a player's set of unlocked skin IDs.
 * Sent on login, respawn, and dimension change.
 */
public record SyncUnlocksPayload(List<String> unlockedSkins) implements CustomPacketPayload {
    public static final Type<SyncUnlocksPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "sync_unlocks"));

    public static final StreamCodec<FriendlyByteBuf, SyncUnlocksPayload> CODEC = CustomPacketPayload.codec(
            SyncUnlocksPayload::write, SyncUnlocksPayload::new
    );

    public SyncUnlocksPayload(FriendlyByteBuf buffer) {
        this(readList(buffer));
    }

    private static List<String> readList(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buffer.readUtf());
        }
        return list;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(unlockedSkins.size());
        for (String skin : unlockedSkins) {
            buffer.writeUtf(skin);
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
                // Update the local client player's attachment cache
                Set<String> newUnlocks = new HashSet<>(unlockedSkins);
                clientPlayer.setData(SkinAttachment.UNLOCKED_SKINS, newUnlocks);
            }
        });
    }
}