package org.minechestplate.mcpskins.api;

import org.minechestplate.mcpskins.skin.SkinDataModels;

/**
 * An immutable snapshot of one loaded skin. Decoupled from the mod's internal types on purpose:
 * this is what {@link MCPSkinsAPI} hands out, and it stays stable while the internals move.
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
 */
public record SkinInfo(String id, String name, String baseId, boolean attachment, String target,
                       String rarityId, String collection, String description, boolean isNew) {

    /** Internal: builds the public view of a lookup result. */
    public static SkinInfo of(SkinDataModels.SkinLookupResult lookup) {
        SkinDataModels.SkinEntry skin = lookup.skin();
        return new SkinInfo(skin.id(), skin.name(), lookup.weapon().baseGun(),
                lookup.weapon().isAttachment(), lookup.weapon().target().name(),
                skin.rarityId(), skin.collection(), skin.description(), skin.isNew());
    }

    public boolean hasCollection() {
        return collection != null && !collection.isBlank();
    }
}
