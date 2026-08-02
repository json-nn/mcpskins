package org.minechestplate.mcpskins.skin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads rarity tiers from {@code data/<namespace>/skin_rarities/} and exposes them by id.
 * <p>
 * The five built-ins are registered in code, so the mod works with no rarity datapack at all
 * and every existing skin pack keeps loading. A file whose name matches a built-in replaces it;
 * any other name adds a tier.
 * <p>
 * Kept separate from {@link SkinManager} rather than resolving ids into {@code SkinEntry} at
 * load time: the two folders are two reload listeners with no guaranteed order between them,
 * so resolution has to happen on read.
 */
public class RarityManager extends SimpleJsonResourceReloadListener {
    private static final List<SkinDataModels.Rarity> BUILT_INS = List.of(
            builtIn("common", "Common", 0xB0B0B0, 0),
            builtIn("uncommon", "Uncommon", 0x55FF55, 100),
            builtIn("rare", "Rare", 0x5FD3FF, 200),
            builtIn("epic", "Epic", 0xB47FFF, 300),
            builtIn("legendary", "Legendary", 0xFFB347, 400));

    /**
     * @param byId   every known tier
     * @param sorted ascending by {@code order}; the Armory sorts and the default fuse ladder
     *               both read this
     * @param lowest fallback for unknown ids, never null
     */
    private record Snapshot(Map<String, SkinDataModels.Rarity> byId,
                            List<SkinDataModels.Rarity> sorted,
                            SkinDataModels.Rarity lowest) {}

    private volatile Snapshot snapshot = build(defaults());

    private static final Set<String> WARNED_UNKNOWN = ConcurrentHashMap.newKeySet();

    /**
     * Must stay below every static field above it. Constructing it seeds {@link #snapshot} from
     * {@link #BUILT_INS}, and static initializers run in textual order - declared any higher and
     * the constructor reads a null BUILT_INS.
     */
    public static final RarityManager INSTANCE = new RarityManager();

    public RarityManager() {
        super(new GsonBuilder().create(), "skin_rarities");
    }

    private static SkinDataModels.Rarity builtIn(String id, String name, int color, int order) {
        // Keeps the existing gui.mcpskins.armory.rarity_* keys, so en/ru/uk still localize.
        return new SkinDataModels.Rarity(id, name, "gui.mcpskins.armory.rarity_" + id,
                color, order, true, null, List.of());
    }

    private static Map<String, SkinDataModels.Rarity> defaults() {
        Map<String, SkinDataModels.Rarity> map = new HashMap<>();
        for (SkinDataModels.Rarity rarity : BUILT_INS) {
            map.put(rarity.id(), rarity);
        }
        return map;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManager, ProfilerFiller profilerIn) {
        Map<String, SkinDataModels.Rarity> byId = defaults();
        Set<String> seen = new HashSet<>();

        objectIn.forEach((location, element) -> {
            String id = location.getPath().toLowerCase(Locale.ROOT);
            if (!seen.add(id)) {
                MCPSkins.LOGGER.warn("[MCPSkins] Duplicate skin rarity id '{}' (from {}); the last one loaded wins.", id, location);
            }
            try {
                byId.put(id, parse(id, element.getAsJsonObject()));
            } catch (Exception e) {
                MCPSkins.LOGGER.error("[MCPSkins] Failed to parse skin rarity {}", location, e);
            }
        });

        publish(byId);
        MCPSkins.LOGGER.info("Loaded {} skin rarity tier(s).", byId.size());
    }

    private static SkinDataModels.Rarity parse(String id, JsonObject json) {
        String name = json.has("name") ? json.get("name").getAsString() : id;
        String translationKey = json.has("translation_key") ? json.get("translation_key").getAsString() : null;
        int color = json.has("color") ? Integer.decode(json.get("color").getAsString()) : 0xFFFFFF;
        int order = json.has("order") ? json.get("order").getAsInt() : 0;
        boolean fusable = !json.has("fusable") || json.get("fusable").getAsBoolean();
        Integer fuseCost = json.has("fuse_cost") ? Math.max(1, json.get("fuse_cost").getAsInt()) : null;

        return new SkinDataModels.Rarity(id, name, translationKey, color, order, fusable, fuseCost,
                parseTargets(json.get("fuses_into")));
    }

