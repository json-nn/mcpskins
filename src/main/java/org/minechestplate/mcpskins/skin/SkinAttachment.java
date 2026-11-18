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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player owned skin ids and leveling counters (data attachments, saved and copied on death).
 * Every ownership change goes through here, which validates, syncs and posts the {@code api.event}s.
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

    // Counters per root skin id. Only SkinProgress writes; values are replaced, never mutated.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, SkinProgress.Counters>>> SKIN_PROGRESS = ATTACHMENTS.register(
            "skin_progress",
            () -> AttachmentType.builder(() -> (Map<String, SkinProgress.Counters>) new HashMap<String, SkinProgress.Counters>())
                    .serialize(Codec.unboundedMap(Codec.STRING, SkinProgress.Counters.CODEC)
                            .xmap(HashMap::new, map -> map))
                    .copyOnDeath()
                    .build()
    );

    private static final String DEFAULT_PREFIX = "default:";

    // The stock entry SkinManager.apply synthesizes per item for "no skin". Never unlockable.
    public static boolean isDefaultEntry(String skinId) {
        return skinId != null && skinId.startsWith(DEFAULT_PREFIX);
    }

    // The authorization predicate. Never grant based on the shape of an id; grant only by adding
    // ids to the owned set. For UI display use isOwnedOrDefault.
    public static boolean hasSkin(Player player, String skinId) {
        return skinId != null && player.getData(UNLOCKED_SKINS).contains(skinId);
    }

    // Display only: stock entries read as owned. Never gate an action on this.
    public static boolean isOwnedOrDefault(Player player, String skinId) {
        return isDefaultEntry(skinId) || hasSkin(player, skinId);
    }

    // A root brings its unconditioned variants in the same commit, reported as VARIANT.
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

        Map<String, UnlockSource> added = new LinkedHashMap<>();
        added.put(skinId, source);
        addVariants(added, owned);

        Set<String> updated = new HashSet<>(owned);
        updated.addAll(added.keySet());
        commit(player, updated);
        postUnlocked(player, added, owned);
        return UnlockResult.UNLOCKED;
    }

    // Starter skins ("unlocked": true); syncs only on an actual change.
    public static void grantDefaultUnlocks(ServerPlayer player) {
        addAll(player, SkinManager.INSTANCE.getDefaultUnlockedIds(), UnlockSource.DEFAULT_GRANT);
    }

    // Adds missing unconditioned variants of owned roots (packs can add variants later). Never removes.
    public static void reconcileVariants(ServerPlayer player) {
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        Map<String, UnlockSource> added = new LinkedHashMap<>();
        for (String skinId : owned) {
            for (String variant : SkinManager.INSTANCE.unconditionedVariantsOf(skinId)) {
                if (!owned.contains(variant) && SkinAvailability.isSkinAvailable(variant)) {
                    added.putIfAbsent(variant, UnlockSource.VARIANT);
                }
            }
        }
        if (added.isEmpty()) {
            return;
        }
        Set<String> updated = new HashSet<>(owned);
        updated.addAll(added.keySet());
        commit(player, updated);
        postUnlocked(player, added, owned);
    }

    private static void addVariants(Map<String, UnlockSource> added, Set<String> owned) {
        for (String skinId : List.copyOf(added.keySet())) {
            for (String variant : SkinManager.INSTANCE.unconditionedVariantsOf(skinId)) {
                if (!owned.contains(variant) && SkinAvailability.isSkinAvailable(variant)) {
                    added.putIfAbsent(variant, UnlockSource.VARIANT);
                }
            }
        }
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

    // Bulk: no pre-event, one sync, one post-event per skin added. Roots bring their unconditioned variants.
    private static void addAll(Player player, Collection<String> candidates, UnlockSource source) {
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        Map<String, UnlockSource> added = new LinkedHashMap<>();
        for (String skinId : candidates) {
            if (!owned.contains(skinId)) {
                added.put(skinId, source);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        addVariants(added, owned);

        Set<String> updated = new HashSet<>(owned);
        updated.addAll(added.keySet());
        commit(player, updated);
        postUnlocked(player, added, owned);
    }

    // Also takes a root's unconditioned variants; earned level variants stay.
    public static boolean revokeSkin(Player player, String skinId, RevokeSource source) {
        Set<String> owned = player.getData(UNLOCKED_SKINS);
        if (!owned.contains(skinId)) {
            return false;
        }
        List<String> removed = new ArrayList<>();
        removed.add(skinId);
        for (String variant : SkinManager.INSTANCE.unconditionedVariantsOf(skinId)) {
            if (owned.contains(variant) && !removed.contains(variant)) {
                removed.add(variant);
            }
        }
        Set<String> updated = new HashSet<>(owned);
        updated.removeAll(removed);
        commit(player, updated);
        postRevoked(player, removed, source);
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

    // The only write of the owned set; syncs to the owning client.
    private static void commit(Player player, Set<String> updated) {
        player.setData(UNLOCKED_SKINS, updated);
        if (player instanceof ServerPlayer serverPlayer) {
            Payloads.sendTo(serverPlayer, new SyncUnlocksPayload(new ArrayList<>(updated)));
        }
    }

    private static void postUnlocked(Player player, Map<String, UnlockSource> added, Set<String> ownedBefore) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        added.forEach((skinId, source) ->
                NeoForge.EVENT_BUS.post(new SkinUnlockEvent.Post(serverPlayer, skinId, source)));
        for (String collection : completedCollections(List.copyOf(added.keySet()), ownedBefore)) {
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

    // Only transitions from incomplete, each once per grant.
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
