package org.minechestplate.mcpskins.api;

/** Outcome of a single-skin unlock. Only {@link #UNLOCKED} means the player's set changed. */
public enum UnlockResult {
    UNLOCKED,
    ALREADY_OWNED,
    /** No such skin in {@link org.minechestplate.mcpskins.skin.SkinManager}, or a {@code default:} entry. */
    UNKNOWN_SKIN,
    /**
     * The skin exists, but TACZ has not loaded its gun or attachment. Not returned while
     * {@code hideUnavailableSkins} is off in the server config.
     */
    UNAVAILABLE,
    /** A listener cancelled {@code SkinUnlockEvent.Pre}. */
    CANCELLED;

    public boolean succeeded() {
        return this == UNLOCKED;
    }
}
