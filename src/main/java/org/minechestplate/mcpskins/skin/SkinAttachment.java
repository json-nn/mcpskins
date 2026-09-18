package org.minechestplate.mcpskins.skin;

import com.mojang.serialization.Codec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.api.RevokeSource;
import org.minechestplate.mcpskins.api.UnlockResult;
import org.minechestplate.mcpskins.api.UnlockSource;
import org.minechestplate.mcpskins.api.event.CollectionCompletedEvent;
import org.minechestplate.mcpskins.api.event.SkinRevokedEvent;
import org.minechestplate.mcpskins.api.event.SkinUnlockEvent;
import org.minechestplate.mcpskins.network.Payloads;
import org.minechestplate.mcpskins.network.SyncUnlocksPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NeoForge data attachment tracking which skin IDs each player has unlocked.
 * Persists across death and syncs to the client whenever it changes.
 * <p>
 * Every mutation goes through this class, so validation, the sync packet and the
 * {@code api.event} notifications all live in one place.
 */
public class SkinAttachment {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCPSkins.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Set<String>>> UNLOCKED_SKINS = ATTACHMENTS.register(
            "unlocked_skins",
            () -> AttachmentType.builder(() -> (Set<String>) new HashSet<String>())
                    .serialize(Codec.list(Codec.STRING).xmap(HashSet::new, ArrayList::new))
                    .copyOnDeath()
                    .build()
    );

    /** Registry ids of a weapon's stock appearance, which nobody needs to unlock. */
    private static final String DEFAULT_PREFIX = "default:";

    /** {@link SkinManager#apply} synthesizes one per weapon for "no skin". Never unlockable. */
    public static boolean isDefaultEntry(String skinId) {
        return skinId != null && skinId.startsWith(DEFAULT_PREFIX);
    }

    /**
     * The <em>authorization</em> predicate. Must never grant on the shape of an id: special-casing
     * {@code default:} here is what let any client equip any locked skin. For "should the UI show
     * this as unlocked", use {@link #isOwnedOrDefault} instead.
     */
    public static boolean hasSkin(Player player, String skinId) {
        return skinId != null && player.getData(UNLOCKED_SKINS).contains(skinId);
    }

    /** Display only - stock entries always read as available. Never gate an action on this. */
    public static boolean isOwnedOrDefault(Player player, String skinId) {
        return isDefaultEntry(skinId) || hasSkin(player, skinId);
    }

    /**
     * Grants one skin, refusing ids that are not in the loaded packs.
     *
     * @return what happened; only {@link UnlockResult#UNLOCKED} changed anything
     */
    public static UnlockResult unlockSkin(Player player, String skinId, UnlockSource source) {
        if (skinId == null || isDefaultEntry(skinId) || SkinManager.INSTANCE.findSkinRaw(skinId) == null) {
            return UnlockResult.UNKNOWN_SKIN;
        }
        if (!SkinAvailability.isSkinAvailable(skinId)) {
            return UnlockResult.UNAVAILABLE;
        }
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        if (owned.contains(skinId)) {
            return UnlockResult.ALREADY_OWNED;
        }
        if (player instanceof ServerPlayer serverPlayer
                && NeoForge.EVENT_BUS.post(new SkinUnlockEvent.Pre(serverPlayer, skinId, source)).isCanceled()) {
            return UnlockResult.CANCELLED;
        }

        Set<String> updated = new HashSet<>(owned);
        updated.add(skinId);
        commit(player, updated);
        postUnlocked(player, List.of(skinId), source, owned);
        return UnlockResult.UNLOCKED;
    }

    /**
     * Grants every skin a pack marked {@code "unlocked": true} that the player is missing.
     * Writes and syncs only on an actual change, so a returning player costs no packet.
     */
    public static void grantDefaultUnlocks(ServerPlayer player) {
        addAll(player, SkinManager.INSTANCE.getDefaultUnlockedIds(), UnlockSource.DEFAULT_GRANT);
    }

    public static void unlockAllSkins(Player player) {
        Set<String> everything = new LinkedHashSet<>();
        for (String skinId : SkinManager.INSTANCE.getAllSkinIds()) {
            if (!isDefaultEntry(skinId)) {
                everything.add(skinId);
            }
        }
        addAll(player, everything, UnlockSource.UNLOCK_ALL);
    }

    /** Bulk grant: no cancellable pre-event, one sync, one post-event per skin actually added. */
    private static void addAll(Player player, Collection<String> candidates, UnlockSource source) {
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        List<String> added = new ArrayList<>();
        for (String skinId : candidates) {
            if (!owned.contains(skinId)) {
                added.add(skinId);
            }
        }
        if (added.isEmpty()) {
            return;
        }

        Set<String> updated = new HashSet<>(owned);
        updated.addAll(added);
        commit(player, updated);
        postUnlocked(player, added, source, owned);
    }

    public static boolean revokeSkin(Player player, String skinId, RevokeSource source) {
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        if (!owned.contains(skinId)) {
            return false;
        }
        Set<String> updated = new HashSet<>(owned);
        updated.remove(skinId);
        commit(player, updated);
        postRevoked(player, List.of(skinId), source);
        return true;
    }

    public static void clearSkins(Player player, RevokeSource source) {
        List<String> removed = new ArrayList<>(player.getData(UNLOCKED_SKINS));
        if (removed.isEmpty()) {
            return;
        }
        commit(player, new HashSet<>());
        postRevoked(player, removed, source);
    }

    /** The one place the set is written and pushed to the owning client. */
    private static void commit(Player player, Set<String> updated) {
        player.setData(UNLOCKED_SKINS, updated);
        if (player instanceof ServerPlayer serverPlayer) {
            Payloads.sendTo(serverPlayer, new SyncUnlocksPayload(new ArrayList<>(updated)));
        }
    }

    /** @param ownedBefore the set as it was, for spotting collections that have just filled up */
    private static void postUnlocked(Player player, List<String> added, UnlockSource source,
                                     Set<String> ownedBefore) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        for (String skinId : added) {
            NeoForge.EVENT_BUS.post(new SkinUnlockEvent.Post(serverPlayer, skinId, source));
        }
        for (String collection : completedCollections(added, ownedBefore)) {
            NeoForge.EVENT_BUS.post(new CollectionCompletedEvent(serverPlayer, collection));
        }
    }

    private static void postRevoked(Player player, List<String> removed, RevokeSource source) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        for (String skinId : removed) {
            NeoForge.EVENT_BUS.post(new SkinRevokedEvent(serverPlayer, skinId, source));
        }
    }

    /**
     * Collections the new skins just completed. Evaluated against {@code ownedBefore} so a
     * collection that was already full stays quiet, and a bulk grant reports each one once.
     */
    private static Set<String> completedCollections(List<String> added, Set<String> ownedBefore) {
        Set<String> touched = new LinkedHashSet<>();
        for (String skinId : added) {
            SkinDataModels.SkinLookupResult lookup = SkinManager.INSTANCE.findSkin(skinId);
            if (lookup != null && lookup.skin().hasCollection()) {
                touched.add(lookup.skin().collection());
            }
        }
        if (touched.isEmpty()) {
            return Set.of();
        }

        Set<String> ownedAfter = new HashSet<>(ownedBefore);
        ownedAfter.addAll(added);

        Set<String> completed = new LinkedHashSet<>();
        for (String collection : touched) {
            List<String> members = SkinManager.INSTANCE.getSkinIdsInCollection(collection);
            if (!members.isEmpty() && ownedAfter.containsAll(members) && !ownedBefore.containsAll(members)) {
                completed.add(collection);
            }
        }
        return completed;
    }
}
