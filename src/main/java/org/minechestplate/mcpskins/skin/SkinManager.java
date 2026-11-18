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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code data/<namespace>/skins/*.json}, one file per base item (picked by a {@code base_*}
 * key, see {@link SkinnableItemType}). Skin ids are file names and owned-set keys, so they must be
 * globally unique. Schema in docs/ai/data-and-ownership.md.
 */
public class SkinManager extends SimpleJsonResourceReloadListener {
    public static final SkinManager INSTANCE = new SkinManager();

    // baseGunById holds each skin id both with and without its default: prefix.
    private record Snapshot(Map<String, SkinDataModels.WeaponSkins> registry,
                            Map<String, SkinDataModels.SkinLookupResult> skinsById,
                            Map<String, String> baseGunById,
                            Set<String> defaultUnlockedIds) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Set.of());
    }

    // Replaced wholesale, never mutated: written on reload and network threads, read while rendering.
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    // Memoized: getRegistry() is on the refit overlay's per-frame path.
    private record FilteredView(Snapshot source, int generation, boolean filtering,
                                Map<String, SkinDataModels.WeaponSkins> registry) {
    }

    private volatile FilteredView filteredView;

    public SkinManager() {
        super(new GsonBuilder().create(), "skins");
    }

    private record RawSkin(SkinDataModels.SkinEntry entry, List<String> variants, JsonArray levels) {
    }

    private record ParsedFile(ResourceLocation location, String baseGun, SkinDataModels.SkinTarget target,
                              List<RawSkin> skins) {
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objectIn, ResourceManager resourceManager, ProfilerFiller profilerIn) {
        // Links resolve after every file is parsed, to tell "in another file" from "unknown".
        Map<String, ParsedFile> files = new HashMap<>();
        objectIn.forEach((location, element) -> {
            try {
                JsonObject json = element.getAsJsonObject();

                SkinnableItemType type = null;
                for (SkinnableItemType candidate : SkinnableItemType.all()) {
                    if (!json.has(candidate.jsonKey())) continue;
                    if (type != null) {
                        MCPSkins.LOGGER.error("Skin config {} sets both {} and {}; skipping.",
                                location, type.jsonKey(), candidate.jsonKey());
                        return;
                    }
                    type = candidate;
                }
                if (type == null) {
                    MCPSkins.LOGGER.error("Skin config {} names no base item; expected one of {}.",
                            location, SkinnableItemType.all().stream().map(SkinnableItemType::jsonKey).toList());
                    return;
                }
                String baseGun = json.get(type.jsonKey()).getAsString();
                SkinDataModels.SkinTarget target = type.target();

                String fileAuthor = json.has("author") ? json.get("author").getAsString() : "";
                List<RawSkin> skins = new ArrayList<>();

                json.getAsJsonArray("skins").forEach(skinElement -> {
                    JsonObject skinObj = skinElement.getAsJsonObject();
                    String id = skinObj.get("id").getAsString();
                    String name = skinObj.get("name").getAsString();
                    int color = Integer.decode(skinObj.get("label_color").getAsString());

                    String rarityId = skinObj.has("rarity")
                            ? skinObj.get("rarity").getAsString().trim().toLowerCase(Locale.ROOT)
                            : SkinDataModels.DEFAULT_RARITY_ID;
                    String collection = skinObj.has("collection") ? skinObj.get("collection").getAsString() : "";
                    String description = skinObj.has("description") ? skinObj.get("description").getAsString() : "";
                    boolean isNew = skinObj.has("is_new") && skinObj.get("is_new").getAsBoolean();
                    int weight = skinObj.has("weight") ? Math.max(1, skinObj.get("weight").getAsInt()) : 1;
                    String lockedText = skinObj.has("locked_text")
                            ? skinObj.get("locked_text").getAsString() : "";
                    boolean unlocked = skinObj.has("unlocked") && skinObj.get("unlocked").getAsBoolean();
                    String author = skinObj.has("author") ? skinObj.get("author").getAsString() : fileAuthor;
                    String subtitle = skinObj.has("subtitle") ? skinObj.get("subtitle").getAsString() : "";
                    boolean limited = skinObj.has("limited") && skinObj.get("limited").getAsBoolean();

                    List<String> variants = new ArrayList<>();
                    if (skinObj.has("variants")) {
                        if (skinObj.get("variants").isJsonArray()) {
                            skinObj.getAsJsonArray("variants").forEach(v -> variants.add(v.getAsString()));
                        } else {
                            MCPSkins.LOGGER.warn("Skin '{}' in {}: \"variants\" is not an array; ignoring it.",
                                    id, location);
                        }
                    }
                    JsonArray levels = new JsonArray();
                    if (skinObj.has("levels")) {
                        if (skinObj.get("levels").isJsonArray()) {
                            levels = skinObj.getAsJsonArray("levels");
                        } else {
                            MCPSkins.LOGGER.warn("Skin '{}' in {}: \"levels\" is not an array; ignoring it.",
                                    id, location);
                        }
                    }

                    skins.add(new RawSkin(new SkinDataModels.SkinEntry(id, name, color, rarityId, collection,
                            description, isNew, weight, lockedText, unlocked, author, subtitle, limited,
                            List.of(), "", List.of()), variants, levels));
                });

                ParsedFile previous = files.put(baseGun, new ParsedFile(location, baseGun, target, skins));
                if (previous != null) {
                    MCPSkins.LOGGER.warn("Two skin configs both target '{}'; {} wins.", baseGun, location);
                }
            } catch (Exception e) {
                MCPSkins.LOGGER.error("Failed to parse skin config: {}", location, e);
            }
        });

        Set<String> allIds = new HashSet<>();
        for (ParsedFile file : files.values()) {
            for (RawSkin raw : file.skins()) {
                allIds.add(raw.entry().id());
            }
        }

        Map<String, SkinDataModels.WeaponSkins> registry = new HashMap<>();
        for (ParsedFile file : files.values()) {
            registry.put(file.baseGun(),
                    new SkinDataModels.WeaponSkins(file.baseGun(), resolveFile(file, allIds), file.target()));
        }
        publish(registry);
        MCPSkins.LOGGER.info("Loaded {} skin config(s).", registry.size());
    }

    // Drops bad variant and level links (the skin stays), orders variants after their root, prepends the stock entry.
    private static List<SkinDataModels.SkinEntry> resolveFile(ParsedFile file, Set<String> allIds) {
        ResourceLocation location = file.location();
        Map<String, RawSkin> byId = new LinkedHashMap<>();
        for (RawSkin raw : file.skins()) {
            byId.putIfAbsent(raw.entry().id(), raw);
        }

        Map<String, String> rootOf = new HashMap<>();
        Map<String, List<String>> keptVariants = new HashMap<>();
        Map<String, List<SkinDataModels.SkinLevel>> keptLevels = new HashMap<>();

        for (RawSkin raw : file.skins()) {
            String id = raw.entry().id();
            if (keptVariants.containsKey(id)) continue;
            List<String> kept = new ArrayList<>();
            for (String variant : raw.variants()) {
                if (SkinAttachment.isDefaultEntry(variant)) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {} lists the stock entry '{}' as a variant; ignoring it.",
                            id, location, variant);
                } else if (variant.equals(id)) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {} lists itself as a variant; ignoring it.", id, location);
                } else if (!byId.containsKey(variant)) {
                    if (allIds.contains(variant)) {
                        MCPSkins.LOGGER.warn("Skin '{}' in {} lists variant '{}', which is defined in another "
                                + "file; variants must share their root's file. Ignoring it.", id, location, variant);
                    } else {
                        MCPSkins.LOGGER.warn("Skin '{}' in {} lists unknown variant '{}'; ignoring it.",
                                id, location, variant);
                    }
                } else if (!byId.get(variant).variants().isEmpty()) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {} lists variant '{}', which has variants of its own; "
                            + "variants go one level deep. Ignoring it.", id, location, variant);
                } else if (rootOf.containsKey(variant)) {
                    MCPSkins.LOGGER.warn("Variant '{}' in {} is claimed by both '{}' and '{}'; keeping the first.",
                            variant, location, rootOf.get(variant), id);
                } else if (!kept.contains(variant)) {
                    kept.add(variant);
                    rootOf.put(variant, id);
                }
            }
            keptVariants.put(id, kept);
            keptLevels.put(id, parseLevels(id, location, raw.levels(), kept));
        }

        List<SkinDataModels.SkinEntry> out = new ArrayList<>();
        out.add(new SkinDataModels.SkinEntry(
                "default:" + file.baseGun(), "Default", 0xFFFFFF,
                SkinDataModels.DEFAULT_RARITY_ID, "", "", false, 1, "", true));
        Set<String> emitted = new HashSet<>();
        for (RawSkin raw : file.skins()) {
            String id = raw.entry().id();
            if (rootOf.containsKey(id) || !emitted.add(id)) continue;
            List<String> variants = keptVariants.getOrDefault(id, List.of());
            out.add(withLinks(raw.entry(), variants, "", keptLevels.getOrDefault(id, List.of())));
            for (String variant : variants) {
                if (emitted.add(variant)) {
                    out.add(withLinks(byId.get(variant).entry(), List.of(), id, List.of()));
                }
            }
        }
        return out;
    }

    private static List<SkinDataModels.SkinLevel> parseLevels(String rootId, ResourceLocation location,
                                                              JsonArray levels, List<String> variants) {
        List<SkinDataModels.SkinLevel> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < levels.size(); i++) {
            JsonElement element = levels.get(i);
            if (!element.isJsonObject()) {
                MCPSkins.LOGGER.warn("Skin '{}' in {}: level entry {} is not an object; ignoring it.",
                        rootId, location, i);
                continue;
            }
            JsonObject level = element.getAsJsonObject();
            String variant = level.has("variant") && level.get("variant").isJsonPrimitive()
                    ? level.get("variant").getAsString() : null;
            if (variant == null || !variants.contains(variant)) {
                MCPSkins.LOGGER.warn("Skin '{}' in {}: level entry {} names '{}', which is not one of its "
                        + "variants; ignoring that level.", rootId, location, i, variant);
                continue;
            }
            if (!used.add(variant)) {
                MCPSkins.LOGGER.warn("Skin '{}' in {}: variant '{}' is used by two levels; ignoring the second.",
                        rootId, location, variant);
                continue;
            }
            if (!level.has("requires") || !level.get("requires").isJsonObject()) {
                MCPSkins.LOGGER.warn("Skin '{}' in {}: level '{}' has no \"requires\" object; ignoring that level.",
                        rootId, location, variant);
                used.remove(variant);
                continue;
            }

            Map<SkinDataModels.LevelCounter, Integer> requires = new EnumMap<>(SkinDataModels.LevelCounter.class);
            boolean valid = true;
            for (Map.Entry<String, JsonElement> condition : level.getAsJsonObject("requires").entrySet()) {
                SkinDataModels.LevelCounter counter = SkinDataModels.LevelCounter.byKey(condition.getKey());
                if (counter == null) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {}: level '{}' requires unknown counter '{}'; "
                            + "ignoring that level.", rootId, location, variant, condition.getKey());
                    valid = false;
                    break;
                }
                int value;
                try {
                    value = condition.getValue().getAsInt();
                } catch (RuntimeException e) {
                    value = -1;
                }
                if (value < 1) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {}: level '{}' needs a positive whole number for '{}'; "
                            + "ignoring that level.", rootId, location, variant, condition.getKey());
                    valid = false;
                    break;
                }
                requires.put(counter, value);
            }
            if (!valid || requires.isEmpty()) {
                if (valid) {
                    MCPSkins.LOGGER.warn("Skin '{}' in {}: level '{}' requires nothing; ignoring that level.",
                            rootId, location, variant);
                }
                used.remove(variant);
                continue;
            }
            out.add(new SkinDataModels.SkinLevel(variant, requires));
        }
        return out;
    }

    private static SkinDataModels.SkinEntry withLinks(SkinDataModels.SkinEntry e, List<String> variants,
                                                      String rootId, List<SkinDataModels.SkinLevel> levels) {
        return new SkinDataModels.SkinEntry(e.id(), e.name(), e.labelColor(), e.rarityId(), e.collection(),
                e.description(), e.isNew(), e.weight(), e.lockedText(), e.unlockedByDefault(),
                e.author(), e.subtitle(), e.limited(), variants, rootId, levels);
    }

    private void publish(Map<String, SkinDataModels.WeaponSkins> registry) {
        Map<String, SkinDataModels.SkinLookupResult> skinsById = new HashMap<>();
        Map<String, String> baseGunById = new HashMap<>();
        Set<String> defaultUnlocked = new HashSet<>();

        for (SkinDataModels.WeaponSkins weapon : registry.values()) {
            baseGunById.put(weapon.baseGun(), weapon.baseGun());
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.unlockedByDefault() && !SkinAttachment.isDefaultEntry(skin.id())) {
                    defaultUnlocked.add(skin.id());
                }
                skinsById.putIfAbsent(skin.id(), new SkinDataModels.SkinLookupResult(weapon, skin));
                // Both spellings, so getBaseGun resolves a "default:" id too.
                baseGunById.putIfAbsent(skin.id(), weapon.baseGun());
                baseGunById.putIfAbsent(TACZSkinHelper.bareSkinId(skin.id()), weapon.baseGun());
            }
        }

        snapshot = new Snapshot(Map.copyOf(registry), Map.copyOf(skinsById), Map.copyOf(baseGunById),
                Set.copyOf(defaultUnlocked));
    }

    // Computed per reload, filtered on availability per call.
    public Set<String> getDefaultUnlockedIds() {
        Set<String> ids = snapshot.defaultUnlockedIds();
        if (!SkinAvailability.isEnabled()) {
            return ids;
        }
        Set<String> available = new HashSet<>();
        for (String skinId : ids) {
            if (SkinAvailability.isSkinAvailable(skinId)) {
                available.add(skinId);
            }
        }
        return available;
    }

    public SkinDataModels.SkinTarget targetOf(String baseId) {
        SkinDataModels.WeaponSkins weapon = snapshot.registry().get(TACZSkinHelper.bareSkinId(baseId));
        return weapon == null ? SkinDataModels.SkinTarget.GUN : weapon.target();
    }

    // Immutable. Leaves out items TACZ has not loaded; this is what clients receive and the UIs read.
    public Map<String, SkinDataModels.WeaponSkins> getRegistry() {
        Snapshot current = snapshot;
        boolean filtering = SkinAvailability.isEnabled();
        int generation = SkinAvailability.generation();

        FilteredView cached = filteredView;
        if (cached != null && cached.source() == current
                && cached.generation() == generation && cached.filtering() == filtering) {
            return cached.registry();
        }

        Map<String, SkinDataModels.WeaponSkins> registry = current.registry();
        if (filtering) {
            Map<String, SkinDataModels.WeaponSkins> available = new HashMap<>();
            registry.forEach((baseId, weapon) -> {
                if (SkinAvailability.isAvailable(weapon)) {
                    available.put(baseId, weapon);
                }
            });
            registry = Map.copyOf(available);
        }
        filteredView = new FilteredView(current, generation, filtering, registry);
        return registry;
    }

    public Map<String, SkinDataModels.WeaponSkins> getRawRegistry() {
        return snapshot.registry();
    }

    public void syncFromNetwork(Map<String, SkinDataModels.WeaponSkins> networkData) {
        publish(new HashMap<>(networkData));
    }

    public String getBaseGun(String skinOrGunId) {
        if (skinOrGunId == null) return "";

        String idToMatch = TACZSkinHelper.bareSkinId(skinOrGunId);
        String baseGun = snapshot.baseGunById().get(idToMatch);
        return baseGun != null ? baseGun : skinOrGunId;
    }

    public SkinDataModels.SkinLookupResult findSkin(String skinId) {
        SkinDataModels.SkinLookupResult lookup = findSkinRaw(skinId);
        return lookup != null && SkinAvailability.isAvailable(lookup.weapon()) ? lookup : null;
    }

    // Ignores availability: only to tell "unknown" from "not installed". Everything else wants findSkin.
    public SkinDataModels.SkinLookupResult findSkinRaw(String skinId) {
        if (skinId == null) return null;
        return snapshot.skinsById().get(skinId);
    }

    public SkinDataModels.SkinLookupResult findRoot(String skinId) {
        SkinDataModels.SkinLookupResult lookup = findSkin(skinId);
        if (lookup == null || !lookup.skin().isVariant()) return lookup;
        return findSkin(lookup.skin().rootId());
    }

    public List<String> unconditionedVariantsOf(String skinId) {
        SkinDataModels.SkinLookupResult lookup = findSkinRaw(skinId);
        return lookup == null ? List.of() : lookup.skin().unconditionedVariants();
    }

    // Raw rarity id, so a skin with an undefined tier never joins the fallback tier's fuse pool.
    public List<SkinDataModels.SkinLookupResult> getSkinsByRarity(String rarityId) {
        List<SkinDataModels.SkinLookupResult> list = new ArrayList<>();
        for (SkinDataModels.SkinLookupResult result : snapshot.skinsById().values()) {
            if (SkinAttachment.isDefaultEntry(result.skin().id())) continue;
            if (!SkinAvailability.isAvailable(result.weapon())) continue;
            if (result.skin().rarityId().equals(rarityId)) {
                list.add(result);
            }
        }
        return list;
    }

    public List<String> getCollections() {
        List<String> names = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (skin.hasCollection() && !names.contains(skin.collection())) {
                    names.add(skin.collection());
                }
            }
        }
        return names;
    }

    public List<String> getSkinIdsInCollection(String collection) {
        List<String> ids = new ArrayList<>();
        if (collection == null || collection.isBlank()) return ids;
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                if (!SkinAttachment.isDefaultEntry(skin.id()) && collection.equals(skin.collection())) {
                    ids.add(skin.id());
                }
            }
        }
        return ids;
    }

    // For command tab-completion. Skins for items TACZ has not loaded are left out.
    public List<String> getAllSkinIds() {
        List<String> list = new ArrayList<>();
        for (SkinDataModels.WeaponSkins weapon : snapshot.registry().values()) {
            if (!SkinAvailability.isAvailable(weapon)) continue;
            for (SkinDataModels.SkinEntry skin : weapon.skins()) {
                list.add(skin.id());
            }
        }
        return list;
    }
}