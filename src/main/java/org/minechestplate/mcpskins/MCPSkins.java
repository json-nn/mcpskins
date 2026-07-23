package org.minechestplate.mcpskins;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.minechestplate.mcpskins.config.MCPSkinsClientConfig;
import org.minechestplate.mcpskins.config.MCPSkinsServerConfig;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.pack.MCPSkinsPackFinder;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinComponents;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.command.SkinCommand;
import org.minechestplate.mcpskins.skin.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.network.SyncRegistryPayload;
import org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload;
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

        // Physical-side pack format for the finder's synthetic pack.mcmeta.
        Dist side = FMLLoader.getDist();
        MCPSkinsPackFinder.INSTANCE.packType = side.isClient() ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;

        SkinComponents.DATA_COMPONENTS.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);

        // Sync unlocked skins on join, respawn, and dimension change.
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangeDimension);

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
        event.addListener(SkinManager.INSTANCE);
    }

    /** Registers the {@code mcpskins/} folder scanner so skin packs load automatically. */
    private void onAddPackFinders(AddPackFindersEvent event) {
        event.addRepositorySource(MCPSkinsPackFinder.INSTANCE);
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

    private void syncSkinsToClient(ServerPlayer player) {
        java.util.Set<String> unlockedSkins = player.getData(SkinAttachment.UNLOCKED_SKINS);
        PacketDistributor.sendToPlayer(player, new SyncUnlocksPayload(new ArrayList<>(unlockedSkins)));
    }

    private void registerNetworking(final RegisterPayloadHandlersEvent event) {
        // Bumped for SyncRegistryPayload's new fields (rarity/collection/description/isNew).
        final PayloadRegistrar registrar = event.registrar("1.1.0");

        registrar.playToServer(ApplySkinPayload.TYPE, ApplySkinPayload.CODEC, ApplySkinPayload::handleData);
        registrar.playToClient(SyncRegistryPayload.TYPE, SyncRegistryPayload.CODEC, SyncRegistryPayload::handleData);
        registrar.playToClient(SyncUnlocksPayload.TYPE, SyncUnlocksPayload.CODEC, SyncUnlocksPayload::handleData);
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        SyncRegistryPayload skinPayload = SyncRegistryPayload.createFromServer();
        if (event.getPlayer() != null) {
            PacketDistributor.sendToPlayer(event.getPlayer(), skinPayload);
        } else {
            PacketDistributor.sendToAllPlayers(skinPayload);
        }
    }
}