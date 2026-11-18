package org.minechestplate.mcpskins.api;

import org.minechestplate.mcpskins.skin.SkinDataModels;

import java.util.ArrayList;
import java.util.List;

/**
 * Public, immutable view of one loaded skin; stays stable while internal types change.
 *
 * @param id          globally unique across every gun and attachment
 * @param name        the pack's untranslated display name
 * @param baseId      the TACZ gun or attachment this skin belongs to
 * @param attachment  true when {@code baseId} names a TACZ attachment
 * @param target      the item family: GUN, ATTACHMENT, MELEE, THROWABLE or CONSUMABLE
 * @param rarityId    the tier id; may name a tier no pack defines, in which case the UIs fall back
 * @param collection  the collection name, or an empty string
 * @param description the pack's untranslated description, or an empty string
 * @param isNew       whether the pack flags this skin as new
 * @param author      who made it, or an empty string
 * @param subtitle    the pack's untranslated flavor line, or an empty string
 * @param limited     whether the pack tags this skin as a limited edition; visual only
 * @param variants    this skin's variant ids, empty unless it is a root
 * @param rootId      the root this skin is a variant of, or an empty string
 * @param levels      this root's levels in order, empty if it has none
 */
public record SkinInfo(String id, String name, String baseId, boolean attachment, String target,
                       String rarityId, String collection, String description, boolean isNew,
                       String author, String subtitle, boolean limited, List<String> variants,
                       String rootId, List<SkinLevelInfo> levels) {

    public SkinInfo {
        variants = variants == null ? List.of() : List.copyOf(variants);
        levels = levels == null ? List.of() : List.copyOf(levels);
    }

    /** The original nine-field shape, kept for callers compiled against it. */
    public SkinInfo(String id, String name, String baseId, boolean attachment, String target,
                    String rarityId, String collection, String description, boolean isNew) {
        this(id, name, baseId, attachment, target, rarityId, collection, description, isNew,
                "", "", false, List.of(), "", List.of());
    }

    /** Internal: builds the public view of a lookup result. */
    public static SkinInfo of(SkinDataModels.SkinLookupResult lookup) {
        SkinDataModels.SkinEntry skin = lookup.skin();
        List<SkinLevelInfo> levels = new ArrayList<>();
        for (int i = 0; i < skin.levels().size(); i++) {
            levels.add(SkinLevelInfo.of(SkinDataModels.SkinLevel.FIRST_LEVEL + i, skin.levels().get(i)));
        }
        return new SkinInfo(skin.id(), skin.name(), lookup.weapon().baseGun(),
                lookup.weapon().isAttachment(), lookup.weapon().target().name(),
                skin.rarityId(), skin.collection(), skin.description(), skin.isNew(),
                skin.author(), skin.subtitle(), skin.limited(), skin.variants(), skin.rootId(), levels);
    }

    public boolean hasCollection() {
        return collection != null && !collection.isBlank();
    }

    public boolean isVariant() {
        return rootId != null && !rootId.isBlank();
    }
}
