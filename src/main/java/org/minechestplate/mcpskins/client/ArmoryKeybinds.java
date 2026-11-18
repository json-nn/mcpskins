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

/** Armory hotkey. The mapping is registered in {@code ClientModEvents}. */
@EventBusSubscriber(modid = MCPSkins.MOD_ID, value = Dist.CLIENT)
public final class ArmoryKeybinds {

    public static final String CATEGORY = "key.categories.mcpskins";

    // K: free in vanilla and TACZ's defaults (R/G/B/V).
    public static final KeyMapping OPEN_ARMORY = new KeyMapping(
            "key.mcpskins.open_armory", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private ArmoryKeybinds() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_ARMORY.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new SkinArmoryScreen());
            }
        }
    }
}
