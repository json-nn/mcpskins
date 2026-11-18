package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.minechestplate.mcpskins.api.UnlockSource;

/** Server side, on {@code NeoForge.EVENT_BUS}, when a player gains a skin. */
public abstract class SkinUnlockEvent extends Event {

    private final ServerPlayer player;
    private final String skinId;
    private final UnlockSource source;

    protected SkinUnlockEvent(ServerPlayer player, String skinId, UnlockSource source) {
        this.player = player;
        this.skinId = skinId;
        this.source = source;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public String getSkinId() {
        return skinId;
    }

    public UnlockSource getSource() {
        return source;
    }

    /**
     * Before a single unlock ({@link UnlockSource#COMMAND}, {@link UnlockSource#UNLOCK_ITEM},
     * {@link UnlockSource#API}, {@link UnlockSource#LEVEL_UP}) of a known, not yet owned skin.
     * Cancelling adds nothing (variants included), reports
     * {@link org.minechestplate.mcpskins.api.UnlockResult#CANCELLED} and keeps the unlock item.
     */
    public static class Pre extends SkinUnlockEvent implements ICancellableEvent {
        public Pre(ServerPlayer player, String skinId, UnlockSource source) {
            super(player, skinId, source);
        }
    }

    /**
     * After the skin was added and the client synced, once per skin gained. Also fires for the bulk
     * sources {@link UnlockSource#UNLOCK_ALL}, {@link UnlockSource#DEFAULT_GRANT} and
     * {@link UnlockSource#VARIANT}, which have no {@link Pre}.
     */
    public static class Post extends SkinUnlockEvent {
        public Post(ServerPlayer player, String skinId, UnlockSource source) {
            super(player, skinId, source);
        }
    }
}
