# TACZ: Advanced Skin System (MCPSkins)

A NeoForge 1.21.1 mod that adds a full weapon skin system on top of
[TACZ](https://github.com/MUKSC/TACZ-1.21.1) (the modern firearms mod for Minecraft).
Players unlock cosmetic skins for their guns, apply them in-game, and browse their
collection through two different UIs, all without ever swapping the underlying weapon
item.

## Features

### Texture-overlay skins, not fake weapons
Skins are implemented as a resource-pack texture overlay rather than as separate
registered guns. A weapon's `GunId` never changes when a skin is applied: only its
rendered texture (and, optionally, its inventory icon and full 3D geometry) does. This
means skins stack cleanly with attachments, ammo, and any other item data without side
effects.

- **Texture skins**: recolor the weapon's 3D model by dropping a PNG into
  `textures/skins/<weapon>/<skin_id>.png`.
- **Optional custom icon**: an accompanying `<skin_id>_icon.png` swaps the 2D
  inventory icon too.
- **Optional custom HUD icon**: an accompanying `<skin_id>_hud.png` swaps the weapon
  silhouette shown bottom-right of the screen while it's held, and an optional
  `<skin_id>_hud_empty.png` swaps its out-of-ammo variant.
- **Optional custom geometry**: a matching geo-model file next to the weapon's own
  model gives a skin an entirely different shape, not just a different color, while
  reusing the base weapon's animations.
- **LOD support**: TACZ swaps to a separate, lower-poly model/texture pair at distance
  and in third person. Skins can override this too, independently of the close-up
  model/texture: an optional `<skin_id>_lod.png` for the LOD's UV, and/or a geo-model
  file next to the weapon's own LOD model for a full LOD shape change.

### Two ways to browse and equip skins
- **Refit screen overlay**: an in-context skin carousel embedded directly into TACZ's
  native weapon refit screen, for quickly cycling through skins for the gun currently
  in hand. Its toggle button's position, size, and anchor corner are all configurable,
  including a drag-to-place picker in-game.
- **Skin Armory**: a standalone, full-screen catalog with a rotatable 3D preview
  podium, search, rarity/collection filters, an "owned/locked" filter, and sorting.
  Opens via hotkey (default **K**) or the `/mcpskins armory [skinId]` command, and
  works independently of what's in the player's hand.

### Live preview before you own it
Locked skins can be previewed on the real weapon model (in both the refit overlay and
the Armory) before a player owns them. Previews are entirely client-side; nothing is
sent to the server and no skin is granted until the player actually equips one they own.
This preview behavior is server-configurable and can be disabled.

### Server-authoritative ownership
Skin ownership is tracked per-player on the server (survives death, synced on login,
respawn, and dimension change) and mirrored to the client. Skins are granted via admin
commands or as an item drop, and unlock instantly with a toast/chat confirmation. A
skin actually equipped on a weapon adds a small "Skinned by \<player\>" lore line,
kept in sync as the skin changes or is removed.

### Fusing duplicates into rarer skins
Shift + right-clicking a skin-unlock item consumes a configurable number of
same-rarity duplicates from across the player's inventory and rerolls one skin of the
next rarity tier up (`COMMON → UNCOMMON → RARE → EPIC → LEGENDARY`), preferring skins
the player doesn't already own. Can be disabled server-side.

### Rich skin metadata
Each skin entry supports rarity, a named collection, a short description, and a "new"
badge, all optional and shown in the Skin Armory's filters, sorting, and info panel.

### Configurable, with a sane client/server split
- **Client config**: refit-button position/size/anchor, toast on/off and duration,
  carousel sizing. Purely local, editable in-game via the mod's config screen.
- **Server config**: fusion on/off and cost, whether locked skins can be previewed,
  and permission levels for equip-bypass and admin commands. Authoritative and synced
  per-world.

## Commands

```
/mcpskins give skin <player> <skinId>     Unlock a skin directly for a player
/mcpskins give item <player> <skinId>     Give a physical skin-unlock item
/mcpskins give all <player>               Unlock every skin for a player
/mcpskins take skin <player> <skinId>     Remove a specific skin
/mcpskins take skins <player>             Clear all unlocked skins
```

All skin IDs are validated against the skin registry and tab-completed, so a mistyped
ID can't be granted. These require operator permission (permission level configurable
via the server config, default `4`).

There's also a client-side, no-permission-needed command for opening the Armory
without a hotkey:

