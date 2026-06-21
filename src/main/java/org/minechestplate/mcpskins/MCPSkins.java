package org.minechestplate.mcpskins;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.command.SkinCommand;
import org.minechestplate.mcpskins.skin.network.ApplySkinPayload;
import org.minechestplate.mcpskins.skin.network.OpenSkinBrowserPayload;
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

        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);

        // Регистрация событий игрока для синхронизации скинов
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
        LOGGER.info("MCPCases common setup complete.");
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(SkinManager.INSTANCE);
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
        final PayloadRegistrar registrar = event.registrar("1.0.0");

        registrar.playToServer(ApplySkinPayload.TYPE, ApplySkinPayload.CODEC, ApplySkinPayload::handleData);
        registrar.playToClient(SyncRegistryPayload.TYPE, SyncRegistryPayload.CODEC, SyncRegistryPayload::handleData);
        registrar.playToClient(SyncUnlocksPayload.TYPE, SyncUnlocksPayload.CODEC, SyncUnlocksPayload::handleData);
        registrar.playToClient(OpenSkinBrowserPayload.TYPE, OpenSkinBrowserPayload.CODEC, OpenSkinBrowserPayload::handleData);
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