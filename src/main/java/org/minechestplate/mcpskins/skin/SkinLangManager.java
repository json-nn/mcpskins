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
 * Loads {@code data/<namespace>/skin_lang/<locale>.json} (flat key to string objects), merged
 * across packs in sorted id order; a later file wins a duplicate key.
 */
public class SkinLangManager extends SimpleJsonResourceReloadListener {
    public static final SkinLangManager INSTANCE = new SkinLangManager();

    public SkinLangManager() {
        super(new GsonBuilder().create(), "skin_lang");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Map<String, String>> byLocale = new HashMap<>();

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

    private static String localeOf(ResourceLocation location) {
        String path = location.getPath();
        int slash = path.lastIndexOf('/');
        return (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.ROOT);
    }
}
