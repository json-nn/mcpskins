package org.minechestplate.mcpskins.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.skin.SkinDataModels;
import org.minechestplate.mcpskins.skin.SkinManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-to-client packet that syncs the full skin registry, so every client sees the
 * same skins regardless of datapack access. Carries each {@link SkinDataModels.SkinEntry}
 * in full, including rarity/collection/description/isNew for the Armory UI.
 */
public record SyncRegistryPayload(Map<String, SkinDataModels.WeaponSkins> registryData) implements CustomPacketPayload {
    public static final Type<SyncRegistryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MCPSkins.MOD_ID, "sync_skin_registry"));

    public static final StreamCodec<FriendlyByteBuf, SyncRegistryPayload> CODEC = CustomPacketPayload.codec(
            SyncRegistryPayload::write, SyncRegistryPayload::new
    );

    public SyncRegistryPayload(FriendlyByteBuf buffer) {
        this(readMap(buffer));
    }

    /** Generous ceilings on element counts, so a declared size is never taken on faith. */
    private static final int MAX_WEAPONS = 4096;
    private static final int MAX_SKINS_PER_WEAPON = 512;

    /** Per-field string bounds, replacing readUtf()'s 32767 default. */
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_RARITY_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 512;

    /**
     * NeoForge caps a clientbound payload at ~1 MiB. Warn well before that, since this
     * payload has no chunking fallback - it just fails to send, silently, and every client
     * ends up with an empty registry.
     */
    private static final int SIZE_WARNING_THRESHOLD = 768 * 1024;

    private static volatile boolean oversizeWarningLogged = false;

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
                SkinDataModels.Rarity rarity = SkinDataModels.Rarity.byName(buffer.readUtf(MAX_RARITY_LENGTH));
                String collection = buffer.readUtf(MAX_NAME_LENGTH);
                String description = buffer.readUtf(MAX_DESCRIPTION_LENGTH);
                boolean isNew = buffer.readBoolean();
                skins.add(new SkinDataModels.SkinEntry(id, name, color, rarity, collection, description, isNew));
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
        buffer.writeVarInt(registryData.size());
        for (Map.Entry<String, SkinDataModels.WeaponSkins> entry : registryData.entrySet()) {
            buffer.writeUtf(entry.getKey());
            buffer.writeUtf(entry.getValue().baseGun());
            buffer.writeVarInt(entry.getValue().skins().size());
            for (SkinDataModels.SkinEntry skin : entry.getValue().skins()) {
                buffer.writeUtf(skin.id());
                buffer.writeUtf(skin.name());
                buffer.writeInt(skin.labelColor());
                buffer.writeUtf(skin.rarity().name());
                buffer.writeUtf(skin.collection());
                buffer.writeUtf(skin.description());
                buffer.writeBoolean(skin.isNew());
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
            SkinManager.INSTANCE.syncFromNetwork(registryData);
        });
    }

    public static SyncRegistryPayload createFromServer() {
        return new SyncRegistryPayload(SkinManager.INSTANCE.getRegistry());
    }
}
