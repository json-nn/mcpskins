package org.minechestplate.mcpskins.client.pack;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.resources.ResourceLocation;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A memory-backed client resource pack, so server-streamed assets can be found through the
 * normal {@code ResourceManager}. A shader locates a PBR map by appending {@code _n} or
 * {@code _s} to a texture's path and asking the resource manager, so bytes that exist only as a
 * GL texture are invisible to it.
 * <p>
 * Registered at startup so its namespace is known, but answered per call, so content arriving
 * long after the last reload is still served.
 */
public class ClientSkinResourcePack implements PackResources {

    public static final String PACK_ID = "mcpskins_streamed";

    private static final Map<ResourceLocation, byte[]> CONTENT = new ConcurrentHashMap<>();

    /** Counts lookups under our texture tree, to tell "asked and missed" from "never asked". */
    private static final Map<String, Integer> LOOKUPS = new ConcurrentHashMap<>();

    private final PackLocationInfo location;

    public ClientSkinResourcePack(PackLocationInfo location) {
        this.location = location;
    }

    public static void put(ResourceLocation path, byte[] bytes) {
        CONTENT.put(path, bytes);
    }

    public static boolean has(ResourceLocation path) {
        return CONTENT.containsKey(path);
    }

    /** The stored bytes, for a caller that needs the file rather than a resource handle. */
    public static byte[] bytes(ResourceLocation path) {
        return CONTENT.get(path);
    }

    public static void clear() {
        CONTENT.clear();
        LOOKUPS.clear();
    }

    public static Map<String, Integer> lookups() {
        return Map.copyOf(LOOKUPS);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation target) {
        if (type != PackType.CLIENT_RESOURCES) return null;

        if (target.getPath().startsWith("textures/skins/")) {
            LOOKUPS.merge(target.toString(), 1, Integer::sum);
        }
        byte[] bytes = CONTENT.get(target);
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !MCPSkins.MOD_ID.equals(namespace)) return;
        CONTENT.forEach((location, bytes) -> {
            if (location.getNamespace().equals(namespace) && location.getPath().startsWith(path)) {
                output.accept(location, () -> new ByteArrayInputStream(bytes));
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        // Declared unconditionally: the namespace has to be known at reload for later lookups
        // in it to reach this pack at all, and content arrives long after that.
        return type == PackType.CLIENT_RESOURCES ? Set.of(MCPSkins.MOD_ID) : Set.of();
    }

    /** Must answer the pack section, or {@code Pack.readMetaAndCreate} yields null and the
     *  repository trips over it on the first reload. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        if (deserializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(
                    Component.literal("Skin assets streamed from the server"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES),
                    Optional.empty());
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public void close() {
    }

    /** Always on and at the top, so a player cannot switch off a server's skin assets. */
    public static Pack createPack() {
        PackLocationInfo info = new PackLocationInfo(PACK_ID,
                Component.literal("MCPSkins streamed assets"), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new ClientSkinResourcePack(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new ClientSkinResourcePack(location);
            }
        };
        Pack pack = Pack.readMetaAndCreate(info, supplier, PackType.CLIENT_RESOURCES,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack == null) {
            MCPSkins.LOGGER.error("[MCPSkins] Could not build the streamed asset pack; shader PBR maps "
                    + "will not be visible to the resource manager.");
        }
        return pack;
    }
}
