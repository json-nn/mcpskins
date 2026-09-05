package org.minechestplate.mcpskins.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinTranslations;

/**
 * Client-to-server: send me the skin translations for this locale. The client asks rather than
 * the server guessing, because the language can change mid-session and the client is the only
 * side that knows immediately.
 */
public record RequestTranslationsPayload(String locale) implements CustomPacketPayload {
    public static final Type<RequestTranslationsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "request_translations"));

    private static final int MAX_LOCALE_LENGTH = 32;

    /** A language switch is a menu click, so anything above this is not a person. */
    private static final int MAX_PER_SECOND = 4;

    public static final StreamCodec<FriendlyByteBuf, RequestTranslationsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_LOCALE_LENGTH), RequestTranslationsPayload::locale,
            RequestTranslationsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Runs on the network thread; reads an immutable snapshot and sends, touching no game state. */
    public void handleData(IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!ServerboundRateLimiter.allow(player.getUUID(), MAX_PER_SECOND)) return;

        String requested = locale == null || locale.isBlank() ? SkinTranslations.DEFAULT_LOCALE : locale;
        PacketDistributor.sendToPlayer(player, new SyncTranslationsPayload(SkinTranslations.tableFor(requested)));
    }
}