```
/mcpskins armory [skinId]                 Open the Skin Armory, optionally focused on one skin
```

## Adding skins

Skin packs are a single folder or `.zip` dropped into a `mcpskins/` folder in the game
directory. No `pack.mcmeta`, nothing to manually enable; it's picked up automatically,
including by worlds that already exist. See [`MCPSkinsPackFinder`](src/main/java/org/minechestplate/mcpskins/pack/MCPSkinsPackFinder.java)
for exactly how packs are discovered and registered.

Each pack has two halves, exactly like a normal resource pack + datapack, just merged
under one root:

- **`data/<namespace>/skins/`**: JSON files that register skins per-weapon. Each entry
  needs at minimum an `id`, `name`, and `label_color`; `rarity`, `collection`,
  `description`, and `is_new` are optional. See [`SkinManager`](src/main/java/org/minechestplate/mcpskins/skin/SkinManager.java)
  for the exact schema and the recommended `<base_gun>_<skin_name>` ID naming convention.
  Skin IDs are global, not per-weapon, so keep that in mind when naming them.
- **`assets/<namespace>/textures/skins/<base_gun, ":"→"/">/<skin_id>.png`**: the
  actual artwork, plus optional `<skin_id>_icon.png` (inventory icon), `<skin_id>_hud.png`
  (HUD silhouette, bottom-right of the screen), `<skin_id>_hud_empty.png` (out-of-ammo HUD
  variant), and `<skin_id>_lod.png` (UV for TACZ's separate distant/third-person LOD
  model) files. For a full shape change instead of just a re-texture, there's also an
  optional geo-model file placed next to the base weapon's own model, and independently
  another one next to its LOD model if it has one. See [`SkinAssetResolver`](src/main/java/org/minechestplate/mcpskins/client/render/SkinAssetResolver.java)
  and [`GunModelPatcher`](src/main/java/org/minechestplate/mcpskins/client/render/GunModelPatcher.java)
  for exactly how those paths are resolved.

> **Skin packs are a server-side concern.** The mod itself still needs to be installed
> on both client and server, that hasn't changed. But a `mcpskins/` skin pack only
> needs to go on the server: the pack finder registers it for `SERVER_DATA` only, and
> skin assets (textures, icons, geo-models) are streamed to each client over the
> network the first time they're actually needed, not read from a local copy. So
> there's no need to hand a skin pack to your players, and no supported way to load
> one client-side. More detail on this is coming to the wiki.

A full walkthrough of the pack layout, plus commands, configuration, and
troubleshooting, lives in the project wiki.

## Requirements

- Minecraft 1.21.1
- NeoForge `21.1.233`+
- [TACZ (MUKSC fork)](https://github.com/MUKSC/TACZ-1.21.1), `neoforge/1.21.1` branch

## Project layout

Client-only code (`Dist.CLIENT`: GUI, rendering, keybinds) lives under `client/`.
Everything else is grouped by concern at the top level.

```
src/main/java/org/minechestplate/mcpskins/
├── MCPSkins.java                 Mod entry point
├── command/                      /mcpskins server command tree
├── config/                       Client & server ModConfigSpec definitions
├── item/                         The skin-unlock item, including the fusion mechanic
├── mixin/                        Texture/model override hook into TACZ's TimelessAPI
├── network/                      Client/server sync packets
│   └── asset/                    Chunked asset-transfer payloads + the server-side asset store
├── pack/                         mcpskins/ folder scanner (the pack.mcmeta-less skin pack loader)
├── skin/                         Core skin domain: registry, data model, ownership
│   ├── SkinManager.java          Loads skin definitions from datapacks
│   ├── SkinAttachment.java       Per-player unlocked-skin storage
│   ├── SkinDataModels.java       Skin/rarity data types
│   ├── SkinComponents.java       The mcpskins:skin_id item data component
│   └── TACZSkinHelper.java       Weapon stack creation/skin application helpers
└── client/                       Everything client-only
    ├── ClientModEvents.java      Client setup: reload listeners, item tints, keybinds, config screen
    ├── ArmoryClientCommand.java, ArmoryKeybinds.java, TACZRefitSkinOverlay.java, ...
    ├── gui/                      Skin Armory screen, 3D podium widget
    │   └── settings/             In-game settings screens (config screen, button-position picker)
    └── render/                   Texture/icon/HUD/geometry resolution and patching
```

## License

MIT