# TACZ: Advanced Skin System (MCPSkins)

A NeoForge 1.21.1 mod that adds a full weapon skin system on top of
[TACZ](https://github.com/MUKSC/TACZ-1.21.1) (the modern firearms mod for Minecraft).
Players unlock cosmetic skins for their guns, apply them in-game, and browse their
collection through two different UIs — all without ever swapping the underlying weapon
item.

## Features

### Texture-overlay skins, not fake weapons
Skins are implemented as a resource-pack texture overlay rather than as separate
registered guns. A weapon's `GunId` never changes when a skin is applied — only its
rendered texture (and, optionally, its inventory icon and full 3D geometry) does. This
means skins stack cleanly with attachments, ammo, and any other item data without side
effects.

- **Texture skins** — recolor the weapon's 3D model by dropping a PNG into
  `textures/skins/<weapon>/<skin_id>.png`.
- **Optional custom icon** — an accompanying `<skin_id>_icon.png` swaps the 2D
  inventory icon too.
- **Optional custom geometry** — a matching geo-model file next to the weapon's own
  model gives a skin an entirely different shape, not just a different color, while
  reusing the base weapon's animations.

### Two ways to browse and equip skins
- **Refit screen overlay** — an in-context skin carousel embedded directly into TACZ's
  native weapon refit screen, for quickly cycling through skins for the gun currently
  in hand.
- **Skin Armory** — a standalone, full-screen catalog with a rotatable 3D preview
  podium, search, rarity/collection filters, and sorting. Opens via hotkey or the
  `/mcpskins armory` command, and works independently of what's in the player's hand.

### Live preview before you own it
Locked skins can be previewed on the real weapon model — in both the refit overlay and
the Armory — before a player owns them. Previews are entirely client-side; nothing is
sent to the server and no skin is granted until the player actually equips one they own.

### Server-authoritative ownership
Skin ownership is tracked per-player on the server and synced to the client. Skins are
granted via admin commands or as an item drop, and unlock instantly with a toast/chat
confirmation.

### Rich skin metadata
Each skin entry supports rarity, a named collection, a short description, and a "new"
badge — all optional and shown in the Skin Armory's filters, sorting, and info panel.

## Commands

```
/mcpskins give skin <player> <skinId>     Unlock a skin directly for a player
/mcpskins give item <player> <skinId>     Give a physical skin-unlock item
/mcpskins give all <player>               Unlock every skin for a player
/mcpskins take skin <player> <skinId>     Remove a specific skin
/mcpskins take all <player>               Clear all unlocked skins
```

All skin IDs are validated against the skin registry and tab-completed, so a mistyped
ID can't be granted.

## Adding skins

Skins are defined per-weapon in datapack JSON files under `data/<namespace>/skins/`.
Each entry needs at minimum an `id`, `name`, and `label_color`; `rarity`, `collection`,
`description`, and `is_new` are optional. See [`SkinManager`](src/main/java/org/minechestplate/mcpskins/skin/SkinManager.java)
for the exact schema and the recommended `<base_gun>_<skin_name>` ID naming convention.

## Requirements

- Minecraft 1.21.1
- NeoForge `21.1.233`+
- [TACZ (MUKSC fork)](https://github.com/MUKSC/TACZ-1.21.1), `neoforge/1.21.1` branch

## Project layout

```
src/main/java/org/minechestplate/mcpskins/
├── MCPSkins.java                 Mod entry point
├── ClientModEvents.java          Client setup: reload listeners, item tints, keybinds
├── item/                         The skin-unlock item
├── mixin/                        Texture/model override hook into TACZ's renderer
└── skin/
    ├── SkinManager.java          Loads skin definitions from datapacks
    ├── SkinAttachment.java       Per-player unlocked-skin storage
    ├── SkinDataModels.java       Skin/rarity data types
    ├── SkinComponents.java       The mcpskins:skin_id item data component
    ├── TACZSkinHelper.java       Weapon stack creation/skin application helpers
    ├── client/                   Refit screen overlay, Skin Armory, keybinds
    ├── command/                  /mcpskins command tree
    ├── network/                  Client/server sync packets
    └── render/                   Texture/icon/geometry resolution and patching
```

## License

MIT
