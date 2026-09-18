package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.MCPSkinsAPI;
import org.minechestplate.mcpskins.api.SkinInfo;
import org.minechestplate.mcpskins.api.event.SkinUnlockEvent;

/** {@code MCPSkinsEvents.unlock}. Cancel to refuse the unlock. */
public class SkinUnlockKubeEvent implements KubeEvent {

    private final SkinUnlockEvent.Pre event;

    SkinUnlockKubeEvent(SkinUnlockEvent.Pre event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getSkinId() {
        return event.getSkinId();
    }

    /** One of COMMAND, UNLOCK_ITEM or API. Bulk grants never reach this event. */
    public String getSource() {
        return event.getSource().name();
    }

    /** Null only if the pack was reloaded mid-tick. */
    public SkinInfo getSkin() {
        return MCPSkinsAPI.getSkin(event.getSkinId());
    }
}
