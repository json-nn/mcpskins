package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.SkinEquipEvent;

/**
 * {@code MCPSkinsEvents.equip}. Cancel to stop the change.
 * <p>
 * There is no setter for the new skin on purpose: ownership is checked before this fires, so
 * swapping the target here would be a way around it.
 */
public class SkinEquipKubeEvent implements KubeEvent {

    private final SkinEquipEvent event;

    SkinEquipKubeEvent(SkinEquipEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    /** "MAIN_HAND" or "OFF_HAND". */
    public String getHand() {
        return event.getHand().name();
    }

    /** The TACZ gun or attachment id of the held item. */
    public String getBaseId() {
        return event.getBaseId();
    }

    /** Null when the item was wearing no skin. */
    public String getPreviousSkinId() {
        return event.getPreviousSkinId();
    }

    /** Null when the player is taking the skin off. */
    public String getNewSkinId() {
        return event.getNewSkinId();
    }

    public boolean getUnequip() {
        return event.isUnequip();
    }
}
