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
 * Re-initializes the held weapon's animation state machine in first person after it has been
 * replaced underneath the renderer. Without this the weapon draws at its bind pose - the
 * "detached hands" look - until the player switches hotbar slots.
 * <p>
 * TACZ's own recovery ({@code TickAnimationEvent.tickAnimation(Post)}) is gated on
 * <em>not</em> being in first person. There, the only caller of {@code tryInit} is
 * {@code IFPAnimationInstance.triggerDraw()}, which latches after its first call and is reset
 * only by building a new instance - which simplebedrockmodel does on a slot change, or when
 * {@code AbstractGunItem.isSame} reports a different GunId/GunDisplayId.
 * <p>
 * A skin swap changes neither id by design, yet {@code getGunDisplay} returns a different
 * instance, and {@code GunItemRendererWrapper.getStateMachine} reads the state machine off
 * that instance. F3+T does the same by rebuilding every {@code GunDisplayInstance}. Both leave
 * an uninitialized machine nothing in first person will touch. The F3+T case is a TACZ bug and
 * reproduces without this mod.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class FirstPersonAnimationFix {

    private FirstPersonAnimationFix() {
    }

    /** LOWEST so a genuine {@code triggerDraw()} this frame runs first and leaves us a no-op. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recoverAnimationState(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !mc.options.getCameraType().isFirstPerson()) {
            return; // third person is TACZ's own tickAnimation(Post)
        }

        IFPAnimationInstance instance = FirstPersonRenderHandler.getActiveAnimationInstance();
        if (instance == null) return;

        // Not getMainHandItem(): mid put-away this is still the outgoing stack, whose
        // exitingTime makes needReInit decline, leaving the sheathe animation alone.
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
