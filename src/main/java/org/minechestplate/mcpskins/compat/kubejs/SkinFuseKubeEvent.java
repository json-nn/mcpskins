package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.event.SkinFuseEvent;

/**
 * {@code MCPSkinsEvents.fuse}. Cancel to block the fuse, or call {@code setResultSkinId} to
 * decide what comes out. An unknown or stock id is refused with a log warning and the original
 * roll stands.
 */
public class SkinFuseKubeEvent implements KubeEvent {

    private final SkinFuseEvent.Pre event;

    SkinFuseKubeEvent(SkinFuseEvent.Pre event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    /** The rarity being spent. */
    public String getRarityId() {
        return event.getRarityId();
    }

    /** The skin on the item the player was holding. */
    public String getHeldSkinId() {
        return event.getHeldSkinId();
    }

    /** What the roll produced, whatever the override says. */
    public String getRolledSkinId() {
        return event.getRolledSkinId();
    }

    /** What the player will get: the roll, or an accepted override. */
    public String getResultSkinId() {
        return event.getResultSkinId();
    }

    public void setResultSkinId(String skinId) {
        event.setResultSkinId(skinId);
    }
}
