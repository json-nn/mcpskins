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
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.client.render.TaczGeoModelInjector;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Client-side setup: resource reload listeners, item tint handlers, key mapping
 * registration, and the mod list "Config" button.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    /** Clears client-side skin caches on every resource reload so a resource pack change can't leave stale data behind. */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((PreparableReloadListener) (preparationBarrier, resourceManager,
                                                                 preparationsProfiler, reloadProfiler,
                                                                 backgroundExecutor, gameExecutor) ->
                CompletableFuture.completedFuture((Void) null)
                        .thenCompose(preparationBarrier::wait)
                        // All on gameExecutor. Closing a DynamicTexture is a GL call, and the
                        // render thread touches these caches every frame - clearing them off
                        // the background executor raced it. None of this is worth a hop.
                        .thenRunAsync(() -> {
                            SkinAssetResolver.clearCache();
                            PatchedGunDisplayCache.clear();
                            GunModelPatcher.clear();
                            TaczGeoModelInjector.reset();
                            RefitButtonPositionScreen.clearBackgroundCache();
                            ClientSkinAssetCache.clearAll();
                            MCPSkins.LOGGER.info("[MCPSkins] Client resources reloaded - skin caches cleared.");
                        }, gameExecutor));
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            // tintIndex 0 is "layer0" (the item's background) in the model JSON
            if (tintIndex == 0) {
                String skinId = TACZSkinHelper.readCustomString(stack, "SkinToUnlock");
                if (skinId != null) {
                    // Indexed lookup - this runs per tint query, i.e. per item render.
                    SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
                    if (lookup != null) {
                        return lookup.skin().labelColor() | 0xFF000000; // force opaque alpha
                    }
                }
            }
            return 0xFFFFFFFF; // opaque white fallback (unknown skin, or tintIndex 1)

        }, ModItems.SKIN_UNLOCK_ITEM.get());
    }

    /** Registers the {@link ArmoryKeybinds#OPEN_ARMORY} key mapping (must happen on the MOD bus). */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ArmoryKeybinds.OPEN_ARMORY);
    }

    /** Registers the mod list "Config" button - opens {@link MCPSkinsConfigScreen}. */
    @SubscribeEvent
    public static void registerConfigScreen(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(MCPSkins.MOD_ID).ifPresent(container ->
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (ctr, parentScreen) -> new MCPSkinsConfigScreen(parentScreen)));
    }
}