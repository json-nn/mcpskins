# MCPSkins — Critical Bug Remediation Plan

> Status: approved. Phase 1 is ship-blocking; Phases 2 and 3 follow.

## Context

`mcpskins` is a NeoForge 1.21.1 addon for the MUKSC TACZ fork that adds a skin system to TACZ weapons. Skins are server-authoritative (an unlock set in a data attachment), and skin **assets** (textures, geo-models) live only on the server under `<gamedir>/mcpskins/` and are streamed to clients on demand over a custom chunked protocol.

A full read of the codebase turned up one outright **authorization bypass** that makes the entire unlock economy free, two **remote client-crash** vectors reachable from a single packet, and a set of threading/lifecycle defects that produce silently-wrong rendering and steady resource leaks. The mod is a public release, so untrusted clients and hostile servers are both in scope.

This plan fixes those, in priority order. It also cleans up per-frame waste in the render hot path and hardens the reflective TACZ interop, both of which you asked to include.

**Wire protocol changes are involved.** The registrar is `event.registrar("1.2.0")` with no `.optional()`, so version mismatches already refuse the connection — there is no mixed-version compatibility to preserve. Bump to `"1.3.0"` and `mod_version` to `1.0.6-neoforge-1.21.1`.

---

## Phase 1 — Ship-blocking

### 1.1 Authorization bypass: any client can equip any locked skin

`SkinAttachment.hasSkin` ([SkinAttachment.java:30](src/main/java/org/minechestplate/mcpskins/skin/SkinAttachment.java:30)) returns `true` for **any** id starting with `default:`, without ever consulting the unlock set. `ApplySkinPayload` ([ApplySkinPayload.java:53](src/main/java/org/minechestplate/mcpskins/network/ApplySkinPayload.java:53)) is the only gate on the equip path.

Confirmed exploit chain — client sends `ApplySkinPayload("default:m4a1_cobra")` while holding an m4a1:

1. `hasSkin` returns `true` on the prefix alone.
2. `SkinManager.getBaseGun("default:m4a1_cobra")` strips the prefix, matches the real entry in the loop at [SkinManager.java:102](src/main/java/org/minechestplate/mcpskins/skin/SkinManager.java:102), returns `"tacz:m4a1"` — so the gun-match check at `ApplySkinPayload.java:67` passes.
3. `TACZSkinHelper.applySkinComponent` computes `bare = "m4a1_cobra"`, which is neither blank nor equal to the base gun, so it writes `SKIN_ID = "m4a1_cobra"` ([TACZSkinHelper.java:86](src/main/java/org/minechestplate/mcpskins/skin/TACZSkinHelper.java:86)).
4. `isStock` is false, so the player even gets the gold "Skinned by \<name\>" lore.

`SKIN_ID` is a `persistent` component, so the stolen skin survives restarts.

**Fix — remove prefix-based authorization entirely** (your chosen option):

- `ApplySkinPayload` becomes `record ApplySkinPayload(String skinId, boolean unequip)`. Replace the hand-rolled `readUtf()` codec with `StreamCodec.composite(ByteBufCodecs.stringUtf8(256), …, ByteBufCodecs.BOOL, …)` — matching the bounded style already used by [RequestSkinAssetPayload.java:33](src/main/java/org/minechestplate/mcpskins/network/asset/RequestSkinAssetPayload.java:33).
- `SkinAttachment.hasSkin` drops the `default:` branch and becomes a pure set lookup. This is the **authorization** predicate.
- Add `SkinAttachment.isOwnedOrDefault(player, skinId)` for **display** use — returns true for `default:` entries so the UI keeps showing stock as unlocked. Audit and repoint every existing caller: `SkinUnlockItem.use`/`fuse`, `SkinArmoryScreen`, `TACZRefitSkinOverlay`. Getting this split wrong is the main regression risk — stock skins showing as locked in both UIs.
- `ApplySkinPayload.handleData`:
  - `unequip == true` → clear `SKIN_ID`, strip owner lore, no ownership check. No skin id is trusted.
  - `unequip == false` → require `SkinManager.findSkin(skinId) != null` (rejects unknown ids), **and** `hasSkin(...) || hasPermissions(equipBypassPermissionLevel())`, **and** the existing base-gun match. Reject any id containing `:` in the skin position that isn't a registered skin.
