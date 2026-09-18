package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.minechestplate.mcpskins.api.UnlockSource;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, server side, when a player gains a skin.
 *
 * @see Pre
 * @see Post
 */
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
     * Before the skin is added, for single unlocks only ({@link UnlockSource#COMMAND},
     * {@link UnlockSource#UNLOCK_ITEM}, {@link UnlockSource#API}). The skin is known to exist and
     * to not already be owned by the time this fires.
     * <p>
     * Cancelling leaves the player's set untouched, makes the unlock report
     * {@link org.minechestplate.mcpskins.api.UnlockResult#CANCELLED}, and does not consume the
     * unlock item that triggered it.
     */
    public static class Pre extends SkinUnlockEvent implements ICancellableEvent {
        public Pre(ServerPlayer player, String skinId, UnlockSource source) {
            super(player, skinId, source);
        }
    }

    /**
     * After the skin was added and the client was synced. Fires once per skin actually gained,
     * including for the bulk sources {@link UnlockSource#UNLOCK_ALL} and
     * {@link UnlockSource#DEFAULT_GRANT}, which have no {@link Pre}.
     */
    public static class Post extends SkinUnlockEvent {
        public Post(ServerPlayer player, String skinId, UnlockSource source) {
            super(player, skinId, source);
        }
    }
}
