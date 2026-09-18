package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.MCPSkinsAPI;
import org.minechestplate.mcpskins.api.SkinInfo;
import org.minechestplate.mcpskins.api.event.SkinUnlockEvent;

/** {@code MCPSkinsEvents.unlocked}. Informational: the skin is already granted. */
public class SkinUnlockedKubeEvent implements KubeEvent {

    private final SkinUnlockEvent.Post event;

    SkinUnlockedKubeEvent(SkinUnlockEvent.Post event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getSkinId() {
        return event.getSkinId();
    }

    /** COMMAND, UNLOCK_ITEM, API, UNLOCK_ALL or DEFAULT_GRANT. */
    public String getSource() {
        return event.getSource().name();
    }

    public SkinInfo getSkin() {
        return MCPSkinsAPI.getSkin(event.getSkinId());
    }
}