- Update the two client senders (`TACZRefitSkinOverlay` equip path, `SkinArmoryScreen` equip path) to set `unequip` from `isDefaultSkin(entry)` instead of relying on the `default:` string reaching the server.

### 1.2 Remote client crash from a single chunk packet

[ClientSkinAssetCache.java:109-111](src/main/java/org/minechestplate/mcpskins/client/render/ClientSkinAssetCache.java:109):

```java
Transfer transfer = TRANSFERS.computeIfAbsent(transferId,
        id -> new Transfer(path, totalChunks, new byte[totalChunks][], System.currentTimeMillis()));
transfer.parts()[index] = data;
```

Two separate defects:

- **OOM:** `totalChunks` is an unbounded `VAR_INT` ([SkinAssetChunkPayload.java:32](src/main/java/org/minechestplate/mcpskins/network/asset/SkinAssetChunkPayload.java:32)) fed straight into an array allocation. One 6-byte packet declaring `2^31-1` chunks allocates ~16 GiB.
- **AIOOBE on the client main thread:** `index` is validated at line 102 against *this packet's* `totalChunks`, but written into an array sized by the **first** packet that created the transfer. `(id=1, total=2, index=0)` followed by `(id=1, total=100, index=50)` passes validation and indexes `[50]` on a length-2 array.

**Fix:**

- Add `public static final int MAX_CHUNKS = 64;` to `ServerSkinAssetStore` next to `CHUNK_SIZE` — 64 × 256 KiB = **16 MiB** max compressed asset, per your choice.
- `onChunk` rejects `totalChunks > MAX_CHUNKS` before allocating.
- After `computeIfAbsent`, reject the packet if `transfer.totalChunks() != totalChunks || !transfer.path().equals(path)`. This closes the AIOOBE and the transfer-id-collision case where two different assets share a slot.
- `sendChunks` ([ServerSkinAssetStore.java:262](src/main/java/org/minechestplate/mcpskins/network/asset/ServerSkinAssetStore.java:262)) refuses payloads over `MAX_CHUNKS`, logs the offending key, and sends `SkinAssetMissingPayload` instead. Also warn at scan time so pack authors find out at reload, not at render.
- `decompress` ([ClientSkinAssetCache.java:150](src/main/java/org/minechestplate/mcpskins/client/render/ClientSkinAssetCache.java:150)) inflates into an unbounded `ByteArrayOutputStream` — a zip bomb from a hostile server. Replace with a bounded read loop that aborts past 16 MiB uncompressed.

### 1.3 Client OOM from `SyncUnlocksPayload`

[SyncUnlocksPayload.java:32-39](src/main/java/org/minechestplate/mcpskins/network/SyncUnlocksPayload.java:32) pre-allocates `new ArrayList<>(size)` from an unvalidated varint **before reading a single element** — a 5-byte packet OOMs the client. Same class of unbounded nested `readVarInt` loops in `SyncRegistryPayload.readMap`.

**Fix:** drop the pre-sizing, cap element counts (8192 unlocks; 4096 weapons × 256 skins), and bound every `readUtf()` to 256. On `SyncRegistryPayload`'s **write** side, log an error if the serialized registry approaches the ~1 MiB clientbound cap — today an oversized skin datapack silently fails the packet with no chunking fallback.

### 1.4 `ZipFile` close/read race + uncaught exception on the netty thread

`readBytes` runs on a netty thread (`HandlerThread.NETWORK`, [MCPSkins.java:139](src/main/java/org/minechestplate/mcpskins/MCPSkins.java:139)) while `closeOpenZips` runs on the game executor from `reload` ([ServerSkinAssetStore.java:96](src/main/java/org/minechestplate/mcpskins/network/asset/ServerSkinAssetStore.java:96)). `zip.close()` is **not** taken under the per-handle `synchronized (zip)` that guards reads.

