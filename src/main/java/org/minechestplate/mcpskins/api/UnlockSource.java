package org.minechestplate.mcpskins.api;

/** What caused a skin to be unlocked, as carried by the unlock events. Append new values only, never reorder. */
public enum UnlockSource {
    /** {@code /mcpskins give skin}. */
    COMMAND,
    /** A player right-clicked a skin unlock item. */
    UNLOCK_ITEM,
    /** {@link MCPSkinsAPI#unlock}, so another mod or a script. */
    API,
    /** {@code /mcpskins give all}. Bulk: no cancellable pre-event. */
    UNLOCK_ALL,
    /** A pack's {@code "unlocked": true} starter skins, granted on join and on reload. Bulk. */
    DEFAULT_GRANT,
    /** An unconditioned variant granted with its root, or reconciled on join and reload. Bulk. */
    VARIANT,
    /** A level variant reached through kills. Single: has a pre-event. */
    LEVEL_UP
}
