package org.minechestplate.mcpskins.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.minechestplate.mcpskins.item.SkinUnlockItem;
import org.minechestplate.mcpskins.skin.SkinAttachment;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;
import org.minechestplate.mcpskins.skin.TACZSkinHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The stable entry point into MCPSkins for other mods and for KubeJS scripts.
 * <p>
 * Everything here reads or writes through the same paths the mod's own UIs use, so an unlock made
 * from a script fires the same {@code api.event} notifications as one made from a command.
 * <p>
 * <b>Registry data only exists after a datapack load.</b> Before the first reload, and on a client
 * that has not yet received the registry sync, the lookup methods report an empty world: no skins,
 * no collections. Query them from gameplay code rather than from mod construction.
 * <p>
 * <b>Threading.</b> The methods that change a player require a {@link ServerPlayer} and must run on
 * the server thread; they throw {@link IllegalStateException} otherwise. The read-only methods are
 * safe from either side.
 */
public final class MCPSkinsAPI {

    private MCPSkinsAPI() {
    }

    // Ownership

    /** Whether {@code player} owns this exact skin. Stock {@code default:} entries are never owned. */
    public static boolean has(Player player, String skinId) {
        return SkinAttachment.hasSkin(player, skinId);
    }

    /**
     * Grants a skin, firing {@code SkinUnlockEvent.Pre} and, if that is not cancelled,
     * {@code SkinUnlockEvent.Post}.
     *
     * @return why nothing happened, or {@link UnlockResult#UNLOCKED}
     */
    public static UnlockResult unlock(Player player, String skinId) {
        return SkinAttachment.unlockSkin(requireServerPlayer(player, "unlock"), skinId, UnlockSource.API);
    }

    /**
     * Takes a skin away, firing {@code SkinRevokedEvent} if the player had it.
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

    // Registry

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

    /** Every real skin id. Stock {@code default:} entries are left out: they cannot be unlocked. */
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

    // Collections

    /** Every collection name any loaded skin declares. Matched exactly: they are free text. */
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

    // Stacks

    /** The skin worn by a TACZ item, or null if it is stock or not a TACZ item at all. */
    public static String getSkinOn(ItemStack stack) {
        return TACZSkinHelper.getSkinId(stack);
    }

    /**
     * A copy of {@code stack} wearing {@code skinId}, or stripped bare when it is null.
     * <p>
     * Purely a stack operation: no ownership check, no owner lore, and no events. Use it for
     * loot, kits and shop rewards. To change what a player is holding as if they had done it
     * themselves, let them equip it through the UI instead.
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
     * Builds a TACZ gun already wearing a skin.
     *
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