- A `/reload` concurrent with an in-flight request makes `zip.getEntry(...)` throw `IllegalStateException("zip file closed")`. The catch at line 223 is `IOException` only, so it escapes `handleRequest` into the NeoForge payload handler.
- A netty thread reaching `computeIfAbsent` *after* `openZips.clear()` reopens the zip into the cleared map — leaked handle, and it serves pre-reload bytes, exactly what the javadoc at line 238 claims to prevent.

**Fix:** guard `openZips` with a `ReentrantReadWriteLock` — `readBytes` takes the read lock around lookup-and-read, `closeOpenZips` takes the write lock around close-and-clear. Keep the per-handle `synchronized` for `ZipFile`'s own concurrency contract. Wrap the whole of `handleRequest` in `try/catch (RuntimeException)` so nothing ever escapes onto a netty thread.

### 1.5 DoS: rate limit counts requests, not bytes

`MAX_REQUESTS_PER_SECOND = 200` ([ServerSkinAssetStore.java:48](src/main/java/org/minechestplate/mcpskins/network/asset/ServerSkinAssetStore.java:48)) with no byte budget. After the first miss the asset is in `hotCache`, so a modified client can re-request the largest asset 200×/sec at zero I/O cost — a 4 MiB asset yields ~800 MiB/sec of allocation and unbacked-pressured outbound buffering **per attacking player**. `sendChunks` makes it worse by materializing a full second copy in the `chunks` list before sending a byte.

**Fix** (you chose to keep asset access open and cap throughput):

- `RateState` gains an `AtomicLong bytes`, reset with the same second-window logic. Add `MAX_BYTES_PER_SECOND` (2 MiB) and charge `compressed.length` before sending.
- `sendChunks` sends each chunk as it is sliced — delete the intermediate `List`.
- See 2.3 for what a throttled client is told.

---

## Phase 2 — Near-term correctness

### 2.1 Client state survives disconnect

`ClientSkinAssetCache.clearAll()`'s javadoc says it runs "on disconnect" ([ClientSkinAssetCache.java:196](src/main/java/org/minechestplate/mcpskins/client/render/ClientSkinAssetCache.java:196)). It has exactly one caller — the resource-reload listener at [ClientModEvents.java:51](src/main/java/org/minechestplate/mcpskins/client/ClientModEvents.java:51). There is no disconnect listener in the repo.

Consequences: registered GL textures leak across server switches; `PRESENT`/`MISSING` verdicts and geo-models injected into TACZ's `dataMap` from server A stay in force on server B under the same `namespace:path` key — **server B renders server A's texture bytes**. `TACZRefitSkinOverlay`'s static `previewActive`/`previewOriginalSkinId` also persist, so a disconnect mid-preview leaves the flags set into the next session.

**Fix:** new `client/ClientNetworkEvents.java`, `@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)` on the game bus, handling `ClientPlayerNetworkEvent.LoggingOut` → `ClientSkinAssetCache.clearAll()`, `SkinAssetResolver.clearCache()`, `PatchedGunDisplayCache.clear()`, `GunModelPatcher.clear()`, `TaczGeoModelInjector.reset()` (2.6), and a new `TACZRefitSkinOverlay.resetSessionState()`. Correct the stale javadoc.

### 2.2 Late-arriving geo-model is silently dropped for the session

Your uncommitted diff removed the `existing.original() == base` check from `PatchedGunDisplayCache` to fix the state-machine re-entrancy race — correct, but it also removed the only signal that the base changed. Because assets arrive asynchronously, this ordering is reachable and permanent:

1. Texture bytes arrive → mixin builds a patched copy from the **plain** base and caches it.
2. Geo-model bytes arrive later → `patchBase` becomes the geo instance.
3. `getOrCreate` matches on texture/icon/hud alone ([PatchedGunDisplayCache.java:48-55](src/main/java/org/minechestplate/mcpskins/client/render/PatchedGunDisplayCache.java:48)), returns the stale entry — **the geometry override never applies** until F3+T.

