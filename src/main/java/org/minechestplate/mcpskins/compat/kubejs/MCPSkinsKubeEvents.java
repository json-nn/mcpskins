package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventHandler;

/**
 * The {@code MCPSkinsEvents} group scripts bind to. Every handler is server side, because every
 * event behind it is posted on the server.
 */
public interface MCPSkinsKubeEvents {

    EventGroup GROUP = EventGroup.of("MCPSkinsEvents");

    /** Cancellable. Before a single skin is granted. */
    EventHandler UNLOCK = GROUP.server("unlock", () -> SkinUnlockKubeEvent.class).hasResult();

    /** After a skin was granted, once per skin, bulk grants included. */
    EventHandler UNLOCKED = GROUP.server("unlocked", () -> SkinUnlockedKubeEvent.class);

    /** After a skin was taken away, once per skin. */
    EventHandler REVOKED = GROUP.server("revoked", () -> SkinRevokedKubeEvent.class);

    /** When a player completes a collection. */
    EventHandler COLLECTION_COMPLETED =
            GROUP.server("collectionCompleted", () -> CollectionCompletedKubeEvent.class);

    /** Cancellable. Before a held item's skin changes. */
    EventHandler EQUIP = GROUP.server("equip", () -> SkinEquipKubeEvent.class).hasResult();

    /** Cancellable, and can override the rolled result. Before anything is consumed. */
    EventHandler FUSE = GROUP.server("fuse", () -> SkinFuseKubeEvent.class).hasResult();

    /** After a fuse completed. */
    EventHandler FUSED = GROUP.server("fused", () -> SkinFusedKubeEvent.class);
}
