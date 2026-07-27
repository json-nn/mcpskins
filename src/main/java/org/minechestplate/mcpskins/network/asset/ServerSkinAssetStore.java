package org.minechestplate.mcpskins.network.asset;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.network.asset.SkinAssetChunkPayload;
import org.minechestplate.mcpskins.network.asset.SkinAssetMissingPayload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads asset bytes (textures, geo-models) out of the server's mcpskins/ folder and ships
 * them to clients on request. Two-layer lookup: a small LRU {@link #hotCache} of already
 * compressed payloads, backed by {@link #index}, a path -&gt; source map built on scan.
 * <p>
 * Client-supplied paths are only ever used as a lookup key, never as a real filesystem
 * path - a bad key just gets a cache miss.
 */
public final class ServerSkinAssetStore implements PreparableReloadListener {
    public static final ServerSkinAssetStore INSTANCE = new ServerSkinAssetStore();

    private static final String FOLDER_NAME = MCPSkins.MOD_ID;
    private static final String ASSETS_PREFIX = "assets/";

    /** Stays under NeoForge's ~1 MiB clientbound payload cap. */
    public static final int CHUNK_SIZE = 256 * 1024;

    /** Max distinct compressed asset payloads kept warm in memory at once. */
    private static final int HOT_CACHE_CAPACITY = 512;

    /** Per-player rate limit - way above normal usage, just here to blunt a spammy client. */
    private static final int MAX_REQUESTS_PER_SECOND = 200;

    private record AssetSource(Path plainFile, Path zipFile, String zipEntryName) {
        static AssetSource ofFile(Path file) {
            return new AssetSource(file, null, null);
        }

        static AssetSource ofZipEntry(Path zip, String entryName) {
            return new AssetSource(null, zip, entryName);
        }
    }

    private volatile Map<String, AssetSource> index = Map.of();

    private final Map<String, byte[]> hotCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(HOT_CACHE_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > HOT_CACHE_CAPACITY;
                }
            });

    private final Map<UUID, RateState> rateLimits = new ConcurrentHashMap<>();

    private static final class RateState {
        volatile long windowSecond = -1;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
    }

    private static final java.util.concurrent.atomic.AtomicLong TRANSFER_ID = new java.util.concurrent.atomic.AtomicLong();

    private ServerSkinAssetStore() {
    }

    // ------------------------------------------------------------------
    // Reload lifecycle
    // ------------------------------------------------------------------

    @NotNull
    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        return CompletableFuture.supplyAsync(this::scan, backgroundExecutor)
                .thenCompose(barrier::wait)
                .thenAcceptAsync(scanned -> {
                    index = scanned;
                    hotCache.clear();
                    MCPSkins.LOGGER.info("[MCPSkins] Skin asset store indexed {} file(s) for network delivery.", scanned.size());
                }, gameExecutor);
    }

    private Map<String, AssetSource> scan() {
        Map<String, AssetSource> result = new java.util.HashMap<>();
        Path root = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
        if (!Files.isDirectory(root)) return result;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    scanFolder(entry, result);
                } else if (isZip(entry)) {
                    scanZip(entry, result);
                }
            }
        } catch (IOException e) {
            MCPSkins.LOGGER.error("[MCPSkins] Failed to scan {} for skin pack assets", root, e);
        }
        return result;
    }

    private static boolean isZip(Path entry) {
        return Files.isRegularFile(entry) && entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private void scanFolder(Path packRoot, Map<String, AssetSource> result) {
        Path assetsDir = packRoot.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return;
        try (var walk = Files.walk(assetsDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String key = toKey(assetsDir.relativize(file).toString().replace('\\', '/'));
                if (key != null) result.put(key, AssetSource.ofFile(file));
            });
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to walk {} for skin pack assets", assetsDir, e);
        }
    }

    private void scanZip(Path zipPath, Map<String, AssetSource> result) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(ASSETS_PREFIX)) continue;
                String key = toKey(name.substring(ASSETS_PREFIX.length()));
                if (key != null) result.put(key, AssetSource.ofZipEntry(zipPath, entry.getName()));
            }
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to read {} for skin pack assets", zipPath, e);
        }
    }

    /** "textures/skins/rifle/cobra.png" -&gt; "mcpskins:textures/skins/rifle/cobra.png". */
    private static String toKey(String relativeToAssets) {
        int firstSlash = relativeToAssets.indexOf('/');
        if (firstSlash < 0) return null;
        String namespace = relativeToAssets.substring(0, firstSlash);
        String path = relativeToAssets.substring(firstSlash + 1);
        if (namespace.isEmpty() || path.isEmpty()) return null;
        return namespace + ":" + path;
    }

    // ------------------------------------------------------------------
    // Serving requests
    // ------------------------------------------------------------------

    public void handleRequest(ServerPlayer player, String path) {
        if (path == null || path.isBlank() || !withinRateLimit(player.getUUID())) return;

        byte[] compressed = hotCache.get(path);
        if (compressed == null) {
            byte[] raw = readBytes(path);
            if (raw == null) {
                PacketDistributor.sendToPlayer(player, new SkinAssetMissingPayload(path));
                return;
            }
            compressed = compress(raw);
            hotCache.put(path, compressed);
        }

        sendChunks(player, path, compressed);
    }

    private boolean withinRateLimit(UUID player) {
        long nowSecond = System.currentTimeMillis() / 1000L;
        RateState state = rateLimits.computeIfAbsent(player, k -> new RateState());
        if (state.windowSecond != nowSecond) {
            synchronized (state) {
                if (state.windowSecond != nowSecond) {
                    state.windowSecond = nowSecond;
                    state.count.set(0);
                }
            }
        }
        return state.count.incrementAndGet() <= MAX_REQUESTS_PER_SECOND;
    }

    private byte[] readBytes(String key) {
        AssetSource source = index.get(key);
        if (source == null) return null;
        try {
            if (source.plainFile() != null) {
                return Files.readAllBytes(source.plainFile());
            }
            try (ZipFile zip = new ZipFile(source.zipFile().toFile())) {
                ZipEntry entry = zip.getEntry(source.zipEntryName());
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to read skin pack asset '{}'", key, e);
            return null;
        }
    }

    private static byte[] compress(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, new Deflater(Deflater.BEST_SPEED))) {
            deflate.write(raw);
        } catch (IOException e) {
            // Can't really fail on an in-memory array, but don't swallow it silently.
            throw new IllegalStateException("Failed to compress skin asset in memory", e);
        }
        return out.toByteArray();
    }

    private void sendChunks(ServerPlayer player, String path, byte[] compressed) {
        int totalChunks = Math.max(1, (compressed.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        long transferId = TRANSFER_ID.incrementAndGet();
        List<SkinAssetChunkPayload> chunks = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(compressed.length, from + CHUNK_SIZE);
            byte[] slice = java.util.Arrays.copyOfRange(compressed, from, to);
            chunks.add(new SkinAssetChunkPayload(transferId, path, i, totalChunks, slice));
        }
        for (SkinAssetChunkPayload chunk : chunks) {
            PacketDistributor.sendToPlayer(player, chunk);
        }
    }

    /** Drops a disconnected player's rate-limit state. Safe to call even if they never sent a request. */
    public void forgetPlayer(UUID player) {
        rateLimits.remove(player);
    }
}