package org.minechestplate.mcpskins.network.asset;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.minechestplate.mcpskins.MCPSkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves skin pack assets from the server's mcpskins/ folder on request: an LRU {@link #hotCache}
 * of compressed bytes over {@link #index} (key to file or zip entry, built on reload). Client
 * paths are only ever index keys, never filesystem paths.
 */
public final class ServerSkinAssetStore implements PreparableReloadListener {
    public static final ServerSkinAssetStore INSTANCE = new ServerSkinAssetStore();

    private static final String FOLDER_NAME = MCPSkins.MOD_ID;
    private static final String ASSETS_PREFIX = "assets/";

    // Under NeoForge's clientbound payload cap (about 1 MiB).
    public static final int CHUNK_SIZE = 256 * 1024;

    // Bounds the client's reassembly array (totalChunks is a VAR_INT); enforced on send too.
    public static final int MAX_CHUNKS = 64;

    public static final int MAX_ASSET_BYTES = MAX_CHUNKS * CHUNK_SIZE;

    private static final int HOT_CACHE_CAPACITY = 512;

    private static final int MAX_REQUESTS_PER_SECOND = 200;

    // The request cap alone bounds no I/O: re-requesting a cached asset still buffers out to netty.
    private static final long MAX_BYTES_PER_SECOND = 2L * 1024 * 1024;

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
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
    }

    private static final java.util.concurrent.atomic.AtomicLong TRANSFER_ID = new java.util.concurrent.atomic.AtomicLong();

    private ServerSkinAssetStore() {
    }


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
                    closeOpenZips();
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
                if (key == null) return;
                warnIfOversized(key, fileSizeQuietly(file));
                result.put(key, AssetSource.ofFile(file));
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
                if (key == null) continue;
                warnIfOversized(key, entry.getSize());
                result.put(key, AssetSource.ofZipEntry(zipPath, entry.getName()));
            }
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to read {} for skin pack assets", zipPath, e);
        }
    }

    private static void warnIfOversized(String key, long sizeBytes) {
        if (sizeBytes > MAX_ASSET_BYTES) {
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Skin pack asset '{}' is {} bytes, over the {} byte delivery limit - "
                            + "clients will not receive it.",
                    key, sizeBytes, MAX_ASSET_BYTES);
        }
    }

    private static long fileSizeQuietly(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String toKey(String relativeToAssets) {
        int firstSlash = relativeToAssets.indexOf('/');
        if (firstSlash < 0) return null;
        String namespace = relativeToAssets.substring(0, firstSlash);
        String path = relativeToAssets.substring(firstSlash + 1);
        if (namespace.isEmpty() || path.isEmpty()) return null;
        return namespace + ":" + path;
    }


    // Netty thread (HandlerThread.NETWORK): nothing may escape, or it can take the connection down.
    public void handleRequest(ServerPlayer player, String path) {
        try {
            if (path == null || path.isBlank()) return;

            RateState rate = rollingWindow(player.getUUID());
            if (rate.count.incrementAndGet() > MAX_REQUESTS_PER_SECOND) {
                sendThrottled(player, path);
                return;
            }

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

            // Charged once the size is known, so the budget tracks bytes.
            if (rate.bytes.addAndGet(compressed.length) > MAX_BYTES_PER_SECOND) {
                sendThrottled(player, path);
                return;
            }

            sendChunks(player, path, compressed);
        } catch (RuntimeException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to serve skin asset '{}' to {}", path, player.getGameProfile().getName(), e);
        }
    }

    private RateState rollingWindow(UUID player) {
        long nowSecond = System.currentTimeMillis() / 1000L;
        RateState state = rateLimits.computeIfAbsent(player, k -> new RateState());
        if (state.windowSecond != nowSecond) {
            synchronized (state) {
                if (state.windowSecond != nowSecond) {
                    state.windowSecond = nowSecond;
                    state.count.set(0);
                    state.bytes.set(0);
                }
            }
        }
        return state;
    }

    // Always reply: silence strands the client's request.
    private static void sendThrottled(ServerPlayer player, String path) {
        long now = System.currentTimeMillis();
        int retryAfter = (int) (1000L - (now % 1000L)) + 250;
        PacketDistributor.sendToPlayer(player, new SkinAssetThrottledPayload(path, retryAfter));
    }

    private final Map<Path, ZipFile> openZips = new HashMap<>();

    // Netty-thread reads against closeOpenZips() on the game executor. Spans lookup and
    // read: a handle closed mid-use throws IllegalStateException, which the IOException catch misses.
    private final ReadWriteLock zipLock = new ReentrantReadWriteLock();

    private byte[] readBytes(String key) {
        AssetSource source = index.get(key);
        if (source == null) return null;
        try {
            if (source.plainFile() != null) {
                if (isTooLargeToRead(key, Files.size(source.plainFile()))) return null;
                return Files.readAllBytes(source.plainFile());
            }
            return readZipEntry(key, source);
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to read skin pack asset '{}'", key, e);
            return null;
        }
    }

    private byte[] readZipEntry(String key, AssetSource source) throws IOException {
        zipLock.readLock().lock();
        try {
            // Kept open (opening parses the central directory); synchronized per handle, as ZipFile promises no concurrent reads.
            ZipFile zip;
            synchronized (openZips) {
                zip = openZips.computeIfAbsent(source.zipFile(), ServerSkinAssetStore::openZipQuietly);
            }
            if (zip == null) return null;
            synchronized (zip) {
                ZipEntry entry = zip.getEntry(source.zipEntryName());
                if (entry == null) return null;
                if (isTooLargeToRead(key, entry.getSize())) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        } finally {
            zipLock.readLock().unlock();
        }
    }

    // Four times MAX_ASSET_BYTES, since JSON compresses well.
    private static boolean isTooLargeToRead(String key, long sizeBytes) {
        long limit = (long) MAX_ASSET_BYTES * 4;
        if (sizeBytes > limit) {
            MCPSkins.LOGGER.warn("[MCPSkins] Refusing to read skin asset '{}': {} bytes exceeds the {} byte read limit.",
                    key, sizeBytes, limit);
            return true;
        }
        return false;
    }

    private static ZipFile openZipQuietly(Path path) {
        try {
            return new ZipFile(path.toFile());
        } catch (IOException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to open skin pack zip '{}'", path, e);
            return null;
        }
    }

    // On reload, so a stale handle never serves a changed pack.
    private void closeOpenZips() {
        zipLock.writeLock().lock();
        try {
            synchronized (openZips) {
                for (ZipFile zip : openZips.values()) {
                    try {
                        zip.close();
                    } catch (IOException e) {
                        MCPSkins.LOGGER.warn("[MCPSkins] Failed to close a skin pack zip handle during reload", e);
                    }
                }
                openZips.clear();
            }
        } finally {
            zipLock.writeLock().unlock();
        }
    }

    private static byte[] compress(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, deflater)) {
            deflate.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compress skin asset in memory", e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private void sendChunks(ServerPlayer player, String path, byte[] compressed) {
        int totalChunks = Math.max(1, (compressed.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (totalChunks > MAX_CHUNKS) {
            MCPSkins.LOGGER.error(
                    "[MCPSkins] Skin asset '{}' is {} bytes compressed, over the {} byte limit - not sending. "
                            + "Shrink the file or raise MAX_CHUNKS.",
                    path, compressed.length, MAX_ASSET_BYTES);
            PacketDistributor.sendToPlayer(player, new SkinAssetMissingPayload(path));
            return;
        }

        long transferId = TRANSFER_ID.incrementAndGet();
        for (int i = 0; i < totalChunks; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(compressed.length, from + CHUNK_SIZE);
            byte[] slice = java.util.Arrays.copyOfRange(compressed, from, to);
            PacketDistributor.sendToPlayer(player, new SkinAssetChunkPayload(transferId, path, i, totalChunks, slice));
        }
    }

    public void forgetPlayer(UUID player) {
        rateLimits.remove(player);
    }
}