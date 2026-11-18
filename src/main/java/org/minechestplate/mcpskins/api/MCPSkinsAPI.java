package org.minechestplate.mcpskins.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.item.SkinUnlockItem;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.SkinProgress;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable entry point for other mods and KubeJS. Writes go through the same paths as commands and
 * fire the same {@code api.event} events.
 * <p>
 * Lookups return nothing until a datapack load (or, on a client, the registry sync) has happened.
 * Methods that change a player need a {@link ServerPlayer} on the server thread and throw
 * {@link IllegalStateException} otherwise; reads work on either side.
 */
public final class MCPSkinsAPI {

    private MCPSkinsAPI() {
    }

    /** Whether {@code player} owns this exact skin. Stock {@code default:} entries are never owned. */
    public static boolean has(Player player, String skinId) {
        return SkinAttachment.hasSkin(player, skinId);
    }

    /**
     * Grants a skin, firing {@code SkinUnlockEvent.Pre} and, if that is not cancelled,
     * {@code SkinUnlockEvent.Post}. A root brings its unconditioned variants along.
     *
     * @return why nothing happened, or {@link UnlockResult#UNLOCKED}
     */
    public static UnlockResult unlock(Player player, String skinId) {
        return SkinAttachment.unlockSkin(requireServerPlayer(player, "unlock"), skinId, UnlockSource.API);
    }

    /**
     * Takes a skin away, firing {@code SkinRevokedEvent} if the player had it. A root takes its
     * unconditioned variants with it; level variants stay.
     *
     * @return true if something was removed
     */
    public static boolean revoke(Player player, String skinId) {
        return SkinAttachment.revokeSkin(requireServerPlayer(player, "revoke"), skinId, RevokeSource.API);
    }

    /** Every skin id this player owns, in no particular order. */
    public static List<String> getUnlocked(Player player) {
        return List.copyOf(player.getData(SkinAttachment.UNLOCKED_SKINS));
    }

    /** Whether a skin with this id is loaded. False for {@code default:} entries. */
    public static boolean exists(String skinId) {
        return !SkinAttachment.isDefaultEntry(skinId) && SkinManager.INSTANCE.findSkin(skinId) != null;
    }

    /** @return the skin, or null when no loaded pack defines it */
    public static SkinInfo getSkin(String skinId) {
        if (SkinAttachment.isDefaultEntry(skinId)) {
            return null;
        }
        SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
        return lookup == null ? null : SkinInfo.of(lookup);
    }

    /** Every skin id except stock {@code default:} entries. */
    public static List<String> getAllSkinIds() {
        List<String> ids = new ArrayList<>();
        for (String id : SkinManager.INSTANCE.getAllSkinIds()) {
            if (!SkinAttachment.isDefaultEntry(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Skins belonging to one TACZ gun or attachment.
     *
     * @param baseId a gun or attachment id, such as {@code "tacz:m4a1"}
     */
    public static List<SkinInfo> getSkinsFor(String baseId) {
        SkinDataModels.WeaponSkins weapon = SkinManager.INSTANCE.getRegistry().get(baseId);
        if (weapon == null) {
            return List.of();
        }
        List<SkinInfo> result = new ArrayList<>();
        for (SkinDataModels.SkinEntry skin : weapon.skins()) {
            if (!SkinAttachment.isDefaultEntry(skin.id())) {
                result.add(SkinInfo.of(new SkinDataModels.SkinLookupResult(weapon, skin)));
            }
        }
        return result;
    }

    /** Skins of one rarity tier, matched on the raw id the pack wrote. */
    public static List<SkinInfo> getSkinsByRarity(String rarityId) {
        List<SkinInfo> result = new ArrayList<>();
        for (SkinDataModels.SkinLookupResult lookup : SkinManager.INSTANCE.getSkinsByRarity(rarityId)) {
            result.add(SkinInfo.of(lookup));
        }
        return result;
    }

    /** Every collection name any loaded skin declares. Names are free text and matched exactly. */
    public static List<String> getCollections() {
        return SkinManager.INSTANCE.getCollections();
    }

    public static List<SkinInfo> getSkinsInCollection(String collection) {
        List<SkinInfo> result = new ArrayList<>();
        for (String skinId : SkinManager.INSTANCE.getSkinIdsInCollection(collection)) {
            SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
            if (lookup != null) {
                result.add(SkinInfo.of(lookup));
            }
        }
        return result;
    }

    /** A total of 0 means no loaded pack declares that collection. */
    public static CollectionProgress getCollectionProgress(Player player, String collection) {
        List<String> members = SkinManager.INSTANCE.getSkinIdsInCollection(collection);
        int owned = 0;
        for (String skinId : members) {
            if (SkinAttachment.hasSkin(player, skinId)) {
                owned++;
            }
        }
        return new CollectionProgress(collection, owned, members.size());
    }

    /**
     * Kill counters and level on a skin's family; a variant reports its root's progress. A skin
     * without levels reads as level 1 of 1. On a client this reads the local player's synced copy.
     *
     * @return null when no loaded pack defines {@code skinId}
     */
    public static SkinProgressInfo getProgress(Player player, String skinId) {
        if (SkinAttachment.isDefaultEntry(skinId)) {
            return null;
        }
        SkinDataModels.SkinLookupResult root = SkinManager.INSTANCE.findRoot(skinId);
        if (root == null) {
            return null;
        }
        SkinProgress.Counters counters = SkinProgress.get(player, root.skin().id());
        return new SkinProgressInfo(root.skin().id(), counters.kills(), counters.headshots(),
                counters.playerKills(), SkinProgress.levelFor(root.skin(), counters),
                SkinProgress.maxLevel(root.skin()));
    }

    /** The level a player has reached on a skin's family; 0 for an unknown id, 1 before any level. */
    public static int getLevel(Player player, String skinId) {
        SkinProgressInfo progress = getProgress(player, skinId);
        return progress == null ? 0 : progress.level();
    }

    /** The skin worn by a TACZ item, or null if it is stock or not a TACZ item at all. */
    public static String getSkinOn(ItemStack stack) {
        return TACZSkinHelper.getSkinId(stack);
    }

    /**
     * A copy of {@code stack} wearing {@code skinId}, or stock when it is null. No ownership check,
     * owner lore or events.
     *
     * @return the new stack, or {@code stack} unchanged if it is not a skinnable TACZ item
     */
    public static ItemStack applySkin(ItemStack stack, String skinId) {
        return TACZSkinHelper.applySkin(stack, skinId);
    }

    /** @return the unlock item for this skin, or an empty stack for an unknown id */
    public static ItemStack createUnlockItem(String skinId) {
        return exists(skinId) ? SkinUnlockItem.create(skinId) : ItemStack.EMPTY;
    }

    /**
     * @param skinId may be null for a stock gun
     * @return an empty stack when {@code gunId} names no registered TACZ item
     */
    public static ItemStack createSkinnedGun(String gunId, String skinId) {
        return TACZSkinHelper.createGunStack(gunId, skinId);
    }

    private static ServerPlayer requireServerPlayer(Player player, String method) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            throw new IllegalStateException("MCPSkinsAPI." + method
                    + " changes saved data and needs a ServerPlayer on the server thread, got "
                    + (player == null ? "null" : player.getClass().getName()));
        }
        if (!serverPlayer.getServer().isSameThread()) {
            throw new IllegalStateException("MCPSkinsAPI." + method
                    + " must be called on the server thread, not " + Thread.currentThread().getName());
        }
        return serverPlayer;
    }
}