**Fix — asset generation counter** (your choice; avoids reintroducing identity semantics):

- `ClientSkinAssetCache` gains `private static final AtomicInteger GENERATION` plus `public static int generation()`. Bump it in `finish()` and `onMissing()` — the two places a resolution outcome can change.
- `PatchedGunDisplayCache.CacheEntry` and `GunModelPatcher.CacheEntry` each store the generation they were built at. A cache hit requires field equality **and** `entry.generation() == ClientSkinAssetCache.generation()`.
- Cost is one `int` compare per frame on the hit path, and one rebuild per live key per asset arrival. Generation bumps stop entirely once a session's assets have settled.

### 2.3 Requests stuck on `PENDING` forever

When the rate limiter trips, `handleRequest` returns silently ([ServerSkinAssetStore.java:168](src/main/java/org/minechestplate/mcpskins/network/asset/ServerSkinAssetStore.java:168)). The client's `checkOrRequest` has no timeout and no retry ([ClientSkinAssetCache.java:79-90](src/main/java/org/minechestplate/mcpskins/client/render/ClientSkinAssetCache.java:79)), so that asset silently never loads for the rest of the session. This is the one path where the state machine has a dead end — every other outcome gets a reply.

**Fix — make both halves total** (your choice):

- New S2C `SkinAssetThrottledPayload(String path, int retryAfterMillis)`. The server sends it instead of dropping.
- Client stores a `retryAtMillis` alongside `PENDING`. `checkOrRequest` re-fires once the deadline passes; a self-imposed client timeout (15 s) covers genuinely lost packets. Cap at 3 attempts, then `MISSING`, so a throttled client can't retry-storm.

### 2.4 Reload clears render caches from the wrong thread

[ClientModEvents.java:41-46](src/main/java/org/minechestplate/mcpskins/client/ClientModEvents.java:41) runs `SkinAssetResolver.clearCache()`, `PatchedGunDisplayCache.clear()`, `GunModelPatcher.clear()` and `RefitButtonPositionScreen.clearBackgroundCache()` on the **background executor**, concurrently with the render thread calling `getOrCreate`. This is precisely the cross-thread `CACHE.get`/`put` interleaving that `GunModelPatcher`'s `ThreadLocal BUILDING` guard cannot cover — and `RefitButtonPositionScreen`'s `backgroundWidth`/`backgroundHeight` are plain non-volatile statics written from that thread and read from the render thread.

**Fix:** move all four `clear()` calls into the existing `thenRunAsync(..., gameExecutor)` block next to `clearAll()`. Nothing there is expensive enough to justify the background hop.

### 2.5 `SkinManager.registry` is a plain `HashMap` shared across threads

[SkinManager.java:34](src/main/java/org/minechestplate/mcpskins/skin/SkinManager.java:34) is mutated by `apply()` (reload thread) and `syncFromNetwork()` (client main), and read from the render thread and packet handlers. `apply()` does `clear()`-then-repopulate, so torn reads are possible. Worse, `getRegistry()` returns the **live mutable map**, which `SyncRegistryPayload.createFromServer()` hands straight into a payload record — serialized on the netty encode path while `/reload` can be clearing it. That's a `ConcurrentModificationException` waiting to happen.

**Fix:** make `registry` a `volatile Map` replaced wholesale with an immutable `Map.copyOf(...)` on each load; `getRegistry()` returns it directly (now safely shared). Build two derived lookup indices at the same time — `skinId → SkinLookupResult` and `skinId → baseGun` — which also kills the linear scans in 3.3.

### 2.6 Resource leaks

- `compress` ([ServerSkinAssetStore.java:253](src/main/java/org/minechestplate/mcpskins/network/asset/ServerSkinAssetStore.java:253)) passes an explicit `Deflater` to `DeflaterOutputStream`, so `close()` never calls `Deflater.end()` — one leaked native zlib stream **per cache miss**. Identical bug with `Inflater` at `ClientSkinAssetCache.java:152`, leaking **per completed transfer** (attacker-triggerable). Wrap both in `try/finally { end(); }`.
- `registerTexture` ([ClientSkinAssetCache.java:174](src/main/java/org/minechestplate/mcpskins/client/render/ClientSkinAssetCache.java:174)): if `new DynamicTexture(image)` throws, the `NativeImage` leaks native memory. Close it on the failure path only — ownership passes to `DynamicTexture` on success.
- Leaked `ZipFile` handle — covered by 1.4.

