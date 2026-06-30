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

public class SkinAttachment {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCPSkins.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Set<String>>> UNLOCKED_SKINS = ATTACHMENTS.register(
            "unlocked_skins",
            () -> AttachmentType.builder(() -> (Set<String>) new HashSet<String>())
                    .serialize(Codec.list(Codec.STRING).xmap(HashSet::new, ArrayList::new))
                    .copyOnDeath()
                    .build()
    );

    public static boolean hasSkin(Player player, String skinId) {
        if (skinId.startsWith("default:")) {
            return true;
        }
        return player.getData(UNLOCKED_SKINS).contains(skinId);
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
                        new org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload(new ArrayList<>(updatedSkins))
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
                        new org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload(new ArrayList<>(updatedSkins))
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
                    new org.minechestplate.mcpskins.skin.network.SyncUnlocksPayload(new ArrayList<>())
            );
        }
    }
}