package org.minechestplate.mcpskins.api;

/** What caused a skin to be unlocked. Carried by the unlock events so scripts can tell them apart. */
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
    DEFAULT_GRANT
}