---

## Phase 3 — Hot-path waste and TACZ interop

### 3.1 Per-frame reflection on unrecognized geo-models

`GunModelPatcher.getOrCreate` filters `modelOverride` to null when unrecognized and stores a `CacheEntry(null, null, null, null)` ([GunModelPatcher.java:159-170](src/main/java/org/minechestplate/mcpskins/client/render/GunModelPatcher.java:159)) — but the hit-check at 143-149 compares the **unfiltered** incoming overrides, so that entry can never match. `isModelRecognized` → `Method.invoke` into TACZ internals runs **every render call, forever**.

**Fix:** key the negative entry on the original incoming overrides, not the filtered ones.

### 3.2 `computeIfAbsent` with a null-returning mapper

`ConcurrentHashMap.computeIfAbsent` stores nothing when the mapper returns null, so `SkinAssetResolver`'s invalid-path branches ([SkinAssetResolver.java:65-70](src/main/java/org/minechestplate/mcpskins/client/render/SkinAssetResolver.java:65) and 127-130) re-run `buildModelPaths`/`buildCandidate` — string ops plus two `ResourceLocation.tryBuild` — every frame. `WARNED_INVALID` silences the log but not the work.

**Fix:** memoize a sentinel value for "known invalid" instead of null.

### 3.3 Linear registry scans in render paths

`getBaseGun`, `findSkin`, and `getAllSkinIds` are O(all skins) and are called per frame and per packet; `ClientModEvents.registerItemColors` ([ClientModEvents.java:66](src/main/java/org/minechestplate/mcpskins/client/ClientModEvents.java:66)) walks the whole registry per tint query. Repoint all of them at the indices from 2.5.

Also: `registerItemColors` still uses `data.copyTag().getString("SkinToUnlock")` — the same full-compound deep copy your diff just removed from `TACZSkinHelper.getGunId`. Same pattern remains in `SkinUnlockItem` and `SkinCommand`; apply `getUnsafe()` consistently to the read-only peeks.

### 3.4 `SkinArmoryScreen` layout thrash and a sticky `false`

- `computeHeaderLayout()` runs font measurement for 5 pills and is invoked 5+ times per frame (`computeLayout`, `statusPillRect`, `customModelToggleRect`, `sortButtonRect`), plus once per input event. Cache it in a field, invalidated on width/filter change.
- `hasCustomModel` ([SkinArmoryScreen.java:993](src/main/java/org/minechestplate/mcpskins/client/gui/SkinArmoryScreen.java:993)) caches its result for the screen's whole lifetime, including a `false` recorded while the geo asset is still in flight — the badge and the `customModelOnly` filter then stay wrong for that session. Only cache once `ClientSkinAssetCache` reports a terminal state; add an `isResolved(key)` accessor for this.

### 3.5 Fragile TACZ interop

- `TaczGeoModelInjector` holds a permanent `volatile Map` handle to TACZ's internal `dataMap`, captured once in `discover()` and never re-validated. If TACZ replaces (rather than clears) that map on a resource reload, every injection lands in an orphaned map and geo skins silently stop working until restart — `supportState == 1` blocks rediscovery. Wire the existing dead `resetForTests` up as a real `reset()`, called from `clearAll()` and disconnect, so `discover()` re-runs.
- `GunDisplayInstancePatcher.shallowCopy` walks only `GunDisplayInstance.getDeclaredFields()` — superclass fields stay at their `allocateInstance` defaults. `findField` in the same class *does* walk the superclass chain, so the author already considered inheritance possible. Make `shallowCopy` walk it too.
- `getUnsafe()` re-does the `theUnsafe` reflective lookup on every call in both patcher classes. Memoize into a `static final`.
- Broad swallows worth tightening: `catch (Throwable)` at `GunModelPatcher.java:548` (also catches OOM/StackOverflow), silent `return null` at `GunModelPatcher.java:570`, the fail-open `return true` at `GunModelPatcher.java:320`, and `catch (Exception ignored)` at `SkinArmoryScreen.java:1009`.

