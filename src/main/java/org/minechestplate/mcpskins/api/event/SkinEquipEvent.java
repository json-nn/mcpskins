package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Server side, on {@code NeoForge.EVENT_BUS}: a held item's skin is about to change. Fires after
 * every ownership and validity check, before the stack is touched; cancelling leaves it as it was.
 * <p>
 * No setter for the new skin on purpose: ownership is checked before this fires, so a setter would bypass it.
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

    public boolean isUnequip() {
        return newSkinId == null;
    }
}
