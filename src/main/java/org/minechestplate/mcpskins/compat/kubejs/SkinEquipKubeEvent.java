package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.SkinEquipEvent;

/** {@code MCPSkinsEvents.equip}. Cancel to stop the change. No new-skin setter: it would bypass the ownership check. */
public class SkinEquipKubeEvent implements KubeEvent {

    private final SkinEquipEvent event;

    SkinEquipKubeEvent(SkinEquipEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getHand() {
        return event.getHand().name();
    }

    public String getBaseId() {
        return event.getBaseId();
    }

    public String getPreviousSkinId() {
        return event.getPreviousSkinId();
    }

    public String getNewSkinId() {
        return event.getNewSkinId();
    }

    public boolean getUnequip() {
        return event.isUnequip();
    }
}
