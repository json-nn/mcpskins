package org.minechestplate.mcpskins.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.client.pack.ClientSkinResourcePack;
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

    private enum Kind { TEXTURE, COMPANION, GEO_MODEL }

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
    private static final Set<ResourceLocation> REGISTERED_TEXTURES = ConcurrentHashMap.newKeySet();

    /**
     * Registering before the blocking companions land would show an animated skin as an
     * un-sliced strip, or let a shader cache a missing PBR map for the rest of the session.
     */
    private static final class Assembly {
        byte[] texture;
        final Map<String, byte[]> companions = new ConcurrentHashMap<>();
        final Set<String> settled = ConcurrentHashMap.newKeySet();

        boolean readyToBuild() {
            if (texture == null) return false;
            for (TextureCompanion companion : TextureCompanion.ALL) {
                if (companion.blocking() && companion.isWanted() && !settled.contains(companion.id())) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Map<String, Assembly> ASSEMBLIES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_DECODE_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * Bumped whenever a key leaves PENDING. The render caches store the generation they were
     * built at and rebuild when it moves, so a late asset still gets picked up.
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
     * Retries are driven from the render path, so an expired deadline means "no frames lately",
     * not "no answer": an attempt cap here would blank skins after an ordinary client stall.
     */
    private static final long GIVE_UP_AFTER_MILLIS = 5 * 60_000L;

    private ClientSkinAssetCache() {
    }


    public static boolean checkOrRequestTexture(ResourceLocation location) {
        String key = location.toString();
        boolean present = checkOrRequest(key, Kind.TEXTURE, location);
        if (!present) {
            for (TextureCompanion companion : TextureCompanion.ALL) {
                if (!companion.isWanted()) continue;
                ResourceLocation path = companion.pathFor(location);
                if (path != null) {
                    checkOrRequest(path.toString(), Kind.COMPANION, path);
                }
            }
        }
        return present;
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

        // Rendering outlives a disconnect by a frame or two, and sendToServer throws on a null
        // connection. State is left untouched, so the next frame retries for free.
        if (Minecraft.getInstance().getConnection() == null) return false;

        long now = System.currentTimeMillis();
        if (state == null) {
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
        Assembly held = ASSEMBLIES.get(key);
        if (held != null && held.texture != null) return;

        if (now - meta.firstSentAtMillis() > GIVE_UP_AFTER_MILLIS) {
            if (PENDING_META.remove(key, meta)) {
                STATE.put(key, State.MISSING);
                GENERATION.incrementAndGet();
                MCPSkins.LOGGER.warn(
                        "[MCPSkins] Giving up on skin asset '{}' - no reply in {} ms across {} attempt(s).",
                        key, now - meta.firstSentAtMillis(), meta.attempts());
                // A companion nobody answered must still release its texture, or the skin
                // waits for something that is never coming.
                settleCompanion(key, null);
            }
            return;
        }

        // CAS, so concurrent render calls can't turn one overdue request into a burst.
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


    public static void onMissing(String path) {
        if (isStaleArrival(path)) return;
        if (settleCompanion(path, null)) return;
        resolve(path, State.MISSING);
    }

    /**
     * Replies run through {@code enqueueWork}, so one queued at disconnect lands after
     * {@link #clearAll()} and would write a terminal MISSING for a key nobody asked about.
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

        // index was checked against this packet's totalChunks; the array was sized by the one
        // that opened the transfer. Both must agree before writing.
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

        switch (meta.kind()) {
            case TEXTURE -> {
                assembly(path).texture = rawBytes;
                tryBuild(path, meta.target());
            }
            case COMPANION -> settleCompanion(path, rawBytes);
            case GEO_MODEL -> resolve(path, TaczGeoModelInjector.inject(meta.target(), rawBytes)
                    ? State.PRESENT : State.MISSING);
        }
    }

    private static Assembly assembly(String texturePath) {
        return ASSEMBLIES.computeIfAbsent(texturePath, key -> new Assembly());
    }

    /**
     * Records a companion's outcome, present or absent, and lets its texture proceed.
     *
     * @return true if {@code path} was a companion at all
     */
    private static boolean settleCompanion(String path, byte[] bytes) {
        TextureCompanion companion = TextureCompanion.matching(path);
        if (companion == null) return false;

        String texturePath = companion.textureOf(path);
        Assembly assembly = assembly(texturePath);
        assembly.settled.add(companion.id());
        if (bytes != null) {
            assembly.companions.put(companion.id(), bytes);
        }
        resolve(path, bytes == null ? State.MISSING : State.PRESENT);

        PendingRequest textureMeta = PENDING_META.get(texturePath);
        if (textureMeta != null) {
            tryBuild(texturePath, textureMeta.target());
        }
        return true;
    }

    /** Builds and registers the texture once nothing blocking is still outstanding. */
    private static void tryBuild(String texturePath, ResourceLocation location) {
        Assembly assembly = ASSEMBLIES.get(texturePath);
        if (assembly == null || !assembly.readyToBuild()) return;
        ASSEMBLIES.remove(texturePath);
        resolve(texturePath, register(location, assembly) ? State.PRESENT : State.MISSING);
    }

    /**
     * Through the streamed pack rather than straight to the GPU: a shader looks the PBR maps up
     * by resource path when the texture first binds.
     */
    private static boolean register(ResourceLocation location, Assembly assembly) {
        SkinTextureAnimator.forget(location);
        try {
            ClientSkinResourcePack.put(location, assembly.texture);
            for (TextureCompanion companion : TextureCompanion.ALL) {
                byte[] bytes = assembly.companions.get(companion.id());
                ResourceLocation path = companion.pathFor(location);
                if (bytes != null && path != null) {
                    ClientSkinResourcePack.put(path, bytes);
                }
            }

            // A plain SimpleTexture on purpose: a shader mod attaches its PBR maps to this
            // exact class, and a subclass measurably stopped it looking them up at all.
            SkinAnimationMeta animation = parseAnimation(location, assembly);
            Minecraft.getInstance().getTextureManager().register(location, new SimpleTexture(location));
            REGISTERED_TEXTURES.add(location);
            SkinTextureAnimator.register(location, assembly.texture, animation);

            if (animation != null) {
                MCPSkins.LOGGER.info("[MCPSkins] Animated skin texture '{}': {} frame(s), {}x{}{}.",
                        location, animation.frames().size(), animation.frameWidth(),
                        animation.frameHeight(), animation.interpolate() ? ", interpolated" : "");
            }
            for (TextureCompanion companion : TextureCompanion.ALL) {
                if (assembly.companions.containsKey(companion.id()) && !"animation".equals(companion.id())) {
                    MCPSkins.LOGGER.info("[MCPSkins] Companion '{}' for '{}' is readable by the resource manager.",
                            companion.id(), location);
                }
            }
            return true;
        } catch (RuntimeException e) {
            if (WARNED_DECODE_FAILURES.add(location.toString())) {
                MCPSkins.LOGGER.warn("[MCPSkins] Failed to register network-delivered texture '{}'", location, e);
            }
            return false;
        }
    }

    private static SkinAnimationMeta parseAnimation(ResourceLocation location, Assembly assembly) {
        byte[] mcmeta = assembly.companions.get("animation");
        if (mcmeta == null) return null;
        try (InputStream in = new ByteArrayInputStream(assembly.texture)) {
            NativeImage image = NativeImage.read(in);
            try {
                return SkinAnimationMeta.parse(mcmeta, image.getWidth(), image.getHeight(), location.toString());
            } finally {
                image.close();
            }
        } catch (IOException | RuntimeException e) {
            MCPSkins.LOGGER.warn("[MCPSkins] Could not read '{}' to size its animation; it stays static.",
                    location, e);
            return null;
        }
    }


    /** Client main thread only. Called on resource reload and on disconnect. */
    public static void clearAll() {
        STATE.clear();
        PENDING_META.clear();
        TRANSFERS.clear();
        WARNED_DECODE_FAILURES.clear();
        ASSEMBLIES.clear();
        SkinTextureAnimator.clear();
        ClientSkinResourcePack.clear();

        // release() unregisters and closes; leaving them behind stranded one dead texture
        // per asset of every server visited.
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation location : REGISTERED_TEXTURES) {
            textureManager.release(location);
        }
        REGISTERED_TEXTURES.clear();

        GENERATION.incrementAndGet();
    }
}
