package org.minechestplate.mcpskins.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.network.RequestTranslationsPayload;
import org.minechestplate.mcpskins.skin.SkinTranslations;

/**
 * Asks the server for translations matching this client's language, on login and on every
 * resource reload, which is what switching language triggers.
 */
public final class ClientTranslationSync {

    private static String requestedLocale;

    private ClientTranslationSync() {
    }

    /** Sends only when the language actually changed, unless {@code force}. */
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
