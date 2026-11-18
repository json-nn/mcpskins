package org.minechestplate.mcpskins.client;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.animation.IFPAnimationInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.minechestplate.mcpskins.MCPSkins;

/**
 * Re-inits the first-person animation state machine after a skin swap replaced the display
 * instance; otherwise the gun sits in bind pose until a slot switch. See docs/ai/tacz-integration.md.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class FirstPersonAnimationFix {

    private FirstPersonAnimationFix() {
    }

    // LOWEST so a real triggerDraw() this frame runs first and leaves this a no-op.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recoverAnimationState(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !mc.options.getCameraType().isFirstPerson()) {
            return; // TACZ's tickAnimation(Post) covers third person
        }

        IFPAnimationInstance instance = FirstPersonRenderHandler.getActiveAnimationInstance();
        if (instance == null) return;

        // Not getMainHandItem(): mid put-away this is the outgoing stack, which needReInit declines.
        ItemStack stack = instance.currentItem();
        if (stack.isEmpty()) return;

        if (!(IClientItemExtensions.of(stack.getItem()).getCustomRenderer()
                instanceof AnimateGeoItemRenderer<?, ?> renderer)) {
            return;
        }

        if (renderer.needReInit(stack)) {
            renderer.tryInit(stack, player, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }
}
