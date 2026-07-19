package org.minechestplate.mcpskins.skin.network;

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

    private static Map<String, SkinDataModels.WeaponSkins> readMap(FriendlyByteBuf buffer) {
        Map<String, SkinDataModels.WeaponSkins> map = new HashMap<>();
        int mapSize = buffer.readVarInt();
        for (int i = 0; i < mapSize; i++) {
            String key = buffer.readUtf();
            String baseGun = buffer.readUtf();
            int skinSize = buffer.readVarInt();
            List<SkinDataModels.SkinEntry> skins = new ArrayList<>();
            for (int j = 0; j < skinSize; j++) {
                String id = buffer.readUtf();
                String name = buffer.readUtf();
                int color = buffer.readInt();
                SkinDataModels.Rarity rarity = SkinDataModels.Rarity.byName(buffer.readUtf());
                String collection = buffer.readUtf();
                String description = buffer.readUtf();
                boolean isNew = buffer.readBoolean();
                skins.add(new SkinDataModels.SkinEntry(id, name, color, rarity, collection, description, isNew));
            }
            map.put(key, new SkinDataModels.WeaponSkins(baseGun, skins));
        }
        return map;
    }

    public void write(FriendlyByteBuf buffer) {
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
