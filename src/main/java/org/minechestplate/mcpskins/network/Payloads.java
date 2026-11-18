package org.minechestplate.mcpskins.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Use instead of {@link PacketDistributor#sendToPlayer}, which throws for a connection that never
 * negotiated the channel. Fake and mock players have such connections and reach the sync hooks and API.
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
