package org.minechestplate.mcpskins;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.minechestplate.mcpskins.command.SkinCommand;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.network.ApplySkinPayload;
import org.minechestplate.mcpskins.network.RequestTranslationsPayload;
import org.minechestplate.mcpskins.network.ServerboundRateLimiter;
import org.minechestplate.mcpskins.network.SkinFusionPayload;
import org.minechestplate.mcpskins.network.SyncRegistryPayload;
import org.minechestplate.mcpskins.network.SyncTranslationsPayload;
import org.minechestplate.mcpskins.network.SyncUnlocksPayload;
import org.minechestplate.mcpskins.network.asset.RequestSkinAssetPayload;
import org.minechestplate.mcpskins.network.asset.ServerSkinAssetStore;
import org.minechestplate.mcpskins.network.asset.SkinAssetChunkPayload;
import org.minechestplate.mcpskins.network.asset.SkinAssetMissingPayload;
import org.minechestplate.mcpskins.network.asset.SkinAssetThrottledPayload;
import org.minechestplate.mcpskins.pack.MCPSkinsPackFinder;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.SkinLangManager;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.SkinTranslations;
import org.slf4j.Logger;

import java.util.ArrayList;

@Mod(MCPSkins.MOD_ID)
public class MCPSkins {
    public static final String MOD_ID = "mcpskins";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MCPSkins(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerNetworking);
        modEventBus.addListener(this::onAddPackFinders);

        modContainer.registerConfig(ModConfig.Type.CLIENT, MCPSkinsClientConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, MCPSkinsServerConfig.SPEC);

        SkinComponents.DATA_COMPONENTS.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);

        // Sync unlocked skins on join, respawn, and dimension change.
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangeDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogOut);

        ModItems.ITEMS.register(modEventBus);
        SkinAttachment.ATTACHMENTS.register(modEventBus);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SkinCommand.register(event.getDispatcher());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("MCPSkins common setup complete.");
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        // No ordering guarantee against SkinManager - skins hold rarity ids and resolve on
        // read, so either load order is fine.
        event.addListener(RarityManager.INSTANCE);
        event.addListener(SkinManager.INSTANCE);
        event.addListener(SkinLangManager.INSTANCE);
        event.addListener(ServerSkinAssetStore.INSTANCE);
    }

    /**
     * SERVER_DATA only - assets are streamed over the network by {@link ServerSkinAssetStore}
     * instead, so clients never need a local copy of a skin pack.
     */
    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addRepositorySource(MCPSkinsPackFinder.INSTANCE);
        } else if (FMLEnvironment.dist.isClient()) {
            // Memory-backed, so streamed assets a shader needs to look up by path are visible
            // to the resource manager without any pack on the player's disk.
            event.addRepositorySource(consumer -> {
                net.minecraft.server.packs.repository.Pack pack =
                        org.minechestplate.mcpskins.client.pack.ClientSkinResourcePack.createPack();
                if (pack != null) {
                    consumer.accept(pack);
                }
            });
        }
    }

    private void onPlayerLogIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncSkinsToClient(player);
        }
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncSkinsToClient(player);
        }
    }

    private void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncSkinsToClient(player);
        }
    }

    private void onPlayerLogOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerSkinAssetStore.INSTANCE.forgetPlayer(player.getUUID());
            ServerboundRateLimiter.forget(player.getUUID());
        }
    }

    /** Pushes the table for the player's own language, so a /reload lands without a round trip. */
    private void sendTranslations(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new SyncTranslationsPayload(SkinTranslations.tableFor(player.clientInformation().language())));
    }

    private void syncSkinsToClient(ServerPlayer player) {
        java.util.Set<String> unlockedSkins = player.getData(SkinAttachment.UNLOCKED_SKINS);
        PacketDistributor.sendToPlayer(player, new SyncUnlocksPayload(new ArrayList<>(unlockedSkins)));
    }

    private void registerNetworking(final RegisterPayloadHandlersEvent event) {
        // 1.10.0: adds the skin translation request/sync pair. The registrar isn't optional,
        // so mismatched versions can't connect.
        PayloadRegistrar registrar = event.registrar("1.10.0");

        registrar.playToClient(SyncRegistryPayload.TYPE, SyncRegistryPayload.CODEC, SyncRegistryPayload::handleData);
        registrar.playToClient(SyncUnlocksPayload.TYPE, SyncUnlocksPayload.CODEC, SyncUnlocksPayload::handleData);
        registrar.playToClient(SkinFusionPayload.TYPE, SkinFusionPayload.CODEC, SkinFusionPayload::handleData);
        registrar.playToClient(SyncTranslationsPayload.TYPE, SyncTranslationsPayload.CODEC, SyncTranslationsPayload::handleData);

        // Payload handlers run on the main thread by default; executesOn(NETWORK) is an
        // explicit opt-in. Both serverbound handlers need it: they screen the request off the
        // tick loop and hop back to MAIN only for work that touches game state. Asset requests
        // also do blocking file/zip I/O plus Deflate, which would stall every tick.
        registrar = registrar.executesOn(HandlerThread.NETWORK);
        registrar.playToServer(ApplySkinPayload.TYPE, ApplySkinPayload.CODEC, ApplySkinPayload::handleData);
        registrar.playToServer(RequestSkinAssetPayload.TYPE, RequestSkinAssetPayload.CODEC, RequestSkinAssetPayload::handleData);
        registrar.playToServer(RequestTranslationsPayload.TYPE, RequestTranslationsPayload.CODEC, RequestTranslationsPayload::handleData);

        // Back to MAIN: these register bytes with the client's GL texture manager.
        registrar = registrar.executesOn(HandlerThread.MAIN);
        registrar.playToClient(SkinAssetChunkPayload.TYPE, SkinAssetChunkPayload.CODEC, SkinAssetChunkPayload::handleData);
        registrar.playToClient(SkinAssetMissingPayload.TYPE, SkinAssetMissingPayload.CODEC, SkinAssetMissingPayload::handleData);
        registrar.playToClient(SkinAssetThrottledPayload.TYPE, SkinAssetThrottledPayload.CODEC, SkinAssetThrottledPayload::handleData);
    }

    /**
     * Fires once when a player joins and once per {@code /reload}, which is also exactly when
     * the set of default-unlocked skins can change, so the grant rides along here rather than
     * costing anything per tick.
     */
    private void onDatapackSync(OnDatapackSyncEvent event) {
        SyncRegistryPayload skinPayload = SyncRegistryPayload.createFromServer();
        if (event.getPlayer() != null) {
            PacketDistributor.sendToPlayer(event.getPlayer(), skinPayload);
            sendTranslations(event.getPlayer());
            SkinAttachment.grantDefaultUnlocks(event.getPlayer());
        } else {
            PacketDistributor.sendToAllPlayers(skinPayload);
            for (ServerPlayer player : event.getPlayerList().getPlayers()) {
                sendTranslations(player);
                SkinAttachment.grantDefaultUnlocks(player);
            }
        }
    }
}