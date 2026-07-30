package org.minechestplate.mcpskins.skin;

import com.mojang.serialization.Codec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.minechestplate.mcpskins.MCPSkins;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * NeoForge data attachment tracking which skin IDs each player has unlocked.
 * Persists across death and syncs to the client whenever it changes.
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

    /**
     * Whether {@code skinId} names a weapon's stock appearance rather than a real skin.
     * <p>
     * These entries are synthesized per weapon by {@link SkinManager#apply} so the UIs
     * have something to render for "no skin"; they are never unlockable and never appear
     * in {@link #UNLOCKED_SKINS}.
     */
    public static boolean isDefaultEntry(String skinId) {
        return skinId != null && skinId.startsWith(DEFAULT_PREFIX);
    }

    /**
     * Strictly whether the player has unlocked this skin. This is the <em>authorization</em>
     * predicate - it is what stands between a client and a skin it hasn't earned, so it
     * must never grant anything based on the shape of the id.
     * <p>
     * It deliberately does <em>not</em> special-case {@link #isDefaultEntry default:} ids.
     * It used to, which meant any client could equip any locked skin just by prefixing the
     * request with {@code "default:"} - the prefix check fired before the unlock set was
     * ever consulted, and every downstream check then passed because
     * {@link SkinManager#getBaseGun} strips the prefix before matching. Removing a skin is
     * now a separate, explicitly-flagged request that carries no skin id at all
     * (see {@code ApplySkinPayload}), so nothing about an id needs to be trusted here.
     * <p>
     * For UI code that wants "should this render as unlocked", use
     * {@link #isOwnedOrDefault} instead - stock entries should still show as available.
     */
    public static boolean hasSkin(Player player, String skinId) {
        return skinId != null && player.getData(UNLOCKED_SKINS).contains(skinId);
    }

    /**
     * Display predicate: whether this entry should read as available to the player.
     * Stock entries always do. Never use this to gate a privileged action.
     */
    public static boolean isOwnedOrDefault(Player player, String skinId) {
        return isDefaultEntry(skinId) || hasSkin(player, skinId);
    }

    public static void unlockSkin(Player player, String skinId) {
        Set<String> skins = player.getData(UNLOCKED_SKINS);
        if (!skins.contains(skinId)) {
            Set<String> updatedSkins = new HashSet<>(skins);
            updatedSkins.add(skinId);
            player.setData(UNLOCKED_SKINS, updatedSkins);

            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        serverPlayer,
                        new org.minechestplate.mcpskins.network.SyncUnlocksPayload(new ArrayList<>(updatedSkins))
                );
            }
        }
    }

    public static void unlockAllSkins(Player player) {
        Set<String> skins = player.getData(UNLOCKED_SKINS);
        Set<String> updatedSkins = new HashSet<>(skins);
        boolean changed = false;

        for (String skinId : SkinManager.INSTANCE.getAllSkinIds()) {
            if (!isDefaultEntry(skinId) && !updatedSkins.contains(skinId)) {
                updatedSkins.add(skinId);
                changed = true;
            }
        }

        if (changed) {
            player.setData(UNLOCKED_SKINS, updatedSkins);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        serverPlayer,
                        new org.minechestplate.mcpskins.network.SyncUnlocksPayload(new ArrayList<>(updatedSkins))
                );
            }
        }
    }

    public static boolean revokeSkin(Player player, String skinId) {
        Set<String> skins = player.getData(UNLOCKED_SKINS);
        if (skins.contains(skinId)) {
            Set<String> updatedSkins = new HashSet<>(skins);
            updatedSkins.remove(skinId);
            player.setData(UNLOCKED_SKINS, updatedSkins);

            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        serverPlayer,
                        new org.minechestplate.mcpskins.network.SyncUnlocksPayload(new ArrayList<>(updatedSkins))
                );
            }
            return true;
        }
        return false;
    }

    public static void clearSkins(Player player) {
        player.setData(UNLOCKED_SKINS, new HashSet<>());

        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    serverPlayer,
                    new org.minechestplate.mcpskins.network.SyncUnlocksPayload(new ArrayList<>())
            );
        }
    }
}