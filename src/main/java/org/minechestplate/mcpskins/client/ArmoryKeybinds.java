package org.minechestplate.mcpskins.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.gui.SkinArmoryScreen;

/**
 * Hotkey that opens {@link SkinArmoryScreen}, usable even with no weapon in hand.
 * The {@link KeyMapping} itself is registered in {@code ClientModEvents}; this class
 * only handles what happens when it's pressed.
 */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ArmoryKeybinds {

    public static final String CATEGORY = "key.categories.mcpskins";

    // Defaults to K, which doesn't collide with TACZ's default binds (R/G/B/V) or vanilla ones
    public static final KeyMapping OPEN_ARMORY = new KeyMapping(
            "key.mcpskins.open_armory", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private ArmoryKeybinds() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // Must be called in a loop, or rapid presses between ticks could be dropped
        while (OPEN_ARMORY.consumeClick()) {
            // Don't override an already-open screen (inventory, chat, another mod's GUI, etc.)
            if (mc.screen == null) {
                mc.setScreen(new SkinArmoryScreen());
            }
        }
    }
}
