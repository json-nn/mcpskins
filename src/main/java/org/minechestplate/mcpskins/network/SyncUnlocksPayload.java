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

    /** Far more skins than any pack ships, but bounded so a bad size can't be believed. */
    private static final int MAX_UNLOCKS = 8192;

    /** Skin ids are short registry strings; readUtf()'s 32767 default is 128x too generous. */
    private static final int MAX_SKIN_ID_LENGTH = 256;

    public SyncUnlocksPayload(FriendlyByteBuf buffer) {
        this(readList(buffer));
    }

    /**
     * Reads the unlock list without trusting the declared size.
     * <p>
     * Note there is no {@code new ArrayList<>(size)} here, deliberately. Pre-sizing from a
     * wire value means a 5-byte packet claiming {@code Integer.MAX_VALUE} entries allocates
     * a ~16 GiB backing array before a single element is read - the list never has to
     * actually contain anything for the client to die. The count is validated first, and the
     * list grows as elements genuinely arrive.
     */
    private static List<String> readList(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_UNLOCKS) {
            throw new DecoderException("Unlock list size " + size + " is out of range [0, " + MAX_UNLOCKS + "]");
        }
        List<String> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(buffer.readUtf(MAX_SKIN_ID_LENGTH));
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