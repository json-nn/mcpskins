package org.minechestplate.mcpskins.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.render.ClientSkinAssetCache;
import org.minechestplate.mcpskins.client.render.GunModelPatcher;
import org.minechestplate.mcpskins.client.render.AttachmentSkinPatcher;
import org.minechestplate.mcpskins.client.render.LrDisplayPatcher;
import org.minechestplate.mcpskins.client.render.PatchedGunDisplayCache;
import org.minechestplate.mcpskins.client.render.SkinAnimationInjector;
import org.minechestplate.mcpskins.client.render.SkinAssetResolver;
import org.minechestplate.mcpskins.client.render.SkinFusionAnimator;
import org.minechestplate.mcpskins.client.render.TaczGeoModelInjector;

/**
 * Drops all session state on disconnect: asset keys carry no server identity, so anything kept
 * would leak into the next server. Keep in step with the reload clear in {@link ClientModEvents}.
 * Main thread only (texture release is a GL call).
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworkEvents {

    private ClientNetworkEvents() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientTranslationSync.request(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SkinAssetResolver.clearCache();
        PatchedGunDisplayCache.clear();
        AttachmentSkinPatcher.clear();
        LrDisplayPatcher.clear();
        GunModelPatcher.clear();
        TaczGeoModelInjector.reset();
        SkinAnimationInjector.reset();
        ClientSkinAssetCache.clearAll();
        SkinFusionAnimator.reset();
        TACZRefitSkinOverlay.resetSessionState();
        ClientTranslationSync.reset();
        MCPSkins.LOGGER.info("[MCPSkins] Disconnected - skin caches and GPU textures released.");
    }
}
