package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.KubeEvent;
import net.minecraft.server.level.ServerPlayer;
import org.minechestplate.mcpskins.api.MCPSkinsAPI;
import org.minechestplate.mcpskins.api.SkinInfo;
import org.minechestplate.mcpskins.api.event.SkinLevelUpEvent;

/**
 * {@code MCPSkinsEvents.levelUp}. Informational: the level is reached and its variant's unlock
 * was attempted, which an {@code unlock} handler may have cancelled.
 */
public class SkinLevelUpKubeEvent implements KubeEvent {

    private final SkinLevelUpEvent event;

    SkinLevelUpKubeEvent(SkinLevelUpEvent event) {
        this.event = event;
    }

    public ServerPlayer getPlayer() {
        return event.getPlayer();
    }

    public String getRootId() {
        return event.getRootId();
    }

    public String getVariantId() {
        return event.getVariantId();
    }

    public int getLevel() {
        return event.getLevel();
    }

    public SkinInfo getVariant() {
        return MCPSkinsAPI.getSkin(event.getVariantId());
    }
}
