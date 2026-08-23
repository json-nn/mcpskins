package org.minechestplate.mcpskins.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.SkinFusionAnimator;

/**
 * Server-to-client: a fuse just succeeded on {@code playerId}, play the effect. Purely
 * decorative - the items were already consumed and the result already granted before this
 * was sent, so dropping it costs nothing but the visuals.
 *
 * @param playerId   entity id of the fusing player, resolved against the client's own level
 * @param mainHand   which hand fused, only used to decide which side the first item leaves from
 * @param ringSize   how many items to orbit, already clamped by {@link #create}
 * @param fromSkinId skin the consumed items carried, for their tint and the trail color
 * @param toSkinId   skin that was rolled, for the item revealed at the center
 */
public record SkinFusionPayload(int playerId, boolean mainHand, int ringSize,
                                String fromSkinId, String toSkinId) implements CustomPacketPayload {
    public static final Type<SkinFusionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "skin_fusion"));

    public static final int MIN_RING_ITEMS = 1;
    public static final int MAX_RING_ITEMS = 12;

    // Bounded for the same reason as ApplySkinPayload: readUtf()'s 32767 default is far
    // beyond any real skin id.
    private static final int MAX_SKIN_ID_LENGTH = 256;

    public static final StreamCodec<FriendlyByteBuf, SkinFusionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SkinFusionPayload::playerId,
            ByteBufCodecs.BOOL, SkinFusionPayload::mainHand,
            ByteBufCodecs.VAR_INT, SkinFusionPayload::ringSize,
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH), SkinFusionPayload::fromSkinId,
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH), SkinFusionPayload::toSkinId,
            SkinFusionPayload::new
    );

    /**
     * Clamps the ring size on the way out, since a datapack rarity can declare any fuse cost
     * it likes and the receiving side renders one item per ring slot.
     */
    public static SkinFusionPayload create(Player player, InteractionHand hand, int fuseCost,
                                           String fromSkinId, String toSkinId) {
        return new SkinFusionPayload(player.getId(), hand == InteractionHand.MAIN_HAND,
                Mth.clamp(fuseCost, MIN_RING_ITEMS, MAX_RING_ITEMS), fromSkinId, toSkinId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> SkinFusionAnimator.onFusion(this));
    }
}
