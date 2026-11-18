package org.minechestplate.mcpskins.pack;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** A skin pack with a synthetic pack.mcmeta; everything else delegates to the disk-backed pack. */
final class MCPSkinsPackResources extends AbstractPackResources implements Pack.ResourcesSupplier {
    private final PackMetadataSection meta;
    private final PackResources delegate;

    MCPSkinsPackResources(PackLocationInfo info, PackMetadataSection meta, PackResources delegate) {
        super(info);
        this.meta = meta;
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        return deserializer.getMetadataSectionName().equals("pack") ? (T) meta : null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        delegate.listResources(type, namespace, path, output);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return delegate.getNamespaces(type);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        return delegate.getResource(type, location);
    }

    @Override
    public PackResources openPrimary(PackLocationInfo info) {
        return this;
    }

    @Override
    public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
        return this;
    }
}
