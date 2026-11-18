package org.minechestplate.mcpskins.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.network.RequestTranslationsPayload;
import org.minechestplate.mcpskins.skin.SkinTranslations;

/** Requests this client's locale table on login and on every resource reload (a language switch reloads). */
public final class ClientTranslationSync {

    private static String requestedLocale;

    private ClientTranslationSync() {
    }

    public static void request(boolean force) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        String locale = mc.getLanguageManager().getSelected();
        if (locale == null || locale.isBlank()) {
            locale = SkinTranslations.DEFAULT_LOCALE;
        }
        if (!force && locale.equals(requestedLocale)) return;

        requestedLocale = locale;
        PacketDistributor.sendToServer(new RequestTranslationsPayload(locale));
        MCPSkins.LOGGER.debug("[MCPSkins] Requested skin translations for '{}'.", locale);
    }

    public static void reset() {
        requestedLocale = null;
        SkinTranslations.clearClient();
    }
}
