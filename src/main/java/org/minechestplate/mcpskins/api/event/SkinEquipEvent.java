package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, server side, when a player is about to change the skin on
 * the item they are holding. Fires after every ownership and validity check has passed and
 * before the stack is touched, so cancelling leaves the item exactly as it was.
 * <p>
 * There is deliberately no setter for the new skin: the ownership check runs before this event
 * and letting a listener swap the target afterwards would be a way around it.
 */
public class SkinEquipEvent extends Event implements ICancellableEvent {

    private final ServerPlayer player;
    private final InteractionHand hand;
    private final String baseId;
    private final String previousSkinId;
    private final String newSkinId;

    public SkinEquipEvent(ServerPlayer player, InteractionHand hand, String baseId,
                          String previousSkinId, String newSkinId) {
        this.player = player;
        this.hand = hand;
        this.baseId = baseId;
        this.previousSkinId = previousSkinId;
        this.newSkinId = newSkinId;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public InteractionHand getHand() {
        return hand;
    }

    /** The TACZ gun or attachment id of the held item. */
    public String getBaseId() {
        return baseId;
    }

    /** Null when the item was wearing no skin. */
    public String getPreviousSkinId() {
        return previousSkinId;
    }

    /** Null when the player is taking the skin off. */
    public String getNewSkinId() {
        return newSkinId;
    }

    /** Whether this is a removal rather than a change. */
    public boolean isUnequip() {
        return newSkinId == null;
    }
}
