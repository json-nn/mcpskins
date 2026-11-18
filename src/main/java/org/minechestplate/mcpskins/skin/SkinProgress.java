package org.minechestplate.mcpskins.skin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.minechestplate.mcpskins.network.Payloads;
import org.minechestplate.mcpskins.network.SyncSkinProgressPayload;

import java.util.HashMap;
import java.util.Map;

/**
 * Leveling counters per player and root, in {@link SkinAttachment#SKIN_PROGRESS}. Only roots with
 * levels are tracked. Every write goes through here and syncs to the owning client.
 */
public final class SkinProgress {

    // Saturates rather than wrapping.
    public record Counters(int kills, int headshots, int playerKills) {

        public static final Counters ZERO = new Counters(0, 0, 0);

        public static final Codec<Counters> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("kills", 0).forGetter(Counters::kills),
                Codec.INT.optionalFieldOf("headshots", 0).forGetter(Counters::headshots),
                Codec.INT.optionalFieldOf("player_kills", 0).forGetter(Counters::playerKills)
        ).apply(instance, Counters::new));

        public int get(SkinDataModels.LevelCounter counter) {
            return switch (counter) {
                case KILLS -> kills;
                case HEADSHOTS -> headshots;
                case PLAYER_KILLS -> playerKills;
            };
        }

        Counters plusKill(boolean headshot, boolean playerKill) {
            return new Counters(inc(kills, true), inc(headshots, headshot), inc(playerKills, playerKill));
        }

        private static int inc(int value, boolean add) {
            return add && value < Integer.MAX_VALUE ? value + 1 : value;
        }
    }

    private SkinProgress() {
    }

    // Never null: a root the player has no kills with reads as Counters.ZERO.
    public static Counters get(Player player, String rootId) {
        Counters counters = player.getData(SkinAttachment.SKIN_PROGRESS).get(rootId);
        return counters == null ? Counters.ZERO : counters;
    }

    static void put(ServerPlayer player, String rootId, Counters counters) {
        Map<String, Counters> updated = new HashMap<>(player.getData(SkinAttachment.SKIN_PROGRESS));
        updated.put(rootId, counters);
        player.setData(SkinAttachment.SKIN_PROGRESS, updated);
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        Payloads.sendTo(player, new SyncSkinProgressPayload(player.getData(SkinAttachment.SKIN_PROGRESS)));
    }

    // 1 plus each consecutive level whose requirements all hold; a later level met early does not count.
    public static int levelFor(SkinDataModels.SkinEntry root, Counters counters) {
        int level = 1;
        for (SkinDataModels.SkinLevel step : root.levels()) {
            if (!meets(step, counters)) break;
            level++;
        }
        return level;
    }

    public static int maxLevel(SkinDataModels.SkinEntry root) {
        return 1 + root.levels().size();
    }

    public static boolean meets(SkinDataModels.SkinLevel step, Counters counters) {
        for (Map.Entry<SkinDataModels.LevelCounter, Integer> condition : step.requires().entrySet()) {
            if (counters.get(condition.getKey()) < condition.getValue()) return false;
        }
        return true;
    }
}
