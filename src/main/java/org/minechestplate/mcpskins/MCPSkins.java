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
import org.minechestplate.mcpskins.network.Payloads;
import org.minechestplate.mcpskins.network.SyncRegistryPayload;
import org.minechestplate.mcpskins.network.SyncSkinProgressPayload;
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
import org.minechestplate.mcpskins.skin.SkinAvailability;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.SkinLangManager;
import org.minechestplate.mcpskins.skin.SkinLeveling;
import org.minechestplate.mcpskins.skin.SkinProgress;
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

        NeoForge.EVENT_BUS.addListener(this::onPlayerLogIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangeDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogOut);
        NeoForge.EVENT_BUS.addListener(SkinLeveling::onGunKill);

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
        // No ordering guarantee against SkinManager, but skins resolve rarity ids on read.
        event.addListener(RarityManager.INSTANCE);
        event.addListener(SkinManager.INSTANCE);
        event.addListener(SkinLangManager.INSTANCE);
        event.addListener(ServerSkinAssetStore.INSTANCE);
    }

    // Skin packs register as data only; their assets are streamed by ServerSkinAssetStore.
    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addRepositorySource(MCPSkinsPackFinder.INSTANCE);
        } else if (FMLEnvironment.dist.isClient()) {
            // Memory-backed, so shaders can look up streamed assets (PBR maps) by path.
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

    // Pushed rather than requested, so a /reload lands without a client round trip.
    private void sendTranslations(ServerPlayer player) {
        Payloads.sendTo(player,
                new SyncTranslationsPayload(SkinTranslations.tableFor(player.clientInformation().language())));
    }

    private void syncSkinsToClient(ServerPlayer player) {
        java.util.Set<String> unlockedSkins = player.getData(SkinAttachment.UNLOCKED_SKINS);
        Payloads.sendTo(player, new SyncUnlocksPayload(new ArrayList<>(unlockedSkins)));
        SkinProgress.sync(player);
    }

    private void registerNetworking(final RegisterPayloadHandlersEvent event) {
        // Bump on any wire-format change. The registrar is not optional, so mismatched versions cannot connect.
        PayloadRegistrar registrar = event.registrar("1.11.0");

        registrar.playToClient(SyncRegistryPayload.TYPE, SyncRegistryPayload.CODEC, SyncRegistryPayload::handleData);
        registrar.playToClient(SyncUnlocksPayload.TYPE, SyncUnlocksPayload.CODEC, SyncUnlocksPayload::handleData);
        registrar.playToClient(SkinFusionPayload.TYPE, SkinFusionPayload.CODEC, SkinFusionPayload::handleData);
        registrar.playToClient(SyncTranslationsPayload.TYPE, SyncTranslationsPayload.CODEC, SyncTranslationsPayload::handleData);
        registrar.playToClient(SyncSkinProgressPayload.TYPE, SyncSkinProgressPayload.CODEC, SyncSkinProgressPayload::handleData);

        // Serverbound handlers validate off the tick loop (asset requests do blocking zip I/O); MAIN only for game state.
        registrar = registrar.executesOn(HandlerThread.NETWORK);
        registrar.playToServer(ApplySkinPayload.TYPE, ApplySkinPayload.CODEC, ApplySkinPayload::handleData);
        registrar.playToServer(RequestSkinAssetPayload.TYPE, RequestSkinAssetPayload.CODEC, RequestSkinAssetPayload::handleData);
        registrar.playToServer(RequestTranslationsPayload.TYPE, RequestTranslationsPayload.CODEC, RequestTranslationsPayload::handleData);

        // MAIN: these register bytes with the GL texture manager.
        registrar = registrar.executesOn(HandlerThread.MAIN);
        registrar.playToClient(SkinAssetChunkPayload.TYPE, SkinAssetChunkPayload.CODEC, SkinAssetChunkPayload::handleData);
        registrar.playToClient(SkinAssetMissingPayload.TYPE, SkinAssetMissingPayload.CODEC, SkinAssetMissingPayload::handleData);
        registrar.playToClient(SkinAssetThrottledPayload.TYPE, SkinAssetThrottledPayload.CODEC, SkinAssetThrottledPayload::handleData);
    }

    // Join and every /reload: the points where starter skins and owned roots' variants can change.
    private void onDatapackSync(OnDatapackSyncEvent event) {
        // Not earlier: TACZ's common index is empty until its own reload listeners have applied.
        SkinAvailability.invalidate();
        SkinAvailability.report(SkinManager.INSTANCE.getRawRegistry());

        SyncRegistryPayload skinPayload = SyncRegistryPayload.createFromServer();
        if (event.getPlayer() != null) {
            Payloads.sendTo(event.getPlayer(), skinPayload);
            sendTranslations(event.getPlayer());
            SkinAttachment.grantDefaultUnlocks(event.getPlayer());
            SkinAttachment.reconcileVariants(event.getPlayer());
        } else {
            PacketDistributor.sendToAllPlayers(skinPayload);
            for (ServerPlayer player : event.getPlayerList().getPlayers()) {
                sendTranslations(player);
                SkinAttachment.grantDefaultUnlocks(player);
                SkinAttachment.reconcileVariants(player);
            }
        }
    }
}