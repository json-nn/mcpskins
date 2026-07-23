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
 * Scans the {@code mcpskins/} folder in the game directory for skin packs: plain folders
 * or {@code .zip} files with an {@code assets/} and/or {@code data/} tree at their root, no
 * {@code pack.mcmeta} required. Found packs are registered required (see
 * {@link PackSelectionConfig}) - force-enabled and applied automatically, including to
 * worlds that already exist.
 * <p>
 * The {@code data/} half is loaded server-side by
 * {@link org.minechestplate.mcpskins.skin.SkinManager}, so a dedicated server needs the same
 * pack dropped into its own {@code mcpskins/} folder too.
 */
public enum MCPSkinsPackFinder implements RepositorySource {
    INSTANCE;

    private static final String FOLDER_NAME = MCPSkins.MOD_ID;

    /**
     * {@link PackType} this finder's packs report for the format-compatibility check in
     * their synthetic metadata. Set once from the physical side in {@code MCPSkins}'
     * constructor; doesn't affect which files get served, only which "current pack format"
     * number packs are checked against.
     */
    public PackType packType = PackType.CLIENT_RESOURCES;

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

    /**
     * Builds a {@link Pack} for one folder/{@code .zip} entry under {@code mcpskins/}, or
     * {@code null} if it's not a usable pack (wrong file type, unreadable, or has no
     * {@code assets/}/{@code data/} content).
     */
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

            boolean hasContent = !raw.getNamespaces(PackType.CLIENT_RESOURCES).isEmpty()
                    || !raw.getNamespaces(PackType.SERVER_DATA).isEmpty();
            if (!hasContent) {
                raw.close();
                return null;
            }

            PackMetadataSection meta = new PackMetadataSection(
                    Component.literal("MCPSkins skin pack: " + displayName),
                    SharedConstants.getCurrentVersion().getPackVersion(packType),
                    Optional.empty());

            MCPSkinsPackResources wrapped = new MCPSkinsPackResources(locationInfo, meta, raw);
            PackSelectionConfig selectionConfig = new PackSelectionConfig(true, Pack.Position.TOP, false);

            return Pack.readMetaAndCreate(locationInfo, wrapped, packType, selectionConfig);
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

                Multiplayer: the data/ half is loaded server-side, so a dedicated server
                needs the same folder/zip dropped into its own mcpskins/ folder too - not
                just the client's.
                """;
        try {
            Files.writeString(root.resolve("README.txt"), readme);
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("Failed to write README in {}", root, e);
        }
    }
}
