package org.minechestplate.mcpskins.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.minechestplate.mcpskins.MCPSkins;
import org.minechestplate.mcpskins.network.asset.RequestSkinAssetPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

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

    private record PendingRequest(Kind kind, ResourceLocation target) {
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

    /** Stale/abandoned transfers get pruned lazily whenever a new one starts. */
    private static final long TRANSFER_TIMEOUT_MILLIS = 30_000;

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
        if (state == null) {
            // putIfAbsent, not put - two render calls racing here shouldn't fire two requests.
            if (STATE.putIfAbsent(key, State.PENDING) == null) {
                PENDING_META.put(key, new PendingRequest(kind, target));
                PacketDistributor.sendToServer(new RequestSkinAssetPayload(key));
            }
        }
        return false; // PENDING or MISSING - not usable either way
    }

    // ------------------------------------------------------------------
    // Write side - called by the network payload handlers
    // ------------------------------------------------------------------

    public static void onMissing(String path) {
        STATE.put(path, State.MISSING);
        PENDING_META.remove(path);
    }

    public static void onChunk(long transferId, String path, int index, int totalChunks, byte[] data) {
        if (totalChunks <= 0 || index < 0 || index >= totalChunks) {
            MCPSkins.LOGGER.warn("[MCPSkins] Dropping malformed skin asset chunk for '{}' ({}/{})", path, index, totalChunks);
            return;
        }

        pruneStaleTransfers();

        Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
                id -> new Transfer(path, totalChunks, new byte[totalChunks][], System.currentTimeMillis()));
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
            STATE.put(path, State.MISSING);
            PENDING_META.remove(path);
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

    private static byte[] decompress(byte[] compressed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 2));
        try (InflaterOutputStream inflate = new InflaterOutputStream(out, new Inflater())) {
            inflate.write(compressed);
        }
        return out.toByteArray();
    }

    private static void finish(String path, byte[] rawBytes) {
        PendingRequest meta = PENDING_META.remove(path);
        if (meta == null) {
            // Shouldn't happen, but don't leave it stuck on PENDING if it somehow does.
            STATE.put(path, State.MISSING);
            return;
        }

        boolean ok = switch (meta.kind()) {
            case TEXTURE -> registerTexture(meta.target(), rawBytes);
            case GEO_MODEL -> TaczGeoModelInjector.inject(meta.target(), rawBytes);
        };

        STATE.put(path, ok ? State.PRESENT : State.MISSING);
    }

    private static boolean registerTexture(ResourceLocation location, byte[] pngBytes) {
        try (InputStream in = new ByteArrayInputStream(pngBytes)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(image);
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
     *  Called on client resource reload and on disconnect. */
    public static void clearAll() {
        STATE.clear();
        PENDING_META.clear();
        TRANSFERS.clear();
        WARNED_DECODE_FAILURES.clear();

        // Not calling TextureManager#release() here too - unsure if it also closes on this
        // MC version, and double-closing a GL texture is a nasty bug to chase down later.
        // Closing our own DynamicTexture is enough; the leftover TextureManager entry is
        // harmless and gets overwritten on the next register() for the same key.
        for (DynamicTexture texture : REGISTERED_TEXTURES.values()) {
            texture.close();
        }
        REGISTERED_TEXTURES.clear();
    }
}