package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, server side, when an unlock leaves a player owning every
 * skin of a named collection they did not already own in full.
 * <p>
 * Only the transition fires it, so further unlocks within an already complete collection stay
 * quiet. A bulk grant is evaluated once after the whole batch. Stock {@code default:} entries
 * carry no collection and never count toward one.
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
