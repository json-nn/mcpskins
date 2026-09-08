package org.minechestplate.mcpskins.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.SkinFusionAnimator;

import java.util.List;

/**
 * Server-to-client: a fuse succeeded, play the effect. Purely decorative, so dropping it costs
 * nothing but the visuals.
 *
 * @param consumedSkinIds one id per orbiting item, in the order taken; fusing matches on rarity,
 *                        so these are often different skins and each keeps its own tint
 */
public record SkinFusionPayload(int playerId, boolean mainHand, List<String> consumedSkinIds,
                                String resultSkinId) implements CustomPacketPayload {
    public static final Type<SkinFusionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "skin_fusion"));

    /** Caps the orbiting item count, and with it the per-frame render work a fuse can cost. */
    public static final int MAX_RING_ITEMS = 12;

    // Bounded; readUtf()'s 32767 default is far beyond any real skin id.
    private static final int MAX_SKIN_ID_LENGTH = 256;

    public static final StreamCodec<FriendlyByteBuf, SkinFusionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SkinFusionPayload::playerId,
            ByteBufCodecs.BOOL, SkinFusionPayload::mainHand,
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH).apply(ByteBufCodecs.list(MAX_RING_ITEMS)),
            SkinFusionPayload::consumedSkinIds,
            ByteBufCodecs.stringUtf8(MAX_SKIN_ID_LENGTH), SkinFusionPayload::resultSkinId,
            SkinFusionPayload::new
    );

    public SkinFusionPayload {
        consumedSkinIds = List.copyOf(consumedSkinIds);
    }

    /**
     * Trims the ring on the way out, since a datapack rarity can declare any fuse cost it likes
     * and the receiving side renders one item per entry.
     */
    public static SkinFusionPayload create(Player player, InteractionHand hand,
                                           List<String> consumedSkinIds, String resultSkinId) {
        List<String> ring = consumedSkinIds.size() > MAX_RING_ITEMS
                ? consumedSkinIds.subList(0, MAX_RING_ITEMS)
                : consumedSkinIds;
        return new SkinFusionPayload(player.getId(), hand == InteractionHand.MAIN_HAND, ring, resultSkinId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> SkinFusionAnimator.onFusion(this));
    }
}
