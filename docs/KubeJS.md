# Scripting MCPSkins with KubeJS

MCPSkins ships an optional KubeJS integration. When KubeJS is installed you get a `MCPSkins`
binding for reading and changing skin ownership, and an `MCPSkinsEvents` group for reacting to
(and blocking) unlocks, equips and fusions. Without KubeJS nothing changes and none of this code
is even loaded.

Scripts go in `kubejs/server_scripts/`. Every event below is server side.

> **Registry timing.** Skin data only exists after a datapack load. Call the lookup methods from
> inside an event, not at the top level of a script, or you will read an empty registry.

## Unavailable skins

A skin pack can declare skins for a gun you do not have installed. Those skins are **hidden**:
they are left out of the registry sent to clients, the Armory, the refit carousel, fusion roll
pools, `/mcpskins give all`, command suggestions, and every lookup method on this page. Explicit
grants refuse them, and `MCPSkins.unlock` returns `UNAVAILABLE`.

Ownership is never touched by this. A player who already owns such a skin keeps it, silently, and
it reappears exactly as it was once the gunpack is installed again.

Server owners debugging a pack can set `hideUnavailableSkins = false` under `[content]` in the
server config to see everything a pack declares, missing textures included.

## The `MCPSkins` binding

### Ownership

| Method | Returns | Notes |
|---|---|---|
| `MCPSkins.has(player, skinId)` | boolean | Stock `default:` entries are never owned |
| `MCPSkins.unlock(player, skinId)` | `UnlockResult` | Fires the unlock events |
| `MCPSkins.revoke(player, skinId)` | boolean | True if the player had it |
| `MCPSkins.getUnlocked(player)` | list of string | Every id the player owns |

`unlock` returns one of `UNLOCKED`, `ALREADY_OWNED`, `UNKNOWN_SKIN`, `UNAVAILABLE` or
`CANCELLED`. Test it with `.succeeded()`, or compare `.name()` against one of those strings.
`unlock` and `revoke` need a real server player; calling them off the server thread throws.

