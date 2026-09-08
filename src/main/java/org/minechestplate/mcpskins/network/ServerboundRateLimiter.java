package org.minechestplate.mcpskins.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-second cap for serverbound payload handlers. A modified client can send any
 * registered payload as fast as it likes, so a handler doing real work per packet needs one.
 */
public final class ServerboundRateLimiter {

    // [0] = window second, [1] = count in that window.
    private static final Map<UUID, long[]> WINDOWS = new ConcurrentHashMap<>();

    private ServerboundRateLimiter() {
    }

    public static boolean allow(UUID player, int maxPerSecond) {
        long nowSecond = System.currentTimeMillis() / 1000L;
        long[] window = WINDOWS.computeIfAbsent(player, key -> new long[]{nowSecond, 0});
        synchronized (window) {
            if (window[0] != nowSecond) {
                window[0] = nowSecond;
                window[1] = 0;
            }
            window[1]++;
            return window[1] <= maxPerSecond;
        }
    }

    public static void forget(UUID player) {
        WINDOWS.remove(player);
    }
}
