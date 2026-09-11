# Afterimage

Keeps the city on screen in Minecraft 1.12.2. Chunk sections that vanilla throws away (when you fly past the render
distance, when the server unloads chunks, when renderers are reloaded) are kept as exact GPU copies, drawn beyond the
render distance and cached on disk, so everything you have seen is back the moment you rejoin.
Built for city-scale builds made of [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) and
[LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles).

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it (see LICENSE).

## Features

- **Exact, not LOD**: the far zone draws the very bytes the game uploaded for each section. No downsampling, no
  simplified buildings. Rebuild determinism and capture correctness were proven in game by a built-in verifier
  (`-Dafterimage.verify=true`).
- **All block layers**: solid, cutout and translucent (glass, stained glass, water, ice). Translucent copies are drawn
  after the opaque ones, back to front and blended, the way vanilla draws its translucent layer.
- **Copied on the GPU**: section geometry is duplicated with `glCopyBufferSubData` at the moment vanilla would lose it,
  with no readback to the CPU and no GL queries that would stall on the driver.
- **Persistent**: sections are written to a per-server, per-dimension cache in the background (deflate, atomic writes,
  newer versions supersede queued older ones). On join the cache is restored nearest-first, uploaded to the GPU for at
  most 4 ms per frame.
- **No half-built buildings while chunks load**: vanilla compiles a section before its LittleTiles / Chisels & Bits tile
  entities arrive. Until vanilla's geometry equals the copy (or the server reports a newer change, or vanilla has been
  quiet for 20 s), vanilla's section is hidden and the complete copy is drawn. Sections within 32 blocks are never
  hidden, so your own edits show at once.
- **Never fights vanilla**: a section vanilla shows itself always wins. Its copy stays in VRAM and is replaced only if
  the section's geometry changed (upload fingerprints), so flying back and forth costs no copies; the disk cache is
  refreshed from every new build.
- **Seamless fog**: while the far zone has content, normal fog is pushed out (2048 blocks by default), so near terrain
  and far zone fade into the sky together. Water, lava and blindness keep their vanilla fog.
- **Budgeted**: 8 GB of VRAM for the far zone by default, farthest sections are evicted first.
- **Works with** OptiFine (Render Regions off), Chisels & Bits and LittleTiles, including LittleTiles' merged
  re-uploads of chunk buffers.
- **Server sync (optional)**: with Afterimage on the server, the server records the tick of every change clients
  have to re-render (block changes and block update notifications, which LittleTiles and Chisels & Bits use for their
  own edits; chunk loading does not count). On join the client asks for everything since its last sync and drops copies
  and cached files made before those changes; while playing, new changes arrive every second.
- **Measures itself**: live section-geometry VRAM, unique meshes, per-section sizes (`sections.csv`) and raw geometry
  samples for offline analysis.

## Usage

Put the jar into the `mods` folder of the client together with MixinBooter. The server does not need it, but it
helps: put the same jar (with MixinBooter) on the server too, and it tells clients which chunks changed while they were
away, so a cache never shows buildings that no longer exist, and caches of different worlds on one server stay apart.
Keep the file name `z-afterimage-<version>.jar`: Forge loads coremods in file-name order, and a name that sorts
before `mixinbooter` stops the game at launch with `NoClassDefFoundError: zone/rong/mixinbooter/IEarlyMixinLoader`.

1. Recommended video settings: **Render Distance** 16-24 (the far zone keeps the rest), OptiFine **Render Regions:
   Off** (required). Fog can stay on.
2. Play. Every place you visit is cached as you see it.
3. Rejoin: the cached city is on screen again within seconds, before the server has sent a single far chunk.

`/afterimage` shows what is going on: GPU geometry, far zone, disk cache and verifier.

## Commands

```
/afterimage                 status: GPU section geometry, far zone, disk cache, verifier
/afterimage far             far zone status
/afterimage far off|on      disable the far zone (frees all copies) / enable it again
/afterimage fog [blocks]    show / set where fog ends while the far zone is shown (0 = vanilla fog)
/afterimage disk            disk cache status
/afterimage sync            server sync status
/afterimage disk off|on     stop / resume writing and restoring the cache
/afterimage disk clear      delete the cache of the current server and dimension
/afterimage csv             write per-section geometry sizes to afterimage/sections.csv
/afterimage off|on          stop / resume upload tracking
```

## Configuration (JVM arguments)

| Key | Default | Meaning |
|---|---|---|
| `-Dafterimage.far` | true | far zone on or off |
| `-Dafterimage.farBudgetMB` | 8192 | VRAM for far-zone copies, farthest evicted first |
| `-Dafterimage.farPlane` | 8192 | far clipping plane used for the far zone, in blocks |
| `-Dafterimage.farNear` | 6 | near clipping plane of the far pass, in blocks (depth precision far away) |
| `-Dafterimage.fogEnd` | 2048 | fog end while the far zone has content, in blocks; 0 keeps vanilla fog |
| `-Dafterimage.settleMs` | 20000 | a freshly loaded vanilla section stays hidden behind its copy until they match, the server reports a change, or vanilla is quiet this long |
| `-Dafterimage.hideNear` | 32 | sections nearer than this many blocks are never hidden behind their copy |
| `-Dafterimage.disk` | true | disk cache on or off |
| `-Dafterimage.diskUploadMs` | 4 | per-frame time budget for uploading restored sections |
| `-Dafterimage.enabled` | true | upload tracking and measurements (far-zone reuse and the disk cache rely on it) |
| `-Dafterimage.verify` | false | development only: verifier (forced rebuilds, GPU readbacks) and raw geometry samples |

## Files

Everything lives in `minecraft/afterimage/`:

| Path | Content |
|---|---|
| `cache/<server>/[<world id>/]DIM<n>/r.<rx>.<rz>/<cx>.<sy>.<cz>.L<layer>.aimg` | cached section geometry (world id when the server has Afterimage) |
| `cache/<server>/<world id>/DIM<n>/sync.txt` | last server tick whose changes this cache has applied |
| `summary.log` | one status line per minute |
| `verify.log`, `mismatch/` | verifier results and dumps of any mismatch |
| `sections.csv`, `samples/` | measurements for the tools below |
| `errors.log` | any error; a failing part disables itself instead of crashing the game |

## Limitations

- Tile entity special renderers and entities draw themselves outside chunk geometry (signs, chests, banners, beds,
  Immersive Railroading tracks and trains, LittleTiles animated structures, vehicles), so they are not in the far zone.
- Without Afterimage on the server, edits made by other players while you are far away stay out of date in your cache
  until you come near them. With it, changed chunks are dropped from the cache and show again once you come near.
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
libs/netty-all-4.1.9.Final.jar    Netty 4.1.9 (Minecraft 1.12.2 library)
libs/fastutil-7.1.0.jar           fastutil 7.1.0 (Minecraft 1.12.2 library)
libs/guava-21.0.jar              Guava 21.0 (Minecraft 1.12.2 library)
```

then

```
JAVA8_HOME=/path/to/jdk8 ./build.sh     # build/z-afterimage-<version>.jar
```

`tools/analyze_quads.py [samples dir]` measures how compressible captured geometry is,
`tools/roundtrip_quads.py [samples dir]` checks a bit-exact rectangle encoding of it,
`tools/analyze_mismatch.py [mismatch dir]` explains verifier mismatches quad by quad.

## Requirements

Minecraft 1.12.2, Forge 14.23.5.x, MixinBooter 10.x, OpenGL 3.1. Client only.
