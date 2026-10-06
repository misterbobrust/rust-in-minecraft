# Rust in Minecraft

Facepunch's **Rust** inside **Minecraft 1.21.11** (Fabric): the guns, the building system, doors, stability and raiding, with Rust's own models, animations and sounds.

> **Early release.** Everything below works in singleplayer; multiplayer has not been tested yet.

**No Rust content is distributed here.** The installer builds the mod's assets on your PC from *your own* copy of Rust. See [How it works](#how-it-works).

---

## Features

### Weapons
- **Assault Rifle**, **Semi-Automatic Rifle**, **Semi-Automatic Pistol** and **Rocket Launcher**, with Rust's first-person animations, recoil, muzzle flashes, reloads and sounds.
- Rust's player model and animations for you and other players.
- Rocket splash damage with Rust's falloff.

### Building
- **Building Plan** with Rust's pie menu: foundation, wall, floor, window wall, doorway, half wall and wall frame.
- Rust's socket snapping: pieces click onto each other the way they do in Rust.
- **Hammer**: swing to repair, hold right-click for the pie menu to upgrade (twig → wood → stone → sheet metal → armored), rotate or demolish. With the hammer in hand, aim at a piece to see its stability and health.
- **Stability**: ported from Rust. Every piece shows `% STABLE`; remove a foundation and what stood on it collapses.
- **Health and raiding**: bullets and rockets damage building pieces and doors through each tier's real protection values.

### Doors
- Single and double doors, wood and sheet metal, in doorways and wall frames.
- Rust's open/close animations and sounds. Tap **E** to open or close, hold **E** for the pie menu (open/close, knock).

### Decorating
- Place **any Minecraft block** freely inside your base, with a live hologram of the block you hold.
- Torches go anywhere on a wall, and give real light.

### Atmosphere
- Buildings are lit like part of the world: per-vertex light, sky occlusion, baked normal maps.
- Rain and snow stop at your roof, and sounds are muffled when a wall is between you and the source.
- Works with **Iris + Sodium** shader packs (buildings fall back to a shader-friendly render path).

All items are in the **Rust in Minecraft** creative tab. English and Russian interface.

## Controls

| Action | Control |
| --- | --- |
| Fire / swing hammer / place piece | Left mouse |
| Aim down sights (weapons) | Right mouse |
| Pie menu (Building Plan, Hammer) | Hold right mouse |
| Rotate a piece (Building Plan) | `R` |
| Reload | `R` |
| Open / close a door | Tap `E` |
| Door pie menu (open/close, knock) | Hold `E` |

## Requirements

- Windows
- **Rust** installed (Steam)
- Minecraft **1.21.11** with [Fabric Loader](https://fabricmc.net/use/installer/) 0.19 or newer
- About 4 GB of free RAM and 3 GB of free disk space while building

## Install

1. Download `RustInMinecraft-Installer.exe` from [**Releases**](../../releases/latest).
2. Close Rust (and any Rust server), then run the installer. Your Rust folder is found automatically, otherwise pick it.
3. Choose where to save the result and press **Build the mod**. It takes about 5 minutes on a fast PC, up to 40 on a slow one.
4. Put **both** jars from the output folder (`rust-in-minecraft-*.jar` and `fabric-api-*.jar`) into your Minecraft `mods` folder.
5. Start Minecraft with the Fabric 1.21.11 profile.

> Windows SmartScreen may warn about the installer because it is not code-signed: choose *More info → Run anyway*. Some antivirus programs also flag unsigned Python-based installers; the installer only reads your Rust folder and writes to the folder you choose.

## How it works

The mod's code is separate from Rust's content. The installer reads the models, textures, animations and sounds from your own Rust install, converts them for Minecraft, and packs them into the mod on your computer. Nothing from Rust is downloaded or included in this repository or in the installer.

**Do not share the jar the installer produces:** it contains Facepunch's content and is for your own use only.

## Disclaimer

Rust and its content belong to Facepunch Studios. This project is an unofficial fan project and is not affiliated with or endorsed by Facepunch Studios or Mojang Studios.

## License

Copyright © 2026 misterbobrust. All rights reserved. The mod is free to download and play; see [LICENSE.txt](LICENSE.txt).
