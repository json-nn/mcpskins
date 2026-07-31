package org.minechestplate.mcpskins.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.network.asset.RequestSkinAssetPayload;
import org.minechestplate.mcpskins.network.asset.ServerSkinAssetStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Client half of the skin asset protocol. Requests an asset the first time
 * {@link SkinAssetResolver} needs it, reassembles the chunked response, and either registers
 * it as a texture or hands it to {@link TaczGeoModelInjector}.
 * <p>
 * Reads are safe from any thread. Writes (onChunk/onMissing/onThrottled) must run on the
 * client main thread - texture registration is a GL call.
 */
public final class ClientSkinAssetCache {

    private enum State { PENDING, PRESENT, MISSING }

    private enum Kind { TEXTURE, GEO_MODEL }

    /** @param attempts consecutive unanswered sends; only widens the backoff */
    private record PendingRequest(Kind kind, ResourceLocation target,
                                  long retryAtMillis, int attempts, long firstSentAtMillis) {
        PendingRequest resent(long now) {
            int nextAttempt = attempts + 1;
            long delay = Math.min(MAX_RETRY_DELAY_MILLIS,
                    INITIAL_RETRY_DELAY_MILLIS << Math.min(attempts, 4));
            return new PendingRequest(kind, target, now + delay, nextAttempt, firstSentAtMillis);
        }

        /** Server asked us to wait. Restarts the backoff - it answered, so it isn't the case backoff is for. */
        PendingRequest deferredUntil(long deadline) {
            return new PendingRequest(kind, target, deadline, 0, firstSentAtMillis);
        }
    }

    private record Transfer(String path, int totalChunks, byte[][] parts, long startedAtMillis) {
        boolean isComplete() {
            for (byte[] part : parts) {
                if (part == null) return false;
            }
            return true;
        }
    }

    private static final Map<String, State> STATE = new ConcurrentHashMap<>();
    private static final Map<String, PendingRequest> PENDING_META = new ConcurrentHashMap<>();
    private static final Map<Long, Transfer> TRANSFERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DynamicTexture> REGISTERED_TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_DECODE_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * Bumped whenever a key leaves PENDING. The render caches store the generation they were
     * built at and rebuild when it moves; without that, an asset arriving after a cache entry
     * was built would never be picked up (see {@link PatchedGunDisplayCache}).
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    public static int generation() {
        return GENERATION.get();
    }

    private static final long TRANSFER_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_INFLATED_BYTES = ServerSkinAssetStore.MAX_ASSET_BYTES;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 15_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;

    /**
     * Only condition that marks a key MISSING through inaction. Retries are driven from the
     * render path, so an expired deadline means "no frames lately", not "server didn't answer" -
     * an attempt cap here would blank skins after ordinary client stalls.
     */
    private static final long GIVE_UP_AFTER_MILLIS = 5 * 60_000L;

    private ClientSkinAssetCache() {
    }

    // ------------------------------------------------------------------
    // Read side - called from SkinAssetResolver, potentially every frame
    // ------------------------------------------------------------------

    public static boolean checkOrRequestTexture(ResourceLocation location) {
        return checkOrRequest(location.toString(), Kind.TEXTURE, location);
    }

    /**
     * @param physical          real asset path; the request and cache key
     * @param collapsedIdentity key TACZ's model registry looks the parsed model up by
     */
    public static boolean checkOrRequestGeoModel(ResourceLocation physical, ResourceLocation collapsedIdentity) {
        return checkOrRequest(physical.toString(), Kind.GEO_MODEL, collapsedIdentity);
    }

    private static boolean checkOrRequest(String key, Kind kind, ResourceLocation target) {
        State state = STATE.get(key);
        if (state == State.PRESENT) return true;
        if (state == State.MISSING) return false;

        // Rendering continues for a frame or two after a disconnect, and sendToServer throws
        // on a null connection rather than no-op'ing. Guards every send below, including the
        // retry. State is left untouched so the next frame retries for free.
        if (Minecraft.getInstance().getConnection() == null) return false;

        long now = System.currentTimeMillis();
        if (state == null) {
            // putIfAbsent, not put - racing render calls shouldn't fire two requests.
            if (STATE.putIfAbsent(key, State.PENDING) == null) {
                PENDING_META.put(key, new PendingRequest(kind, target, now + INITIAL_RETRY_DELAY_MILLIS, 1, now));
                PacketDistributor.sendToServer(new RequestSkinAssetPayload(key));
            }
            return false;
        }

        retryIfOverdue(key, now);
        return false;
    }

    private static void retryIfOverdue(String key, long now) {
        PendingRequest meta = PENDING_META.get(key);
        if (meta == null || now < meta.retryAtMillis()) return;

        if (now - meta.firstSentAtMillis() > GIVE_UP_AFTER_MILLIS) {
            if (PENDING_META.remove(key, meta)) {
                STATE.put(key, State.MISSING);
                GENERATION.incrementAndGet();
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Giving up on skin asset '{}' - no reply in {} ms across {} attempt(s).",
                        key, now - meta.firstSentAtMillis(), meta.attempts());
            }
            return;
        }

