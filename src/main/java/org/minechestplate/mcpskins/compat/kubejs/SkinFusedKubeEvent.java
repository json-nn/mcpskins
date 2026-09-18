package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.SkinFuseEvent;

import java.util.List;

/** {@code MCPSkinsEvents.fused}. Informational: the items are spent and the reward handed out. */
public class SkinFusedKubeEvent implements KubeEvent {

    private final SkinFuseEvent.Post event;

    SkinFusedKubeEvent(SkinFuseEvent.Post event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    /** One entry per item spent, so the same id can appear more than once. */
    public List<String> getConsumedSkinIds() {
        return event.getConsumedSkinIds();
    }

    public String getResultSkinId() {
        return event.getResultSkinId();
    }
}
