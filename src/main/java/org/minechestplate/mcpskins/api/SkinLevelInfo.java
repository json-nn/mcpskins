package org.minechestplate.mcpskins.api;

import org.minechestplate.mcpskins.skin.SkinDataModels;

/**
 * One level of a root skin. Reached when every non-zero requirement holds and every earlier level is reached.
 *
 * @param level       2 or higher; the root itself is level 1
 * @param variantId   the variant this level unlocks
 * @param kills       required kills, or 0 when this level does not ask for any
 * @param headshots   required headshot kills, or 0
 * @param playerKills required player kills, or 0
 */
public record SkinLevelInfo(int level, String variantId, int kills, int headshots, int playerKills) {

    /** Internal: builds the public view of one entry of a skin's {@code levels}. */
    public static SkinLevelInfo of(int level, SkinDataModels.SkinLevel step) {
        return new SkinLevelInfo(level, step.variant(),
                step.required(SkinDataModels.LevelCounter.KILLS),
                step.required(SkinDataModels.LevelCounter.HEADSHOTS),
                step.required(SkinDataModels.LevelCounter.PLAYER_KILLS));
    }
}
