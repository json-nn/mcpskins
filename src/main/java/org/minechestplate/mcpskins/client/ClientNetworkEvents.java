package org.minechestplate.mcpskins.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.client.render.TaczGeoModelInjector;

/**
 * Drops session-scoped client state on disconnect.
 * <p>
 * Asset keys are {@code namespace:path} with nothing server-specific in them, so two servers
 * can ship different bytes under the same key. Anything kept across sessions - GPU textures,
 * PRESENT/MISSING verdicts, injected geo-models - leaks into the next one.
 * <p>
 * Runs on the client main thread; closing a {@code DynamicTexture} is a GL call.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworkEvents {

    private ClientNetworkEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SkinAssetResolver.clearCache();
        PatchedGunDisplayCache.clear();
        GunModelPatcher.clear();
        TaczGeoModelInjector.reset();
        ClientSkinAssetCache.clearAll();
        TACZRefitSkinOverlay.resetSessionState();
        MCPSkins.LOGGER.info("[MCPSkins] Disconnected - skin caches and GPU textures released.");
    }
}