        // CAS so concurrent render calls can't turn one overdue request into a burst.
        if (PENDING_META.replace(key, meta, meta.resent(now))) {
            PacketDistributor.sendToServer(new RequestSkinAssetPayload(key));
        }
    }

    public static void onThrottled(String path, int retryAfterMillis) {
        PendingRequest meta = PENDING_META.get(path);
        if (meta == null) return;
        long deadline = System.currentTimeMillis() + Math.clamp(retryAfterMillis, 100, 30_000);
        PENDING_META.replace(path, meta, meta.deferredUntil(deadline));
    }

    // ------------------------------------------------------------------
    // Write side - called by the network payload handlers
    // ------------------------------------------------------------------

    public static void onMissing(String path) {
        if (isStaleArrival(path)) return;
        resolve(path, State.MISSING);
    }

    /**
     * Replies are dispatched through {@code enqueueWork}, so one queued at disconnect runs
     * after {@link #clearAll()}. Acting on it would write a terminal MISSING for a key the
     * next session never asked about.
     */
    private static boolean isStaleArrival(String path) {
        return STATE.get(path) != State.PENDING;
    }

    /** Every transition out of PENDING goes through here so the generation stays accurate. */
    private static void resolve(String key, State state) {
        STATE.put(key, state);
        PENDING_META.remove(key);
        GENERATION.incrementAndGet();
    }

    public static void onChunk(long transferId, String path, int index, int totalChunks, byte[] data) {
        if (isStaleArrival(path)) return;

        // totalChunks is an unbounded VAR_INT that sizes the array below - bound it first.
        if (totalChunks <= 0 || totalChunks > ServerSkinAssetStore.MAX_CHUNKS
                || index < 0 || index >= totalChunks) {
            MCPSkins.LOGGER.warn("[MCPSkins] Dropping malformed skin asset chunk for '{}' ({}/{})", path, index, totalChunks);
            return;
        }

        pruneStaleTransfers();

        Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
                id -> new Transfer(path, totalChunks, new byte[totalChunks][], System.currentTimeMillis()));

        // index was checked against this packet's totalChunks, but the array was sized by the
        // packet that opened the transfer. Both must agree before writing.
        if (transfer.totalChunks() != totalChunks || !transfer.path().equals(path)) {
            MCPSkins.LOGGER.warn(
                    "[MCPSkins] Dropping inconsistent skin asset chunk on transfer {}: got '{}' ({} chunks), expected '{}' ({} chunks)",
                    transferId, path, totalChunks, transfer.path(), transfer.totalChunks());
            return;
        }
        transfer.parts()[index] = data;

        if (!transfer.isComplete()) return;
        TRANSFERS.remove(transferId);

        byte[] compressed = concat(transfer.parts());
        byte[] raw;
        try {
            raw = decompress(compressed);
        } catch (IOException e) {
            if (WARNED_DECODE_FAILURES.add(path)) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to decompress skin asset '{}'", path, e);
            }
            resolve(path, State.MISSING);
            return;
        }

        finish(path, raw);
    }

    private static void pruneStaleTransfers() {
        if (TRANSFERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        TRANSFERS.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis() > TRANSFER_TIMEOUT_MILLIS);
    }

    private static byte[] concat(byte[][] parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Bounded inflate - a hostile server can ship a few KiB that expands to gigabytes. */
    private static byte[] decompress(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 2));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_INFLATED_BYTES) {
                    throw new IOException("Inflated skin asset exceeds the " + MAX_INFLATED_BYTES + " byte limit");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            // Only auto-end()ed if the stream created it, and we passed one in.
            inflater.end();
        }
    }

    private static void finish(String path, byte[] rawBytes) {
        PendingRequest meta = PENDING_META.get(path);
        if (meta == null) {
            // Shouldn't happen. Leave the state alone so the retry path can repair it.
            MCPSkins.LOGGER.debug("[MCPSkins] Received asset '{}' with no pending request metadata; ignoring.", path);
            return;
        }

        boolean ok = switch (meta.kind()) {
            case TEXTURE -> registerTexture(meta.target(), rawBytes);
            case GEO_MODEL -> TaczGeoModelInjector.inject(meta.target(), rawBytes);
        };

        resolve(path, ok ? State.PRESENT : State.MISSING);
    }

    private static boolean registerTexture(ResourceLocation location, byte[] pngBytes) {
        try (InputStream in = new ByteArrayInputStream(pngBytes)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture;
            try {
                texture = new DynamicTexture(image);
            } catch (RuntimeException e) {
                image.close(); // DynamicTexture takes ownership only on success
                throw e;
            }
            Minecraft.getInstance().getTextureManager().register(location, texture);
            DynamicTexture previous = REGISTERED_TEXTURES.put(location, texture);
            if (previous != null) {
                previous.close();
            }
            return true;
        } catch (IOException | RuntimeException e) {
            if (WARNED_DECODE_FAILURES.add(location.toString())) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to decode network-delivered texture '{}'", location, e);
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    /**
     * Drops all cached assets and frees their GPU textures. Called on resource reload and on
     * disconnect; must be on the client main thread.
     */
    public static void clearAll() {
        STATE.clear();
        PENDING_META.clear();
        TRANSFERS.clear();
        WARNED_DECODE_FAILURES.clear();

        // release() unregisters and closes. Leaving the registrations behind stranded a dead
        // texture per asset of every server visited. Double-close is a no-op.
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : REGISTERED_TEXTURES.keySet()) {
            textureManager.release(location);
        }
        REGISTERED_TEXTURES.clear();

        GENERATION.incrementAndGet();
    }
}