---

## Files touched

| Area | Files |
|---|---|
| Auth bypass | `network/ApplySkinPayload.java`, `skin/SkinAttachment.java`, `client/TACZRefitSkinOverlay.java`, `client/gui/SkinArmoryScreen.java`, `item/SkinUnlockItem.java` |
| Packet hardening | `network/asset/SkinAssetChunkPayload.java`, `network/SyncUnlocksPayload.java`, `network/SyncRegistryPayload.java`, new `network/asset/SkinAssetThrottledPayload.java` |
| Server store | `network/asset/ServerSkinAssetStore.java` |
| Client asset cache | `client/render/ClientSkinAssetCache.java` |
| Render caches | `client/render/PatchedGunDisplayCache.java`, `client/render/GunModelPatcher.java`, `client/render/SkinAssetResolver.java`, `client/render/GunDisplayInstancePatcher.java`, `client/render/TaczGeoModelInjector.java` |
| Lifecycle | `client/ClientModEvents.java`, new `client/ClientNetworkEvents.java`, `MCPSkins.java` |
| Registry | `skin/SkinManager.java` |
| Version | `gradle.properties`, `MCPSkins.java` (registrar `1.3.0`) |

## Verification

**Build:** `./gradlew build` — must stay clean; the mixin config is `"required": true` with `defaultRequire: 1`, so any TACZ signature drift fails at load rather than degrading.

**Auth bypass (the important one).** Start `runServer` + `runClient`, join with an account owning **no** skins.
1. Hold an m4a1, open the refit overlay, confirm locked skins show locked and equip is refused.
2. Hand-send `ApplySkinPayload("default:<a real locked skin id>", false)` from a scratch client build (or a debugger breakpoint on the send path). **Before:** skin applies with owner lore. **After:** rejected, no component written.
3. Confirm unequip still works from both UIs, and that stock/`default:` entries still render as *unlocked* in the armory and carousel — this is the regression 1.1 most likely introduces.

**Chunk hardening.** Point a client at a patched server that sends `totalChunks = Integer.MAX_VALUE`, then one that sends mismatched `totalChunks` for the same `transferId`. Both must log a dropped-packet warning and leave the client running. Drop a >16 MiB asset in `mcpskins/` and confirm the server refuses it with a clear log line at scan time.

**Late geo-model (2.2).** Add a temporary delay to the geo-model branch of `ServerSkinAssetStore.handleRequest` so the texture always wins the race. Equip a geo-model skin. Before the fix the weapon keeps base geometry until F3+T; after, it corrects itself within a frame of the model arriving.

**Disconnect (2.1).** Join server A with skinned weapons, disconnect, join server B whose pack has a *different* texture at the same asset key. B must show its own texture. Watch GPU memory across several A→B→A cycles — it should not climb.

**Throttling (2.3).** Set `MAX_BYTES_PER_SECOND` very low, join, and confirm assets still resolve (throttled → retried → loaded) rather than sticking on a base skin forever.

**Reload races (1.4, 2.4, 2.5).** Run `/reload` repeatedly on the server while a client is actively rendering skinned weapons and requesting assets. No `IllegalStateException` on netty threads, no `ConcurrentModificationException` during registry sync, no missing textures afterward. Run the client with `-ea` and F3+T spam during rendering for 2.4.

**Leaks (2.6).** Request several hundred distinct assets, then check native memory (`-XX:NativeMemoryTracking=summary`) on both sides — it should be flat, not growing per transfer.

**Hot path (Phase 3).** Profile the client with a geo-model skin equipped whose model file is intentionally missing. Before the fix `isModelRecognized` shows up per frame; after, it should not appear at all.
