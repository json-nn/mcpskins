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
 * Drops every piece of session-scoped client state when the player leaves a server.
 * <p>
 * Skin assets are delivered by the server and keyed by {@code namespace:path}, but nothing
 * about those keys is server-specific - two servers can and will ship different bytes under
 * the same key. Without this, everything a session accumulated outlived it:
 * <ul>
 *   <li>registered {@code DynamicTexture}s stayed on the GPU, so switching servers leaked
 *       video memory and server B rendered server A's texture bytes;</li>
 *   <li>PRESENT/MISSING verdicts persisted, so an asset server A lacked was never even
 *       requested from server B;</li>
 *   <li>geo-models injected into TACZ's model registry stayed injected under the same keys.</li>
 * </ul>
 * {@code ClientSkinAssetCache.clearAll()} always documented itself as running "on disconnect",
 * but nothing ever called it that way - reload was its only trigger.
 * <p>
 * This runs on the client main thread, which is required: closing a {@code DynamicTexture}
 * is a GL call.
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
