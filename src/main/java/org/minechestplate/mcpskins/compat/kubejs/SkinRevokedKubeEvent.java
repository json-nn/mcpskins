package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.SkinRevokedEvent;

/** {@code MCPSkinsEvents.revoked}. Informational: the skin is already gone. */
public class SkinRevokedKubeEvent implements KubeEvent {

    private final SkinRevokedEvent event;

    SkinRevokedKubeEvent(SkinRevokedEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getSkinId() {
        return event.getSkinId();
    }

    public String getSource() {
        return event.getSource().name();
    }
}
