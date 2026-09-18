package org.minechestplate.mcpskins.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends a payload to one player, skipping connections that never negotiated it.
 * <p>
 * {@link PacketDistributor#sendToPlayer} throws {@code UnsupportedOperationException} for such a
 * connection rather than ignoring it, which takes down whatever was iterating the player list.
 * Real clients always negotiate, but mock and fake players are {@code ServerPlayer}s with a stub
 * connection, and they do reach the sync hooks and the public API.
 */
public final class Payloads {

    private Payloads() {
    }

    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
