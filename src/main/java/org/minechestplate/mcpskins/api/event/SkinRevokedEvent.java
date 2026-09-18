package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import org.minechestplate.mcpskins.api.RevokeSource;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, server side, once per skin actually removed from a
 * player. Clearing a player's whole set fires one of these per skin they had.
 * <p>
 * Not cancellable: it reports a change that already happened.
 */
public class SkinRevokedEvent extends Event {

    private final ServerPlayer player;
    private final String skinId;
    private final RevokeSource source;

    public SkinRevokedEvent(ServerPlayer player, String skinId, RevokeSource source) {
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

    public RevokeSource getSource() {
        return source;
    }
}
