package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

/** The {@code MCPSkinsEvents} group. Every handler is server side. */
public interface MCPSkinsKubeEvents {

    EventGroup GROUP = EventGroup.of("MCPSkinsEvents");

    EventHandler UNLOCK = GROUP.server("unlock", () -> SkinUnlockKubeEvent.class).hasResult();

    EventHandler UNLOCKED = GROUP.server("unlocked", () -> SkinUnlockedKubeEvent.class);

    EventHandler REVOKED = GROUP.server("revoked", () -> SkinRevokedKubeEvent.class);

    EventHandler COLLECTION_COMPLETED =
            GROUP.server("collectionCompleted", () -> CollectionCompletedKubeEvent.class);

    EventHandler EQUIP = GROUP.server("equip", () -> SkinEquipKubeEvent.class).hasResult();

    EventHandler FUSE = GROUP.server("fuse", () -> SkinFuseKubeEvent.class).hasResult();

    EventHandler FUSED = GROUP.server("fused", () -> SkinFusedKubeEvent.class);

    EventHandler LEVEL_UP = GROUP.server("levelUp", () -> SkinLevelUpKubeEvent.class);
}
