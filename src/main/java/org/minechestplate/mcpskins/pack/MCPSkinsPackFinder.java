package org.minechestplate.mcpskins.pack;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.neoforged.fml.loading.FMLPaths;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Scans {@code mcpskins/} in the game dir for skin packs and registers them as required
 * data packs. Folders or .zip files with a {@code data/} tree at the root, no
 * {@code pack.mcmeta} needed.
 * <p>
 * Server-side only - registered for SERVER_DATA, never CLIENT_RESOURCES. Asset bytes
 * (textures, models) get streamed to clients on demand instead, see
 * {@code ServerSkinAssetStore}/{@code ClientSkinAssetCache}.
 */
public enum MCPSkinsPackFinder implements RepositorySource {
    INSTANCE;

    private static final String FOLDER_NAME = MCPSkins.MOD_ID;

    /** Always SERVER_DATA, see class javadoc. */
    private static final PackType PACK_TYPE = PackType.SERVER_DATA;

    @Override
    public void loadPacks(Consumer<Pack> onLoad) {
        for (Pack pack : discoverPacks()) {
            onLoad.accept(pack);
        }
    }

    private List<Pack> discoverPacks() {
        List<Pack> result = new ArrayList<>();
        Path root = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);

        if (!Files.isDirectory(root)) {
            try {
                Files.createDirectories(root);
                writeReadme(root);
            } catch (IOException e) {
                MCPSkins.LOGGER.warn("Failed to create {} folder", root, e);
                return result;
            }
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                Pack pack = tryCreatePack(entry);
                if (pack != null) {
                    result.add(pack);
                }
            }
        } catch (IOException e) {
            MCPSkins.LOGGER.error("Failed to scan {} for skin packs", root, e);
        }

        if (!result.isEmpty()) {
            MCPSkins.LOGGER.info("MCPSkins: loaded {} skin pack(s) from {}", result.size(), root);
        }
        return result;
    }

    /** Builds a Pack for one folder/zip entry under mcpskins/, or null if it's not usable. */
    private Pack tryCreatePack(Path entry) {
        String fileName = entry.getFileName().toString();
        boolean isZip = Files.isRegularFile(entry) && fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
        boolean isDir = Files.isDirectory(entry);
        if (!isZip && !isDir) return null;

        PackResources raw = null;
        try {
            String displayName = isZip ? fileName.substring(0, fileName.length() - 4) : fileName;
            PackLocationInfo locationInfo = new PackLocationInfo(
                    "mcpskins_pack/" + fileName, Component.literal(displayName), PackSource.BUILT_IN, Optional.empty());

            Pack.ResourcesSupplier rawSupplier = isDir
                    ? new PathPackResources.PathResourcesSupplier(entry)
                    : new FilePackResources.FileResourcesSupplier(entry);
            raw = rawSupplier.openPrimary(locationInfo);

            // Skip packs with no data/ content - nothing here for SkinManager to load.
            boolean hasContent = !raw.getNamespaces(PackType.SERVER_DATA).isEmpty();
            if (!hasContent) {
                raw.close();
                return null;
            }

            PackMetadataSection meta = new PackMetadataSection(
                    Component.literal("MCPSkins skin pack: " + displayName),
                    SharedConstants.getCurrentVersion().getPackVersion(PACK_TYPE),
                    Optional.empty());

            MCPSkinsPackResources wrapped = new MCPSkinsPackResources(locationInfo, meta, raw);
            PackSelectionConfig selectionConfig = new PackSelectionConfig(true, Pack.Position.TOP, false);

            return Pack.readMetaAndCreate(locationInfo, wrapped, PACK_TYPE, selectionConfig);
        } catch (Exception e) {
            MCPSkins.LOGGER.warn("MCPSkins: failed to load potential skin pack '{}', skipping", fileName, e);
            if (raw != null) {
                raw.close();
            }
            return null;
        }
    }

    private void writeReadme(Path root) {
        String readme = """
                MCPSkins skin packs
                ====================

                Drop a folder or a .zip file right here to add TACZ weapon skins. No
                pack.mcmeta needed, no need to create a world first, nothing to enable
                manually - it's picked up automatically next time the game (or /reload)
                starts.

                Each folder/zip should contain, at its root:

                  assets/mcpskins/textures/skins/<gun_addon>/<gun>/<skin>.png   (and _icon.png, optional)
                  assets/<gun_addon>/geo_models/gun/<gun>_geo__skin_<skin>.json (optional geometry override)
                  data/mcpskins/skins/<gun>.json                               (skin registry entries)

                Geometry overrides go under the target gun addon's own namespace (e.g.
                create_armorer), not mcpskins' - that's the namespace TACZ looks up model
                overrides in.

                Server-side only: this whole folder - assets/ AND data/ - only needs to
                exist on the SERVER (or, in singleplayer, wherever the world is hosted).
                Textures and models are sent to each client over the network the moment
                they're actually needed in-game; players never need a copy of this folder,
                and nothing in it is exposed as a resource pack for them to download or
                extract. If you're editing/previewing a pack, do it against a local server
                or singleplayer world so you see it exactly as players will.
                """;
        try {
            Files.writeString(root.resolve("README.txt"), readme);
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("Failed to write README in {}", root, e);
        }
    }
}