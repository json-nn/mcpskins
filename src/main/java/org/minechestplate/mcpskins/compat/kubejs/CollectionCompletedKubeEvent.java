package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.CollectionCompletedEvent;

/** {@code MCPSkinsEvents.collectionCompleted}. Fires once, on the transition to complete. */
public class CollectionCompletedKubeEvent implements KubeEvent {

    private final CollectionCompletedEvent event;

    CollectionCompletedKubeEvent(CollectionCompletedEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getCollection() {
        return event.getCollection();
    }
}
