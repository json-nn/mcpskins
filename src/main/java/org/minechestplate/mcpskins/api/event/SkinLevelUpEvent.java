package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Server side, on {@code NeoForge.EVENT_BUS}, once per level crossed on a root skin. The root is
 * level 1, so the first {@code levels} entry is level 2.
 * <p>
 * Not cancellable. Fires after the variant's unlock was attempted; that unlock
 * ({@code SkinUnlockEvent.Pre}, source {@code LEVEL_UP}) may have been cancelled, so check ownership.
 */
public class SkinLevelUpEvent extends Event {

    private final ServerPlayer player;
    private final String rootId;
    private final String variantId;
    private final int level;

    public SkinLevelUpEvent(ServerPlayer player, String rootId, String variantId, int level) {
        this.player = player;
        this.rootId = rootId;
        this.variantId = variantId;
        this.level = level;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public String getRootId() {
        return rootId;
    }

    public String getVariantId() {
        return variantId;
    }

    /** The level just reached, 2 or higher. */
    public int getLevel() {
        return level;
    }
}
