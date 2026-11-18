package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Server side, on {@code NeoForge.EVENT_BUS}: an unlock completed a collection. Fires only on the
 * transition to complete, once per bulk grant. Stock {@code default:} entries never count.
 */
public class CollectionCompletedEvent extends Event {

    private final ServerPlayer player;
    private final String collection;

    public CollectionCompletedEvent(ServerPlayer player, String collection) {
        this.player = player;
        this.collection = collection;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public String getCollection() {
        return collection;
    }
}
