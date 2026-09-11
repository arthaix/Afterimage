# Afterimage

Keeps the city on screen in Minecraft 1.12.2. Chunk sections that vanilla throws away (when you fly past the render
distance, when the server unloads chunks, when renderers are reloaded) are kept as exact GPU copies, drawn beyond the
render distance and cached on disk, so everything you have seen is back the moment you rejoin.
Built for city-scale builds made of [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) and
[LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles).

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it (see LICENSE).

## Features

- **Exact, not LOD**: the far zone draws the very bytes the game uploaded for each section. No downsampling, no
  simplified buildings. Rebuild determinism and capture correctness are checked in game by a built-in verifier.
- **Copied on the GPU**: section geometry is duplicated with `glCopyBufferSubData` at the moment vanilla would lose it,
  with no readback to the CPU and no hitch.
- **Persistent**: sections are written to a per-server, per-dimension cache in the background (deflate, atomic writes,
  newer versions supersede queued older ones). On join the cache is restored nearest-first, uploaded to the GPU for at
  most 4 ms per frame.
- **Never fights vanilla**: a section vanilla shows itself always wins; a copy is freed as soon as its chunk is loaded
  and compiled again, and the cache is refreshed from that new build.
- **Budgeted**: 8 GB of VRAM for the far zone by default, farthest sections are evicted first.
- **Works with** OptiFine (Render Regions off), Chisels & Bits and LittleTiles, including LittleTiles' merged
  re-uploads of chunk buffers.
- **Measures itself**: live section-geometry VRAM, unique meshes, per-section sizes (`sections.csv`) and raw geometry
  samples for offline analysis.

## Usage

Put the jar into the `mods` folder of the client together with MixinBooter. Nothing is needed on the server.

1. Recommended video settings: **Render Distance** 16-24 (the far zone keeps the rest), OptiFine **Fog: Off**
   (copies are drawn without fog), OptiFine **Render Regions: Off** (required).
2. Play. Every place you visit is cached as you see it.
3. Rejoin: the cached city is on screen again within seconds, before the server has sent a single far chunk.

`/afterimage` shows what is going on: GPU geometry, far zone, disk cache and verifier.

## Commands

```
/afterimage                 status: GPU section geometry, far zone, disk cache, verifier
/afterimage far             far zone status
/afterimage far off|on      disable the far zone (frees all copies) / enable it again
/afterimage disk            disk cache status
/afterimage disk off|on     stop / resume writing and restoring the cache
/afterimage disk clear      delete the cache of the current server and dimension
/afterimage csv             write per-section geometry sizes to afterimage/sections.csv
/afterimage off|on          stop / resume capture and verification
```

## Configuration (JVM arguments)

| Key | Default | Meaning |
|---|---|---|
| `-Dafterimage.far` | true | far zone on or off |
| `-Dafterimage.farBudgetMB` | 8192 | VRAM for far-zone copies, farthest evicted first |
| `-Dafterimage.farPlane` | 8192 | far clipping plane used for the far zone, in blocks |
| `-Dafterimage.disk` | true | disk cache on or off |
| `-Dafterimage.diskUploadMs` | 4 | per-frame time budget for uploading restored sections |
| `-Dafterimage.enabled` | true | capture, verifier and measurements |

## Files

Everything lives in `minecraft/afterimage/`:

| Path | Content |
|---|---|
| `cache/<server>/DIM<n>/r.<rx>.<rz>/<cx>.<sy>.<cz>.L<layer>.aimg` | cached section geometry |
| `summary.log` | one status line per minute |
| `verify.log`, `mismatch/` | verifier results and dumps of any mismatch |
| `sections.csv`, `samples/` | measurements for the tools below |
| `errors.log` | any error; a failing part disables itself instead of crashing the game |

## Limitations

- Translucent blocks (glass, water) are not part of the far zone yet.
- Edits made by other players while you are far away stay out of date in your cache until you come near them.
- Shader packs and OptiFine Render Regions are not supported.

## Building

The sources call Minecraft by SRG names and hook it with early MixinBooter mixins, so there is no reobfuscation step
and the build is a plain two-pass `javac` instead of Gradle. Put these jars into `libs/`:

```
libs/mixinbooter-10.7.jar
libs/forge-1.12.2-srg.jar          Forge 1.12.2 srgBin jar (Minecraft + Forge, SRG names)
libs/forge-1.12.2-universal.jar    Forge 1.12.2-14.23.5.x universal jar
libs/forge-1.12.2-dev.jar          Forge 1.12.2 dev jar (MCP names), used only for the @Mod class
libs/lwjgl-2.9.4.jar               LWJGL 2.9.4-nightly-20150209
```

then

```
JAVA8_HOME=/path/to/jdk8 ./build.sh     # build/afterimage-<version>.jar
```

`tools/analyze_quads.py [samples dir]` measures how compressible captured geometry is,
`tools/roundtrip_quads.py [samples dir]` checks a bit-exact rectangle encoding of it,
`tools/analyze_mismatch.py [mismatch dir]` explains verifier mismatches quad by quad.

## Requirements

Minecraft 1.12.2, Forge 14.23.5.x, MixinBooter 10.x, OpenGL 3.1. Client only.
