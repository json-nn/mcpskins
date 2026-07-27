package org.minechestplate.mcpskins.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.client.ArmoryKeybinds;
import org.minechestplate.mcpskins.client.gui.settings.MCPSkinsConfigScreen;
import org.minechestplate.mcpskins.client.gui.settings.RefitButtonPositionScreen;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;

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
                CompletableFuture.runAsync(() -> {
                            SkinAssetResolver.clearCache();
                            PatchedGunDisplayCache.clear();
                            GunModelPatcher.clear();
                            RefitButtonPositionScreen.clearBackgroundCache();
                        }, backgroundExecutor)
                        .thenCompose(preparationBarrier::wait)
                        // Has to run on gameExecutor, not backgroundExecutor above - closing
                        // DynamicTextures is a GL call, only valid on the main client thread.
                        .thenRunAsync(() -> {
                            ClientSkinAssetCache.clearAll();
                            MCPSkins.LOGGER.info("[MCPSkins] Client resources reloaded - skin caches cleared.");
                        }, gameExecutor));
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            // tintIndex 0 is "layer0" (the item's background) in the model JSON
            if (tintIndex == 0) {
                CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);

                if (data.contains("SkinToUnlock")) {
                    String skinId = data.copyTag().getString("SkinToUnlock");

                    for (SkinDataModels.WeaponSkins weapon : SkinManager.INSTANCE.getRegistry().values()) {
                        for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                            if (skin.id().equals(skinId)) {
                                return skin.labelColor() | 0xFF000000; // force opaque alpha
                            }
                        }
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