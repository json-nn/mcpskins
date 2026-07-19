package org.minechestplate.mcpskins;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.minechestplate.mcpskins.item.ModItems;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.client.ArmoryKeybinds;
import org.minechestplate.mcpskins.skin.render.GunModelPatcher;
import org.minechestplate.mcpskins.skin.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.skin.render.SkinAssetResolver;

import java.util.concurrent.CompletableFuture;

/**
 * Client-side setup: resource reload listeners, item tint handlers, and key mapping
 * registration.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    /**
     * Clears the client-side skin caches ({@link SkinAssetResolver}, {@link PatchedGunDisplayCache},
     * {@link GunModelPatcher}) on every resource reload. Without this, stale texture-existence
     * lookups and patched display instances could survive a resource pack change and point at
     * data that no longer matches.
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((PreparableReloadListener) (preparationBarrier, resourceManager,
                                                                 preparationsProfiler, reloadProfiler,
                                                                 backgroundExecutor, gameExecutor) ->
                CompletableFuture.runAsync(() -> {
                    SkinAssetResolver.clearCache();
                    PatchedGunDisplayCache.clear();
                    GunModelPatcher.clear();
                    MCPSkins.LOGGER.info("[MCPSkins] Client resources reloaded - skin caches cleared.");
                }, backgroundExecutor)
                        .thenCompose(preparationBarrier::wait));
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

    /**
     * Registers the {@link ArmoryKeybinds#OPEN_ARMORY} key mapping. Must happen on the MOD bus
     * (unlike the tick polling in {@link ArmoryKeybinds}, which runs on the GAME bus), so
     * registration and handling live in separate classes as the API requires.
     */
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ArmoryKeybinds.OPEN_ARMORY);
    }
}