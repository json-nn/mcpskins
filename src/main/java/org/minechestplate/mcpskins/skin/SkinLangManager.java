package org.minechestplate.mcpskins.skin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads translations from {@code data/<namespace>/skin_lang/<locale>.json}, one flat
 * {@code key: value} object per locale, the same shape a resource pack's language file uses.
 * <p>
 * Every pack contributes to the same locale, so a translation pack can ship nothing but a
 * language file for someone else's skins. Packs are merged in a fixed order and a later one
 * wins a duplicate key, which is what lets a server override a pack's wording.
 */
public class SkinLangManager extends SimpleJsonResourceReloadListener {
    public static final SkinLangManager INSTANCE = new SkinLangManager();

    public SkinLangManager() {
        super(new GsonBuilder().create(), "skin_lang");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Map<String, String>> byLocale = new HashMap<>();

        // Sorted so two packs defining the same key resolve the same way every reload.
        List<ResourceLocation> ordered = new ArrayList<>(files.keySet());
        ordered.sort(ResourceLocation::compareTo);

        for (ResourceLocation location : ordered) {
            String locale = localeOf(location);
            JsonElement element = files.get(location);
            if (!element.isJsonObject()) {
                MCPSkins.LOGGER.error("Skin language file {} is not a JSON object; skipping.", location);
                continue;
            }
            Map<String, String> table = byLocale.computeIfAbsent(locale, key -> new HashMap<>());
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    table.put(entry.getKey(), value.getAsString());
                } else {
                    MCPSkins.LOGGER.warn("Skin language key '{}' in {} is not a string; skipping it.",
                            entry.getKey(), location);
                }
            }
        }

        SkinTranslations.load(byLocale);
        if (!byLocale.isEmpty()) {
            MCPSkins.LOGGER.info("Loaded skin translations for {} locale(s): {}.",
                    byLocale.size(), byLocale.keySet());
        }
    }

    /** The file name is the locale, so {@code data/mypack/skin_lang/ru_ru.json} is ru_ru. */
    private static String localeOf(ResourceLocation location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        return (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.ROOT);
    }
}
