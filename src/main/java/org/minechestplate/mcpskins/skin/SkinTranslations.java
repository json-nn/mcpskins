package org.minechestplate.mcpskins.skin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translations for pack-authored text: skin names, descriptions, collections, unlock hints and
 * rarity tiers.
 * <p>
 * Skin packs never reach the client, so a {@code lang} folder in one would never be loaded. The
 * server loads every locale from {@code data/<namespace>/skin_lang/} and sends each client the
 * one table it needs. Keys are derived from ids, and anything missing falls back to the text in
 * the skin file, so a partial translation is a normal state rather than an error.
 */
public final class SkinTranslations {

    public static final String DEFAULT_LOCALE = "en_us";

    /** Every locale, held by the server. Empty on a client. */
    private static volatile Map<String, Map<String, String>> tables = Map.of();

    /** The one merged table a client was sent. Empty on a server. */
    private static volatile Map<String, String> clientTable = Map.of();

    private SkinTranslations() {
    }

    public static String skinKey(String skinId, String field) {
        return "skin." + sanitize(skinId) + '.' + field;
    }

    public static String rarityKey(String rarityId) {
        return "rarity." + sanitize(rarityId);
    }

    /** Collections are free text, so the key is a slug; one entry covers the whole set. */
    public static String collectionKey(String collection) {
        return "collection." + sanitize(collection);
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString();
    }

    /** Server side, on reload. */
    public static void load(Map<String, Map<String, String>> byLocale) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        byLocale.forEach((locale, entries) -> copy.put(locale, Map.copyOf(entries)));
        tables = Map.copyOf(copy);
    }

    /** The table to send a client: the default locale, overlaid with the requested one. */
    public static Map<String, String> tableFor(String locale) {
        Map<String, String> merged = new HashMap<>(tables.getOrDefault(DEFAULT_LOCALE, Map.of()));
        if (locale != null && !locale.equals(DEFAULT_LOCALE)) {
            merged.putAll(tables.getOrDefault(locale, Map.of()));
        }
        return merged;
    }

    /** Client side, when the server answers a request. */
    public static void acceptFromServer(Map<String, String> table) {
        clientTable = table == null ? Map.of() : Map.copyOf(table);
    }

    public static void clearClient() {
        clientTable = Map.of();
    }

    /** Uses the table this client was sent. Falls back to {@code literal}. */
    public static String text(String key, String literal) {
        String value = clientTable.get(key);
        return value == null || value.isBlank() ? literal : value;
    }

    /** Server side, for text addressed to one player. Falls back to {@code literal}. */
    public static String text(String locale, String key, String literal) {
        Map<String, String> table = tables.get(locale);
        String value = table == null ? null : table.get(key);
        if (value == null) {
            table = tables.get(DEFAULT_LOCALE);
            value = table == null ? null : table.get(key);
        }
        return value == null || value.isBlank() ? literal : value;
    }

    public static String name(SkinDataModels.SkinEntry entry) {
        return text(skinKey(entry.id(), "name"), entry.name());
    }

    public static String description(SkinDataModels.SkinEntry entry) {
        return text(skinKey(entry.id(), "description"), entry.description());
    }

    public static String lockedText(SkinDataModels.SkinEntry entry) {
        return text(skinKey(entry.id(), "locked_text"), entry.lockedText());
    }

    /** Per-skin key first, then the shared collection key, then the literal. */
    public static String collection(SkinDataModels.SkinEntry entry) {
        String perSkin = text(skinKey(entry.id(), "collection"), null);
        if (perSkin != null) return perSkin;
        return text(collectionKey(entry.collection()), entry.collection());
    }

    public static String rarityName(SkinDataModels.Rarity rarity) {
        return text(rarityKey(rarity.id()), rarity.displayName());
    }

    public static String rarityName(String locale, SkinDataModels.Rarity rarity) {
        return text(locale, rarityKey(rarity.id()), rarity.displayName());
    }
}
