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
     * Hard ceiling on how many chunks one asset may be split into - 64 x 256 KiB = 16 MiB
     * compressed, far above any real skin texture or geo-model.
     * <p>
     * This exists mainly for the <em>receiving</em> side. {@code totalChunks} arrives as an
     * unbounded VAR_INT, and the client allocates its reassembly array straight from it, so
     * without a cap a single 6-byte packet from a hostile server asks for a ~16 GiB array.
     * Enforced on send too, so an oversized pack file fails loudly here instead of producing
     * a transfer no well-behaved client will accept.
     */
    public static final int MAX_CHUNKS = 64;

    /** Largest asset we will serve, derived from {@link #MAX_CHUNKS}. */
    public static final int MAX_ASSET_BYTES = MAX_CHUNKS * CHUNK_SIZE;

    /** Max distinct compressed asset payloads kept warm in memory at once. */
    private static final int HOT_CACHE_CAPACITY = 512;

    /** Per-player rate limit - way above normal usage, just here to blunt a spammy client. */
    private static final int MAX_REQUESTS_PER_SECOND = 200;

    /**
     * Per-player outbound budget for asset data.
     * <p>
     * The request cap alone doesn't bound anything that matters. Once an asset is in
     * {@link #hotCache} a re-request costs no I/O, so 200 requests/second for the largest
     * indexed asset is 200x its size per second of allocation and unbacked-pressured netty
     * buffering, per attacking player - a 4 MiB asset gets you most of a gigabyte a second
     * out of a limit that looks conservative. Charging bytes is what actually caps it.
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
     * Flags assets too big to ever be delivered, at reload time rather than at first render.
     * <p>
     * Compares the <em>uncompressed</em> size, so it's a heuristic: the real check is on the
     * compressed payload in {@link #sendChunks}. Textures are already-deflated PNGs that
     * barely shrink again, so in practice anything tripping this will trip that too.
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
     * Serves one asset request. Runs on a netty thread (see {@code HandlerThread.NETWORK} in
     * {@code MCPSkins#registerNetworking}), so it must never let a throwable escape - an
     * exception here would surface inside NeoForge's payload handler rather than anywhere
     * useful, and can take the connection with it.
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

            // Charged after the payload is known, so the budget reflects bytes actually put
            // on the wire rather than requests made.
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

    /**
     * Tells a throttled client when to come back. Always replies - see
     * {@link SkinAssetThrottledPayload} for why silence here used to strand the asset.
     */
    private static void sendThrottled(ServerPlayer player, String path) {
        long now = System.currentTimeMillis();
        // Time left in the current window, plus a small margin so the retry lands in the next
        // one rather than racing the boundary.
        int retryAfter = (int) (1000L - (now % 1000L)) + 250;
        PacketDistributor.sendToPlayer(player, new SkinAssetThrottledPayload(path, retryAfter));
    }

    private final Map<Path, ZipFile> openZips = new HashMap<>();

    /**
     * Guards {@link #openZips} across the two threads that touch it: reads arrive on netty
     * threads, while {@link #closeOpenZips()} runs on the game executor during a reload.
     * <p>
     * A plain concurrent map wasn't enough. Closing a handle outside the same lock a reader
     * holds means a reload landing mid-request makes {@code getEntry}/{@code getInputStream}
     * throw {@link IllegalStateException} ("zip file closed"), which isn't an
     * {@link IOException} and so escaped the catch below entirely. And a reader that reached
     * the map after {@code clear()} would reopen the zip into the now-discarded map: a leaked
     * handle that also keeps serving pre-reload bytes, the exact thing closing them prevents.
     * <p>
     * The read lock is held for the whole lookup-and-read so a handle cannot be closed while
     * in use; the write lock makes close-and-clear atomic against that.
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
            // Kept open across requests instead of reopening per miss: opening a ZipFile
            // reads and parses the whole central directory, which for a large gun pack
            // (hundreds of entries) is real, avoidable cost on every first-time asset -
            // exactly the kind of work that used to also stall the main server thread
            // before HandlerThread.NETWORK was added for this payload's handler. Access is
            // synchronized per-handle since java.util.zip.ZipFile's contract doesn't commit
            // to safe concurrent reads from multiple threads.
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
     * Refuses to materialize an asset that could never be delivered anyway.
     * <p>
     * Deliberately looser than {@link #MAX_ASSET_BYTES}: geo-model JSON compresses very well,
     * so a raw file several times the delivery limit can still fit once deflated. The point
     * here is only to stop {@code readAllBytes} pulling a runaway file into the server's heap
     * before {@link #sendChunks} gets a chance to reject it.
     *
     * @param sizeBytes uncompressed size, or negative if unknown (then we allow the read)
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

    /** Closes every zip handle opened by {@link #readBytes}. Called on reload so a stale
     *  handle never keeps serving bytes from a pack that's since changed on disk. */
    private void closeOpenZips() {
        // Write lock: waits for in-flight readers to finish and blocks new ones, so no
        // handle is ever closed out from under a read in progress.
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
            // DeflaterOutputStream#close only calls end() on a deflater it created itself.
            // We hand it one, so the native zlib stream is ours to release - without this
            // every cache miss leaks one until a cleaner eventually gets to it.
            deflater.end();
        }
        return out.toByteArray();
    }

    private void sendChunks(ServerPlayer player, String path, byte[] compressed) {
        int totalChunks = Math.max(1, (compressed.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (totalChunks > MAX_CHUNKS) {
            // Refuse rather than emit a transfer the client is required to reject. Logged at
            // error level because this is a pack-authoring problem the admin needs to see.
            MCPSkins.LOGGER.error(
                    "[MCPSkins] Skin asset '{}' is {} bytes compressed, over the {} byte limit - not sending. "
                            + "Shrink the file or raise MAX_CHUNKS.",
                    path, compressed.length, MAX_ASSET_BYTES);
            PacketDistributor.sendToPlayer(player, new SkinAssetMissingPayload(path));
            return;
        }

        long transferId = TRANSFER_ID.incrementAndGet();
        // Sliced and sent one at a time - buffering every chunk into a List first meant a
        // second full copy of the asset resident before a single byte went out.
        for (int i = 0; i < totalChunks; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(compressed.length, from + CHUNK_SIZE);
            byte[] slice = java.util.Arrays.copyOfRange(compressed, from, to);
            PacketDistributor.sendToPlayer(player, new SkinAssetChunkPayload(transferId, path, i, totalChunks, slice));
        }
    }

    /** Drops a disconnected player's rate-limit state. Safe to call even if they never sent a request. */
    public void forgetPlayer(UUID player) {
        rateLimits.remove(player);
    }
}