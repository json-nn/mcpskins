package org.minechestplate.mcpskins.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinManager;

import java.util.List;

/**
 * Fired on {@code NeoForge.EVENT_BUS}, server side, around a skin fusion.
 *
 * @see Pre
 * @see Post
 */
public abstract class SkinFuseEvent extends Event {

    private final ServerPlayer player;

    protected SkinFuseEvent(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    /**
     * After every check has passed and the result has been rolled, but before anything is
     * consumed. This is the hook for pity rules and weighted overrides.
     * <p>
     * Cancelling consumes nothing, hands out nothing, and tells the player the fuse was blocked.
     */
    public static class Pre extends SkinFuseEvent implements ICancellableEvent {

        private final String rarityId;
        private final String heldSkinId;
        private final String rolledSkinId;
        private String resultSkinId;

        public Pre(ServerPlayer player, String rarityId, String heldSkinId, String rolledSkinId) {
            super(player);
            this.rarityId = rarityId;
            this.heldSkinId = heldSkinId;
            this.rolledSkinId = rolledSkinId;
            this.resultSkinId = rolledSkinId;
        }

        /** The rarity being spent. */
        public String getRarityId() {
            return rarityId;
        }

        /** The skin on the item the player was holding. */
        public String getHeldSkinId() {
            return heldSkinId;
        }

        /** What the roll produced, unaffected by any override. */
        public String getRolledSkinId() {
            return rolledSkinId;
        }

        /** What the player will actually get: the roll, or an accepted override. */
        public String getResultSkinId() {
            return resultSkinId;
        }

        /**
         * Replaces the rolled skin. A null, unknown or {@code default:} id is refused with a
         * warning in the log and the roll stands, so a bad script cannot hand out a skin that
         * does not exist.
         */
        public void setResultSkinId(String skinId) {
            if (skinId == null || SkinAttachment.isDefaultEntry(skinId)
                    || SkinManager.INSTANCE.findSkin(skinId) == null) {
                MCPSkins.LOGGER.warn("[MCPSkins] Ignoring fuse result override '{}': no such skin. "
                        + "Keeping the rolled '{}'.", skinId, rolledSkinId);
                return;
            }
            this.resultSkinId = skinId;
        }
    }

    /** After the items were consumed and the reward handed out. */
    public static class Post extends SkinFuseEvent {

        private final List<String> consumedSkinIds;
        private final String resultSkinId;

        public Post(ServerPlayer player, List<String> consumedSkinIds, String resultSkinId) {
            super(player);
            this.consumedSkinIds = List.copyOf(consumedSkinIds);
            this.resultSkinId = resultSkinId;
        }

        /** One entry per item spent, so the same id can appear more than once. */
        public List<String> getConsumedSkinIds() {
            return consumedSkinIds;
        }

        public String getResultSkinId() {
            return resultSkinId;
        }
    }
}
