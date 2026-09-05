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
    private static final int MAX_LOCKED_TEXT_LENGTH = 256;

    /**
     * NeoForge caps clientbound payloads at ~1 MiB. There's no chunking fallback here - past
     * the cap this silently fails to send and every client gets an empty registry.
     */
    private static final int SIZE_WARNING_THRESHOLD = 768 * 1024;

    private static volatile boolean oversizeWarningLogged = false;
    private static volatile boolean truncationWarningLogged = false;

    /**
     * Writes a string the reader will accept. The read side is bounded, the write side is not,
     * so an over-long pack field would otherwise produce a packet every client fails to decode
     * and get them all dropped at join.
     */
    private static void writeBounded(FriendlyByteBuf buffer, String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() > max) {
            text = text.substring(0, max);
            if (!truncationWarningLogged) {
                truncationWarningLogged = true;
                MCPSkins.LOGGER.warn("[MCPSkins] A skin pack field is longer than {} characters "
                        + "and was truncated before syncing to clients.", max);
            }
        }
        buffer.writeUtf(text, max);
    }

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
            writeBounded(buffer, rarity.id(), MAX_RARITY_LENGTH);
            writeBounded(buffer, rarity.displayName(), MAX_NAME_LENGTH);
            writeBounded(buffer, rarity.translationKey(), MAX_NAME_LENGTH);
            buffer.writeInt(rarity.accentColor());
            buffer.writeInt(rarity.order());
            buffer.writeBoolean(rarity.fusable());
            buffer.writeBoolean(rarity.fuseCost() != null);
            if (rarity.fuseCost() != null) {
                buffer.writeVarInt(rarity.fuseCost());
            }
            buffer.writeVarInt(rarity.fuseTargets().size());
            for (SkinDataModels.FuseTarget target : rarity.fuseTargets()) {
                writeBounded(buffer, target.rarityId(), MAX_RARITY_LENGTH);
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
            SkinDataModels.SkinTarget target = readTarget(buffer);
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
                String lockedText = buffer.readUtf(MAX_LOCKED_TEXT_LENGTH);
                boolean unlockedByDefault = buffer.readBoolean();
                skins.add(new SkinDataModels.SkinEntry(id, name, color, rarityId, collection,
                        description, isNew, weight, lockedText, unlockedByDefault));
            }
            map.put(key, new SkinDataModels.WeaponSkins(baseGun, skins, target));
        }
        return map;
    }

    /** An ordinal from a newer sender falls back to GUN rather than failing the whole packet. */
    private static SkinDataModels.SkinTarget readTarget(FriendlyByteBuf buffer) {
        int ordinal = buffer.readByte();
        SkinDataModels.SkinTarget[] values = SkinDataModels.SkinTarget.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SkinDataModels.SkinTarget.GUN;
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
            writeBounded(buffer, entry.getKey(), MAX_ID_LENGTH);
            writeBounded(buffer, entry.getValue().baseGun(), MAX_ID_LENGTH);
            buffer.writeByte(entry.getValue().target().ordinal());
            buffer.writeVarInt(entry.getValue().skins().size());
            for (SkinDataModels.SkinEntry skin : entry.getValue().skins()) {
                writeBounded(buffer, skin.id(), MAX_ID_LENGTH);
                writeBounded(buffer, skin.name(), MAX_NAME_LENGTH);
                buffer.writeInt(skin.labelColor());
                writeBounded(buffer, skin.rarityId(), MAX_RARITY_LENGTH);
                writeBounded(buffer, skin.collection(), MAX_NAME_LENGTH);
                writeBounded(buffer, skin.description(), MAX_DESCRIPTION_LENGTH);
                buffer.writeBoolean(skin.isNew());
                buffer.writeVarInt(skin.weight());
                writeBounded(buffer, skin.lockedText(), MAX_LOCKED_TEXT_LENGTH);
                buffer.writeBoolean(skin.unlockedByDefault());
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
