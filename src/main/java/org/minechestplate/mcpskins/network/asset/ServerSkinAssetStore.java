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

    /**
     * 64 x 256 KiB = 16 MiB compressed. Mainly for the receiving side: {@code totalChunks} is
     * an unbounded VAR_INT that sizes the client's reassembly array. Enforced on send too, so
     * an oversized pack file fails loudly here.
     */
    public static final int MAX_CHUNKS = 64;

    /** Largest asset we will serve, derived from {@link #MAX_CHUNKS}. */
    public static final int MAX_ASSET_BYTES = MAX_CHUNKS * CHUNK_SIZE;

    /** Max distinct compressed asset payloads kept warm in memory at once. */
    private static final int HOT_CACHE_CAPACITY = 512;

    /** Per-player rate limit - way above normal usage, just here to blunt a spammy client. */
    private static final int MAX_REQUESTS_PER_SECOND = 200;

    /**
     * The request cap alone bounds nothing - a cached asset re-requested 200x/second costs no
     * I/O but still buffers 200x its size out to netty. Charging bytes is the real limit.
     */
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

    /**
     * Flags undeliverable assets at reload time instead of at first render. Heuristic - this
     * is the uncompressed size, while {@link #sendChunks} checks the real (compressed) one.
     *
     * @param sizeBytes uncompressed size, or negative if unknown
     */
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

    /**
     * Serves one asset request. Runs on a netty thread ({@code HandlerThread.NETWORK}), so
     * nothing may escape - an exception here can take the connection with it.
     */
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

            // Charged once the size is known, so the budget tracks bytes, not requests.
            if (rate.bytes.addAndGet(compressed.length) > MAX_BYTES_PER_SECOND) {
                sendThrottled(player, path);
                return;
            }

            sendChunks(player, path, compressed);
        } catch (RuntimeException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Failed to serve skin asset '{}' to {}", path, player.getGameProfile().getName(), e);
        }
    }

    /** This player's counters for the current one-second window, rolled over if stale. */
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

    /** Always replies - silence here strands the client's request forever. */
    private static void sendThrottled(ServerPlayer player, String path) {
        long now = System.currentTimeMillis();
        // Time left in this window, plus margin so the retry doesn't race the boundary.
        int retryAfter = (int) (1000L - (now % 1000L)) + 250;
        PacketDistributor.sendToPlayer(player, new SkinAssetThrottledPayload(path, retryAfter));
    }

    private final Map<Path, ZipFile> openZips = new HashMap<>();

    /**
     * Reads arrive on netty threads; {@link #closeOpenZips()} runs on the game executor during
     * a reload. The read lock spans the whole lookup-and-read so a handle can't be closed
     * mid-use - that threw {@link IllegalStateException}, which isn't an {@link IOException}
     * and escaped the catch below - and makes close-and-clear atomic against reopens.
     */
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
            // Handles stay open across requests - opening a ZipFile parses the whole central
            // directory, which is real cost per first-time asset on a large gun pack.
            // Synchronized per handle; ZipFile doesn't promise safe concurrent reads.
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

    /**
     * Stops {@code readAllBytes} pulling a runaway file into the heap before
     * {@link #sendChunks} can reject it. Looser than {@link #MAX_ASSET_BYTES} because JSON
     * compresses well enough that a much larger raw file can still fit.
     *
     * @param sizeBytes uncompressed size, or negative if unknown (read is allowed)
     */
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

    /** Called on reload, so a stale handle never keeps serving bytes from a changed pack. */
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
            // Can't really fail on an in-memory array, but don't swallow it silently.
            throw new IllegalStateException("Failed to compress skin asset in memory", e);
        } finally {
            // Only auto-end()ed if the stream created it, and we passed one in.
            deflater.end();
        }
        return out.toByteArray();
    }

    private void sendChunks(ServerPlayer player, String path, byte[] compressed) {
        int totalChunks = Math.max(1, (compressed.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (totalChunks > MAX_CHUNKS) {
            // A pack-authoring problem the admin needs to see, hence error level.
            MCPSkins.LOGGER.error(
                    "[MCPSkins] Skin asset '{}' is {} bytes compressed, over the {} byte limit - not sending. "
                            + "Shrink the file or raise MAX_CHUNKS.",
                    path, compressed.length, MAX_ASSET_BYTES);
            PacketDistributor.sendToPlayer(player, new SkinAssetMissingPayload(path));
            return;
        }

        long transferId = TRANSFER_ID.incrementAndGet();
        // Sent as sliced - buffering them all first kept a second full copy resident.
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