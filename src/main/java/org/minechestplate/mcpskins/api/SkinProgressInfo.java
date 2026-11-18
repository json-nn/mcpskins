package org.minechestplate.mcpskins.api;

/**
 * A player's leveling progress on one root skin. Read-only: progress only moves through kills.
 *
 * @param rootId   the skin the counters belong to, even when a variant was asked about
 * @param level    1 until the first level is reached
 * @param maxLevel 1 for a skin with no levels
 */
public record SkinProgressInfo(String rootId, int kills, int headshots, int playerKills,
                               int level, int maxLevel) {

    public boolean maxed() {
        return level >= maxLevel;
    }
}
