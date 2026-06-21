package org.minechestplate.mcpskins.skin.network;

import net.minecraft.client.Minecraft;
import org.minechestplate.mcpskins.skin.client.gui.SkinBrowserScreen;

public class ClientPayloadHandler {
    public static void handleOpenSkinBrowser() {
        // Выполняется строго на клиенте, открывая браузер скинов
        Minecraft.getInstance().setScreen(new SkinBrowserScreen());
    }
}