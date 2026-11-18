package org.minechestplate.mcpskins.client;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.gui.settings.MCPSkinsConfigScreen;
import org.minechestplate.mcpskins.client.gui.settings.RefitButtonPositionScreen;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.AttachmentSkinPatcher;
import org.minechestplate.mcpskins.client.render.LrDisplayPatcher;
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAnimationInjector;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.client.render.TaczGeoModelInjector;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    // Clears skin caches on every resource reload. Keep in step with ClientNetworkEvents.
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((PreparableReloadListener) (preparationBarrier, resourceManager,
                                                                 preparationsProfiler, reloadProfiler,
                                                                 backgroundExecutor, gameExecutor) ->
                CompletableFuture.completedFuture((Void) null)
                        .thenCompose(preparationBarrier::wait)
                        // gameExecutor: texture release is a GL call and the render thread reads these caches every frame.
                        .thenRunAsync(() -> {
                            SkinAssetResolver.clearCache();
                            PatchedGunDisplayCache.clear();
                            AttachmentSkinPatcher.clear();
                            LrDisplayPatcher.clear();
                            GunModelPatcher.clear();
                            TaczGeoModelInjector.reset();
                            SkinAnimationInjector.reset();
                            RefitButtonPositionScreen.clearBackgroundCache();
                            ClientSkinAssetCache.clearAll();
                            // A language switch reloads resources; this is where it is noticed.
                            ClientTranslationSync.request(false);
                            MCPSkins.LOGGER.info("[MCPSkins] Client resources reloaded - skin caches cleared.");
                        }, gameExecutor));
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            // layer0 takes the rarity colour; runs per item render, so the lookup must stay indexed.
            if (tintIndex == 0) {
                String skinId = TACZSkinHelper.readCustomString(stack, "SkinToUnlock");
                if (skinId != null) {
                    SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
                    if (lookup != null) {
                        return lookup.skin().labelColor() | 0xFF000000;
                    }
                }
            }
            return 0xFFFFFFFF;

        }, ModItems.SKIN_UNLOCK_ITEM.get());
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ArmoryKeybinds.OPEN_ARMORY);
    }

    @SubscribeEvent
    public static void registerConfigScreen(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(MCPSkins.MOD_ID).ifPresent(container ->
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (ctr, parentScreen) -> new MCPSkinsConfigScreen(parentScreen)));
    }
}