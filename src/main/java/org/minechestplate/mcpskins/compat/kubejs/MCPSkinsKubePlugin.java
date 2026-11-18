package org.minechestplate.mcpskins.compat.kubejs;

import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import net.neoforged.neoforge.common.NeoForge;
import org.minechestplate.mcpskins.api.CollectionProgress;
import org.minechestplate.mcpskins.api.MCPSkinsAPI;
import org.minechestplate.mcpskins.api.SkinInfo;
import org.minechestplate.mcpskins.api.SkinLevelInfo;
import org.minechestplate.mcpskins.api.SkinProgressInfo;
import org.minechestplate.mcpskins.api.UnlockResult;
import org.minechestplate.mcpskins.api.event.CollectionCompletedEvent;
import org.minechestplate.mcpskins.api.event.SkinEquipEvent;
import org.minechestplate.mcpskins.api.event.SkinFuseEvent;
import org.minechestplate.mcpskins.api.event.SkinLevelUpEvent;
import org.minechestplate.mcpskins.api.event.SkinRevokedEvent;
import org.minechestplate.mcpskins.api.event.SkinUnlockEvent;

/**
 * Binds {@link MCPSkinsAPI} as {@code MCPSkins} and forwards the NeoForge events to {@code MCPSkinsEvents}.
 * Loaded only by KubeJS through {@code kubejs.plugins.txt}; keep game logic out of this package.
 */
public class MCPSkinsKubePlugin implements KubeJSPlugin {

    @Override
    public void init() {
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onUnlockPre);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onUnlockPost);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onRevoked);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onCollectionCompleted);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onEquip);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onFusePre);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onFusePost);
        NeoForge.EVENT_BUS.addListener(MCPSkinsKubePlugin::onLevelUp);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("MCPSkins", MCPSkinsAPI.class);
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(MCPSkinsKubeEvents.GROUP);
    }

    @Override
    public void registerClasses(ClassFilter filter) {
        filter.allow(MCPSkinsAPI.class);
        filter.allow(SkinInfo.class);
        filter.allow(CollectionProgress.class);
        filter.allow(UnlockResult.class);
        filter.allow(SkinProgressInfo.class);
        filter.allow(SkinLevelInfo.class);
    }

    private static void onUnlockPre(SkinUnlockEvent.Pre event) {
        MCPSkinsKubeEvents.UNLOCK.post(new SkinUnlockKubeEvent(event)).applyCancel(event);
    }

    private static void onUnlockPost(SkinUnlockEvent.Post event) {
        MCPSkinsKubeEvents.UNLOCKED.post(new SkinUnlockedKubeEvent(event));
    }

    private static void onRevoked(SkinRevokedEvent event) {
        MCPSkinsKubeEvents.REVOKED.post(new SkinRevokedKubeEvent(event));
    }

    private static void onCollectionCompleted(CollectionCompletedEvent event) {
        MCPSkinsKubeEvents.COLLECTION_COMPLETED.post(new CollectionCompletedKubeEvent(event));
    }

    private static void onEquip(SkinEquipEvent event) {
        MCPSkinsKubeEvents.EQUIP.post(new SkinEquipKubeEvent(event)).applyCancel(event);
    }

    private static void onFusePre(SkinFuseEvent.Pre event) {
        MCPSkinsKubeEvents.FUSE.post(new SkinFuseKubeEvent(event)).applyCancel(event);
    }

    private static void onFusePost(SkinFuseEvent.Post event) {
        MCPSkinsKubeEvents.FUSED.post(new SkinFusedKubeEvent(event));
    }

    private static void onLevelUp(SkinLevelUpEvent event) {
        MCPSkinsKubeEvents.LEVEL_UP.post(new SkinLevelUpKubeEvent(event));
    }
}