    /** Accepts a bare id, or an array of {@code {rarity, weight}} objects. */
    private static List<SkinDataModels.FuseTarget> parseTargets(JsonElement element) {
        if (element == null || element.isJsonNull()) return List.of();

        if (element.isJsonPrimitive()) {
            return List.of(new SkinDataModels.FuseTarget(element.getAsString(), 1));
        }

        List<SkinDataModels.FuseTarget> targets = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (entry.isJsonPrimitive()) {
                targets.add(new SkinDataModels.FuseTarget(entry.getAsString(), 1));
                continue;
            }
            JsonObject object = entry.getAsJsonObject();
            String rarityId = object.get("rarity").getAsString();
            int weight = object.has("weight") ? Math.max(1, object.get("weight").getAsInt()) : 1;
            targets.add(new SkinDataModels.FuseTarget(rarityId, weight));
        }
        return targets;
    }

    /** Drops fuse targets naming a tier that doesn't exist, then publishes atomically. */
    private void publish(Map<String, SkinDataModels.Rarity> byId) {
        snapshot = build(validate(byId));
    }

    private static Map<String, SkinDataModels.Rarity> validate(Map<String, SkinDataModels.Rarity> byId) {
        Map<String, SkinDataModels.Rarity> checked = new HashMap<>();
        byId.forEach((id, rarity) -> {
            if (rarity.fuseTargets().isEmpty()) {
                checked.put(id, rarity);
                return;
            }
            List<SkinDataModels.FuseTarget> live = new ArrayList<>();
            for (SkinDataModels.FuseTarget target : rarity.fuseTargets()) {
                if (byId.containsKey(target.rarityId())) {
                    live.add(target);
                } else {
                    MCPSkins.LOGGER.warn("[MCPSkins] Rarity '{}' fuses into unknown rarity '{}'; ignoring that target.",
                            id, target.rarityId());
                }
            }
            checked.put(id, new SkinDataModels.Rarity(rarity.id(), rarity.displayName(), rarity.translationKey(),
                    rarity.accentColor(), rarity.order(), rarity.fusable(), rarity.fuseCost(), live));
        });
        return checked;
    }

    private static Snapshot build(Map<String, SkinDataModels.Rarity> byId) {
        List<SkinDataModels.Rarity> sorted = new ArrayList<>(byId.values());
        sorted.sort(java.util.Comparator.comparingInt(SkinDataModels.Rarity::order)
                .thenComparing(SkinDataModels.Rarity::id));
        SkinDataModels.Rarity lowest = sorted.isEmpty() ? BUILT_INS.get(0) : sorted.get(0);
        return new Snapshot(Map.copyOf(byId), List.copyOf(sorted), lowest);
    }

    /**
     * Never null. An unrecognized id falls back to the lowest tier and warns once, so a typo
     * costs a skin its sorting position rather than its existence.
     */
    public SkinDataModels.Rarity get(String rarityId) {
        Snapshot current = snapshot;
        if (rarityId == null || rarityId.isBlank()) return current.lowest();

        SkinDataModels.Rarity rarity = current.byId().get(rarityId);
        if (rarity != null) return rarity;

        if (WARNED_UNKNOWN.add(rarityId)) {
            MCPSkins.LOGGER.warn("[MCPSkins] Skin references undefined rarity '{}'; treating it as '{}'.",
                    rarityId, current.lowest().id());
        }
        return current.lowest();
    }

    /** Ascending by order. */
    public List<SkinDataModels.Rarity> sorted() {
        return snapshot.sorted();
    }

    public Collection<SkinDataModels.Rarity> all() {
        return snapshot.byId().values();
    }

    /** The next fusable tier above {@code from}, or null at the top of the ladder. */
    public SkinDataModels.Rarity nextByOrder(SkinDataModels.Rarity from) {
        for (SkinDataModels.Rarity candidate : snapshot.sorted()) {
            if (candidate.order() > from.order() && candidate.fusable()) {
                return candidate;
            }
        }
        return null;
    }

    public void syncFromNetwork(Collection<SkinDataModels.Rarity> rarities) {
        Map<String, SkinDataModels.Rarity> byId = new HashMap<>();
        for (SkinDataModels.Rarity rarity : rarities) {
            byId.put(rarity.id(), rarity);
        }
        // Server is authoritative, but never leave the client with nothing to resolve against.
        if (byId.isEmpty()) byId = defaults();
        WARNED_UNKNOWN.clear();
        publish(byId);
    }
}