`UNAVAILABLE` means the skin is defined by a pack, but TACZ has not loaded the gun or attachment
it paints, so granting it would hand out something the player could never see. See
[Unavailable skins](#unavailable-skins).

### Looking skins up

| Method | Returns |
|---|---|
| `MCPSkins.exists(skinId)` | boolean |
| `MCPSkins.getSkin(skinId)` | `SkinInfo`, or null |
| `MCPSkins.getAllSkinIds()` | list of string |
| `MCPSkins.getSkinsFor(baseId)` | list of `SkinInfo` |
| `MCPSkins.getSkinsByRarity(rarityId)` | list of `SkinInfo` |

A `SkinInfo` has `id`, `name`, `baseId`, `attachment`, `rarityId`, `collection`, `description`
and `isNew`. `baseId` is the TACZ gun or attachment the skin belongs to, such as `tacz:m4a1`.

> **`SkinInfo` and `CollectionProgress` are Java records**, so read them with a call, not a
> property: `skin.id()`, not `skin.id`. Event fields are normal getters, so `event.player` and
> `event.skinId` work as you would expect.

### Collections

| Method | Returns |
|---|---|
| `MCPSkins.getCollections()` | list of string |
| `MCPSkins.getSkinsInCollection(name)` | list of `SkinInfo` |
| `MCPSkins.getCollectionProgress(player, name)` | `CollectionProgress` |

`CollectionProgress` has `collection`, `owned`, `total` and `complete()`. A `total` of 0 means no
loaded pack declares that collection. Names are free text and are matched exactly.

### Item stacks

| Method | Returns |
|---|---|
| `MCPSkins.getSkinOn(stack)` | skin id, or null if the item is stock |
| `MCPSkins.applySkin(stack, skinIdOrNull)` | a new stack wearing that skin |
| `MCPSkins.createUnlockItem(skinId)` | the unlock item, or an empty stack |
| `MCPSkins.createSkinnedGun(gunId, skinId)` | a gun already wearing a skin |

`applySkin` is a pure stack operation: it does not check ownership, add owner lore, or fire
events. It is meant for loot, kits and shop rewards. Creator methods return an empty stack for an
unknown id, and `applySkin` returns the stack unchanged if it is not a skinnable TACZ item.

## Events

| Handler | Cancellable | Fires |
|---|---|---|
| `MCPSkinsEvents.unlock` | yes | Before one skin is granted |
| `MCPSkinsEvents.unlocked` | no | After a skin was granted, once per skin |
| `MCPSkinsEvents.revoked` | no | After a skin was taken away, once per skin |
| `MCPSkinsEvents.collectionCompleted` | no | When a player completes a collection |
| `MCPSkinsEvents.equip` | yes | Before a held item's skin changes |
| `MCPSkinsEvents.fuse` | yes | After the roll, before anything is spent |
| `MCPSkinsEvents.fused` | no | After a fuse completed |

### `unlock`

`player`, `skinId`, `source`, `skin`. `source` is `COMMAND`, `UNLOCK_ITEM` or `API`. It only fires
for single unlocks, and only once the skin is known to exist and to not already be owned. Bulk
grants (`/mcpskins give all`, starter skins) never reach it.

Cancelling leaves ownership untouched, makes the unlock report `CANCELLED`, does **not** consume
the unlock item, and makes the command report failure.

### `unlocked`

`player`, `skinId`, `source`, `skin`. `source` may also be `UNLOCK_ALL` or `DEFAULT_GRANT`.

### `revoked`

`player`, `skinId`, `source`, where `source` is `COMMAND` or `API`. Clearing a player's skins
fires one of these per skin they had.

### `collectionCompleted`

`player`, `collection`. Only the transition from incomplete to complete fires it, so later
unlocks inside a full collection stay quiet. A bulk grant is evaluated once after the whole
batch. Stock `default:` entries carry no collection and never count toward one.

### `equip`

`player`, `hand`, `baseId`, `previousSkinId`, `newSkinId`, `unequip`. `previousSkinId` is null
when the item was bare; `newSkinId` is null when the player is taking the skin off. Cancelling
leaves the item exactly as it was.

There is deliberately no way to change `newSkinId`: ownership is checked before this fires, and
letting a script swap the target afterwards would be a way around that check.

### `fuse`

`player`, `rarityId`, `heldSkinId`, `rolledSkinId`, `resultSkinId`, plus `setResultSkinId(id)`.
Fires after every check has passed and the result has been rolled, but before any item is
consumed. An override that names an unknown or stock skin is refused with a warning in the log
and the original roll stands. Cancelling consumes nothing and tells the player the fuse was
blocked.

### `fused`

`player`, `consumedSkinIds`, `resultSkinId`. `consumedSkinIds` has one entry per item spent, so
the same id can appear more than once.

## Examples

### Unlock a skin when a condition is met

```js
// Kill 50 zombies, get the skin.
EntityEvents.death('minecraft:zombie', event => {
  const player = event.source.player
  if (!player || !player.isPlayer()) return

  const kills = (player.persistentData.getInt('zombieKills') || 0) + 1
  player.persistentData.putInt('zombieKills', kills)

  if (kills === 50 && MCPSkins.unlock(player, 'm4a1_cobra').succeeded()) {
    player.tell(Text.gold('50 zombies down. The Cobra skin is yours.'))
  }
})
```

### Reward a completed collection

```js
MCPSkinsEvents.collectionCompleted(event => {
  if (event.collection !== 'Snake Pack') return

  event.player.tell(Text.green(`Collection complete: ${event.collection}`))
  event.player.give('minecraft:diamond_block')
})
```

### A pity rule on fusing

```js
// Every 10th fuse is guaranteed to be the skin the player is missing.
MCPSkinsEvents.fuse(event => {
  const player = event.player
  const count = (player.persistentData.getInt('fuseCount') || 0) + 1
  player.persistentData.putInt('fuseCount', count)

  if (count % 10 !== 0) return

  const wanted = MCPSkins.getSkinsByRarity('legendary')
    .filter(skin => !MCPSkins.has(player, skin.id()))

  if (wanted.length > 0) {
    event.setResultSkinId(wanted[0].id())
    player.tell(Text.gold('Pity bonus: a skin you were missing.'))
  }
})
```

### Block equipping under a condition

```js
// No skins in the arena.
MCPSkinsEvents.equip(event => {
  if (event.unequip) return

  if (event.player.stages.has('in_arena')) {
    event.player.tell(Text.red('Skins are disabled in the arena.'))
    event.cancel()
  }
})
```

## Using the Java API from another mod

The KubeJS layer is a thin wrapper. Any mod can use the same contract without KubeJS installed:

- `org.minechestplate.mcpskins.api.MCPSkinsAPI` is the facade. Same methods as the binding above.
- `org.minechestplate.mcpskins.api.event` holds the events, posted on `NeoForge.EVENT_BUS`:
  `SkinUnlockEvent.Pre` / `.Post`, `SkinRevokedEvent`, `CollectionCompletedEvent`,
  `SkinEquipEvent`, and `SkinFuseEvent.Pre` / `.Post`. The cancellable ones implement
  `ICancellableEvent`.
- `SkinInfo`, `CollectionProgress`, `UnlockResult`, `UnlockSource` and `RevokeSource` are the
  supporting types.

```java
NeoForge.EVENT_BUS.addListener((SkinUnlockEvent.Pre event) -> {
    if (event.getSource() == UnlockSource.UNLOCK_ITEM && isBlocked(event.getPlayer())) {
        event.setCanceled(true);
    }
});
```

Everything the mod's own commands, items and UIs do goes through these same paths, so an unlock
made from a script is indistinguishable from one made by a command.
