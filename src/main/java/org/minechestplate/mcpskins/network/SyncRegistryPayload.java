package org.minechestplate.mcpskins.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.RarityManager;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-to-client packet that syncs the rarity table and the full skin registry, so every
 * client sees the same skins regardless of datapack access.
 * <p>
 * Both travel in one packet on purpose: skins reference rarities by id, and splitting them
 * would leave a window where the client holds skins whose tiers it can't resolve.
 */
public record SyncRegistryPayload(List<SkinDataModels.Rarity> rarities,
                                  Map<String, SkinDataModels.WeaponSkins> registryData) implements CustomPacketPayload {
    public static final Type<SyncRegistryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "sync_skin_registry"));

    public static final StreamCodec<FriendlyByteBuf, SyncRegistryPayload> CODEC = CustomPacketPayload.codec(
            SyncRegistryPayload::write, SyncRegistryPayload::new
    );

    public SyncRegistryPayload(FriendlyByteBuf buffer) {
        this(readRarities(buffer), readMap(buffer));
    }

    private static final int MAX_WEAPONS = 4096;
    private static final int MAX_SKINS_PER_WEAPON = 512;
    private static final int MAX_RARITIES = 256;
    private static final int MAX_FUSE_TARGETS = 64;

    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_RARITY_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    /**
     * NeoForge caps clientbound payloads at ~1 MiB. There's no chunking fallback here - past
     * the cap this silently fails to send and every client gets an empty registry.
     */
    private static final int SIZE_WARNING_THRESHOLD = 768 * 1024;

    private static volatile boolean oversizeWarningLogged = false;

    private static List<SkinDataModels.Rarity> readRarities(FriendlyByteBuf buffer) {
        int count = readBoundedSize(buffer, MAX_RARITIES, "rarity count");
        List<SkinDataModels.Rarity> rarities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(MAX_RARITY_LENGTH);
            String displayName = buffer.readUtf(MAX_NAME_LENGTH);
            String translationKey = buffer.readUtf(MAX_NAME_LENGTH);
            int accentColor = buffer.readInt();
            int order = buffer.readInt();
            boolean fusable = buffer.readBoolean();
            Integer fuseCost = buffer.readBoolean() ? buffer.readVarInt() : null;

            int targetCount = readBoundedSize(buffer, MAX_FUSE_TARGETS, "fuse target count");
            List<SkinDataModels.FuseTarget> targets = new ArrayList<>();
            for (int j = 0; j < targetCount; j++) {
                targets.add(new SkinDataModels.FuseTarget(buffer.readUtf(MAX_RARITY_LENGTH), buffer.readVarInt()));
            }

            rarities.add(new SkinDataModels.Rarity(id, displayName,
                    translationKey.isEmpty() ? null : translationKey,
                    accentColor, order, fusable, fuseCost, targets));
        }
        return rarities;
    }

    private static void writeRarities(FriendlyByteBuf buffer, List<SkinDataModels.Rarity> rarities) {
        buffer.writeVarInt(rarities.size());
        for (SkinDataModels.Rarity rarity : rarities) {
            buffer.writeUtf(rarity.id());
            buffer.writeUtf(rarity.displayName());
            buffer.writeUtf(rarity.translationKey() == null ? "" : rarity.translationKey());
            buffer.writeInt(rarity.accentColor());
            buffer.writeInt(rarity.order());
            buffer.writeBoolean(rarity.fusable());
            buffer.writeBoolean(rarity.fuseCost() != null);
            if (rarity.fuseCost() != null) {
                buffer.writeVarInt(rarity.fuseCost());
            }
            buffer.writeVarInt(rarity.fuseTargets().size());
            for (SkinDataModels.FuseTarget target : rarity.fuseTargets()) {
                buffer.writeUtf(target.rarityId());
                buffer.writeVarInt(target.weight());
            }
        }
    }

    private static Map<String, SkinDataModels.WeaponSkins> readMap(FriendlyByteBuf buffer) {
        Map<String, SkinDataModels.WeaponSkins> map = new HashMap<>();
        int mapSize = readBoundedSize(buffer, MAX_WEAPONS, "weapon count");
        for (int i = 0; i < mapSize; i++) {
            String key = buffer.readUtf(MAX_ID_LENGTH);
            String baseGun = buffer.readUtf(MAX_ID_LENGTH);
            int skinSize = readBoundedSize(buffer, MAX_SKINS_PER_WEAPON, "skin count");
            List<SkinDataModels.SkinEntry> skins = new ArrayList<>();
            for (int j = 0; j < skinSize; j++) {
                String id = buffer.readUtf(MAX_ID_LENGTH);
                String name = buffer.readUtf(MAX_NAME_LENGTH);
                int color = buffer.readInt();
                String rarityId = buffer.readUtf(MAX_RARITY_LENGTH);
                String collection = buffer.readUtf(MAX_NAME_LENGTH);
                String description = buffer.readUtf(MAX_DESCRIPTION_LENGTH);
                boolean isNew = buffer.readBoolean();
                int weight = buffer.readVarInt();
                skins.add(new SkinDataModels.SkinEntry(id, name, color, rarityId, collection, description, isNew, weight));
            }
            map.put(key, new SkinDataModels.WeaponSkins(baseGun, skins));
        }
        return map;
    }

    /** Reads a collection size and rejects it before it can drive any allocation or loop. */
    private static int readBoundedSize(FriendlyByteBuf buffer, int max, String what) {
        int size = buffer.readVarInt();
        if (size < 0 || size > max) {
            throw new DecoderException("Skin registry " + what + " " + size + " is out of range [0, " + max + "]");
        }
        return size;
    }

    public void write(FriendlyByteBuf buffer) {
        int startIndex = buffer.writerIndex();
        writeRarities(buffer, rarities);
        buffer.writeVarInt(registryData.size());
        for (Map.Entry<String, SkinDataModels.WeaponSkins> entry : registryData.entrySet()) {
            buffer.writeUtf(entry.getKey());
            buffer.writeUtf(entry.getValue().baseGun());
            buffer.writeVarInt(entry.getValue().skins().size());
            for (SkinDataModels.SkinEntry skin : entry.getValue().skins()) {
                buffer.writeUtf(skin.id());
                buffer.writeUtf(skin.name());
                buffer.writeInt(skin.labelColor());
                buffer.writeUtf(skin.rarityId());
                buffer.writeUtf(skin.collection());
                buffer.writeUtf(skin.description());
                buffer.writeBoolean(skin.isNew());
                buffer.writeVarInt(skin.weight());
            }
        }

        int written = buffer.writerIndex() - startIndex;
        if (written > SIZE_WARNING_THRESHOLD && !oversizeWarningLogged) {
            oversizeWarningLogged = true;
            MCPSkins.LOGGER.error(
                    "[MCPSkins] Skin registry serializes to {} bytes, near NeoForge's ~1 MiB payload cap. "
                            + "Past the cap this packet fails to send and clients see an empty registry. "
                            + "Trim skin descriptions or split the datapack.",
                    written);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handleData(IPayloadContext context) {
        context.enqueueWork(() -> {
            // Rarities first - skins resolve their tier through RarityManager.
            RarityManager.INSTANCE.syncFromNetwork(rarities);
            SkinManager.INSTANCE.syncFromNetwork(registryData);
        });
    }

    public static SyncRegistryPayload createFromServer() {
        return new SyncRegistryPayload(List.copyOf(RarityManager.INSTANCE.all()), SkinManager.INSTANCE.getRegistry());
    }
}
