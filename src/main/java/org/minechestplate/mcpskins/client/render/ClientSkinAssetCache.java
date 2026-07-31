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
 * Client-side counterpart to {@code ServerSkinAssetStore}: requests an asset the first
 * time {@link SkinAssetResolver} needs it, reassembles the (possibly chunked) response,
 * and registers it as a texture or feeds it to {@link TaczGeoModelInjector}.
 * <p>
 * Read side is safe to call from any thread. Write side (onChunk/onMissing) always runs
 * on the client main thread, since texture registration is a GL call.
 */
public final class ClientSkinAssetCache {

    private enum State { PENDING, PRESENT, MISSING }

    private enum Kind { TEXTURE, GEO_MODEL }

    /**
     * An outstanding request.
     *
     * @param retryAtMillis when to stop waiting and re-send
     * @param attempts      consecutive unanswered sends, used only to widen the backoff.
     *                      Reset by {@link #deferredUntil}
     * @param firstSentAtMillis the single terminal condition - see
     *                          {@link #GIVE_UP_AFTER_MILLIS}
     */
    private record PendingRequest(Kind kind, ResourceLocation target,
                                  long retryAtMillis, int attempts, long firstSentAtMillis) {
        /**
         * Backs off exponentially rather than re-sending on a fixed interval, so a client
         * that stalls repeatedly doesn't keep poking a server that was never the problem.
         */
        PendingRequest resent(long now) {
            int nextAttempt = attempts + 1;
            long delay = Math.min(MAX_RETRY_DELAY_MILLIS,
                    INITIAL_RETRY_DELAY_MILLIS << Math.min(attempts, 4));
            return new PendingRequest(kind, target, now + delay, nextAttempt, firstSentAtMillis);
        }

        /**
         * Server said "come back later". Clears the attempt count so the backoff restarts
         * narrow: a throttle reply is proof the server is alive and cooperating, which is the
         * opposite of the situation backing off is meant to relieve. Without the reset, a
         * client throttled while pulling many assets at once (login, exactly when it matters)
         * would widen its own backoff on every successful round-trip.
         * {@code firstSentAtMillis} is untouched, so the absolute deadline still bounds the loop.
         */
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
     * Bumped whenever an asset's resolution outcome changes, i.e. whenever a key moves off
     * PENDING. Downstream render caches record the value they were built at and rebuild when
     * it moves, which is how a late-arriving asset gets picked up.
     * <p>
     * The render caches key on the override paths they were handed, not on the identity of
     * the base instance they patched - that check had to go, because TACZ re-enters
     * {@code getGunDisplay} mid-build and the identity comparison misfired. But paths alone
     * can't see this ordering: texture bytes arrive, a patched copy is built from the plain
     * base and cached; geo-model bytes arrive later, so the mixin now has a geometry-patched
     * base to work from - yet the texture/icon/hud values are unchanged, the cache hits, and
     * the geometry override is dropped for the rest of the session. A counter catches that
     * without reintroducing any identity semantics: one int compare on the hit path, and
     * one rebuild per live key per asset arrival, which stops entirely once a session's
     * assets have settled.
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** Current asset-resolution generation. See {@link #GENERATION}. */
    public static int generation() {
        return GENERATION.get();
    }

    /** Stale/abandoned transfers get pruned lazily whenever a new one starts. */
    private static final long TRANSFER_TIMEOUT_MILLIS = 30_000;

    /** Ceiling on a single asset once inflated. Matches what the server is willing to send. */
    private static final int MAX_INFLATED_BYTES = ServerSkinAssetStore.MAX_ASSET_BYTES;

    /** How long to wait for any reply before assuming the request was lost and re-sending. */
    private static final long INITIAL_RETRY_DELAY_MILLIS = 15_000;

    /** Ceiling on the exponential backoff between re-sends. */
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;

    /**
     * The only condition that marks a key MISSING through inaction, deliberately.
     * <p>
     * There used to be an attempt cap alongside this, and it was wrong. Retries are driven
     * from the render path, so the deadline expiring means "no frame rendered recently", not
     * "the server failed to answer" - and multi-second stalls are ordinary in a heavy modpack
     * (chunk loading, shader compilation, a GC pause). Three such hitches over a session were
     * enough to permanently blank a skin the server had been perfectly willing to serve, with
     * nothing logged to connect cause to effect. A lost request is nearly impossible on TCP,
     * and a genuinely absent asset gets an explicit {@code SkinAssetMissingPayload}, so a
     * generous absolute deadline is the honest bound here.
     */
    private static final long GIVE_UP_AFTER_MILLIS = 5 * 60_000L;

    private ClientSkinAssetCache() {
    }

    // ------------------------------------------------------------------
    // Read side - called from SkinAssetResolver, potentially every frame
    // ------------------------------------------------------------------

    /** @return whether {@code location}'s texture bytes have arrived and been registered. */
    public static boolean checkOrRequestTexture(ResourceLocation location) {
        return checkOrRequest(location.toString(), Kind.TEXTURE, location);
    }

    /**
     * @param physical the real asset path, used as both the request key and the
     *                 presence-cache key
     * @param collapsedIdentity the namespace:path key TACZ's model registry looks
     *                          the parsed model up by (see {@link TaczGeoModelInjector})
     * @return whether the geo-model has arrived and been injected
     */
    public static boolean checkOrRequestGeoModel(ResourceLocation physical, ResourceLocation collapsedIdentity) {
        return checkOrRequest(physical.toString(), Kind.GEO_MODEL, collapsedIdentity);
    }

    private static boolean checkOrRequest(String key, Kind kind, ResourceLocation target) {
        State state = STATE.get(key);
        if (state == State.PRESENT) return true;
        if (state == State.MISSING) return false;

        // This runs from the render path, which can tick with no live server connection -
        // most commonly the frame or two between a disconnect and the title screen actually
        // taking over, where a held gun's ItemStack is still being rendered from stale state.
        // PacketDistributor.sendToServer() null-checks the connection internally and throws
        // rather than no-op'ing, so every call below this point (initial send AND the retry
        // in retryIfOverdue()) has to be unreachable until a connection actually exists.
        // Deliberately leaves STATE/PENDING_META untouched rather than recording a failed
        // attempt - there's no server to have failed to answer, so this shouldn't cost the
        // key one of its MAX_ATTEMPTS. Next frame retries for free once reconnected.
        if (Minecraft.getInstance().getConnection() == null) return false;

        long now = System.currentTimeMillis();
        if (state == null) {
            // putIfAbsent, not put - two render calls racing here shouldn't fire two requests.
            if (STATE.putIfAbsent(key, State.PENDING) == null) {
                PENDING_META.put(key, new PendingRequest(kind, target, now + INITIAL_RETRY_DELAY_MILLIS, 1, now));
                PacketDistributor.sendToServer(new RequestSkinAssetPayload(key));
            }
            return false;
        }

        // PENDING. Re-driven from the render path, which is the only thing that ticks here -
        // without this the first lost or throttled request left the key on PENDING forever
        // and the skin silently never loaded for the rest of the session.
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

    /**
     * The server has the asset but is pacing us. Push the deadline out and restart the
     * backoff - it answered, so this isn't the unresponsive case backing off is meant for.
     */
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
     * Whether a server reply is for a request this session no longer has outstanding.
     * <p>
     * Payload handlers are dispatched through {@code enqueueWork}, so a reply that was
     * already queued when the player disconnected runs <em>after</em> {@link #clearAll()} has
     * wiped the session. Acting on it wrote a MISSING verdict for a key nobody had asked
     * about - and MISSING is terminal, so the next server would never even request that
     * asset and the skin stayed blank with nothing logged. Only a key we currently have in
     * flight is allowed to be resolved by an incoming reply.
     */
    private static boolean isStaleArrival(String path) {
        return STATE.get(path) != State.PENDING;
    }

    /**
     * Moves a key off PENDING to its final state and bumps {@link #GENERATION}.
     * <p>
     * Every transition out of PENDING goes through here - a MISSING verdict changes what
     * {@code SkinAssetResolver} returns just as much as a PRESENT one does, so both have to
     * invalidate the downstream render caches.
     */
    private static void resolve(String key, State state) {
        STATE.put(key, state);
        PENDING_META.remove(key);
        GENERATION.incrementAndGet();
    }

    public static void onChunk(long transferId, String path, int index, int totalChunks, byte[] data) {
        // Chunks queued before a disconnect can land after the session was torn down. Drop
        // them rather than reassembling into a transfer nothing is waiting for.
        if (isStaleArrival(path)) return;

        // totalChunks arrives as an unbounded VAR_INT and sizes the array allocated below,
        // so it has to be bounded BEFORE it is used - otherwise one small packet declaring
        // Integer.MAX_VALUE chunks is enough to OOM the client outright.
        if (totalChunks <= 0 || totalChunks > ServerSkinAssetStore.MAX_CHUNKS
                || index < 0 || index >= totalChunks) {
            MCPSkins.LOGGER.warn("[MCPSkins] Dropping malformed skin asset chunk for '{}' ({}/{})", path, index, totalChunks);
            return;
        }

        pruneStaleTransfers();

        Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
                id -> new Transfer(path, totalChunks, new byte[totalChunks][], System.currentTimeMillis()));

        // The bounds check above validates index against THIS packet's totalChunks, but the
        // array was sized by whichever packet opened the transfer. A later chunk claiming a
        // larger totalChunks (or a different asset reusing the id) would index past the end,
        // so both have to agree with the transfer already in flight before we write.
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

    /**
     * Inflates a received payload, refusing to expand past {@link #MAX_INFLATED_BYTES}.
     * <p>
     * The bound matters: a hostile server can hand us a few KiB of deflate stream that
     * expands to gigabytes, and the previous unbounded {@link ByteArrayOutputStream} would
     * have grown to meet it. Pulling from an {@link InflaterInputStream} instead of pushing
     * into an {@link InflaterOutputStream} is what makes the limit enforceable - we stop
     * reading the moment the budget is gone, rather than discovering it afterwards.
     */
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
            // DeflaterOutputStream/InflaterInputStream only call end() on a deflater/inflater
            // they created themselves. We passed one in, so releasing the native zlib stream
            // is on us - otherwise every completed transfer leaks one.
            inflater.end();
        }
    }

    private static void finish(String path, byte[] rawBytes) {
        PendingRequest meta = PENDING_META.get(path);
        if (meta == null) {
            // PENDING with no metadata shouldn't happen. Leave the state alone rather than
            // forcing MISSING: the retry path will re-request and repair it, whereas a
            // MISSING verdict here would be terminal for a key we can't even describe.
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
                // DynamicTexture takes ownership on success, so the image is only ours to
                // close if construction failed - otherwise this native buffer just leaks.
                image.close();
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

    /** Drops all cached assets and closes every texture we registered, freeing GPU memory.
     *  Called on client resource reload ({@code ClientModEvents}) and on disconnect
     *  ({@code ClientNetworkEvents}). Must run on the client main thread - closing a
     *  {@link DynamicTexture} is a GL call. */
    public static void clearAll() {
        STATE.clear();
        PENDING_META.clear();
        TRANSFERS.clear();
        WARNED_DECODE_FAILURES.clear();

        // release() unregisters AND closes, which is what we want on both call sites.
        // The old code closed the textures itself and deliberately left the TextureManager
        // entries behind, on the theory that a leftover mapping was harmless because the
        // next register() for the same key overwrites it. That holds only for keys that come
        // back - across a session of server-hopping, every asset unique to a server visited
        // once stays mapped to a closed texture forever. Double-closing was the stated worry
        // and isn't one: DynamicTexture#close no-ops once its pixels are null, and
        // AbstractTexture#releaseId no-ops once the id is -1.
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : REGISTERED_TEXTURES.keySet()) {
            textureManager.release(location);
        }
        REGISTERED_TEXTURES.clear();

        // Everything downstream just went stale, so move the generation on. The render
        // caches are cleared alongside this, but bumping keeps the invariant honest: any
        // entry built before this point must not be trusted after it.
        GENERATION.incrementAndGet();
    }
}