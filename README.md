# true-sim effects-terrain

Terrain for true-sim from real elevation data fetched on demand: a site's public elevation sources pinned in a
manifest, fetched into a local cache and imported into a site the asset manager loads; a pyramid of heightfield tiles
streamed by the scene's multi-level world streaming, each tile a scene node whose mesh is a generated asset the
engine draws as GPU-culled meshlets in every pass it has (forward, visibility buffer, shadow maps, ray tracing); the
`terrain` material type blending rock, grass, scree and snow by slope, altitude and curvature, rock triplanar; and the
surface on the CPU for placement. Nothing is uploaded or drawn by hand: everything loads through the asset manager and
goes through the scene.

| Module | Contents |
|---|---|
| `effects-terrain-data` | `Manifest` (pinned tiles: URL, SHA-256, size, licence, attribution), `TerrainFetcher` (into the cache, checked), `GeoTiff` (JDK only: strips and tiles, Deflate, LZW, prediction), `TerrainImporter` (merged, cropped `.dem` rasters and a `site.terrain`), `TerrainAssets` (the site as an asset), `LocalFrame`, `SwissGrid`, `TerrainSite`, `TerrainSurface` (data, detail, curvature). JDK, common-math and scene-assets only: servers and tools read the terrain without a GPU. |
| `effects-terrain` | `Terrain` (the tiles in a world, streamed and shown), `TileMeshes` (tiles built as generated mesh assets), `TerrainError`, `TerrainGrid`, `TerrainLayers` (the material's rules on the CPU), `TerrainMaterial`, `TerrainSettings`; the `terrain` material type (`terrain/terrain.slang`, `TerrainType.TYPE`). |

```kotlin
dependencies {
    implementation("com.truesim.effects:effects-terrain:0.1.0") // with engine-scene 0.197, scene 0.70
}
```

## The data

`./gradlew fetchTerrain` (`-Ptruesim.terrain.manifest=<name>`, `-Ptruesim.terrain.cache=<dir>`; default
`lauterbrunnen` into `terrain-cache/`, which git ignores) downloads what the cache lacks from the manifest's URLs over
HTTPS, checks each file's SHA-256 and size before it takes its place, and imports the site: each source's tiles merged
on their common grid and cropped to its radius round the site, written as `<source>.dem` (a header and little-endian
floats) beside `site.terrain`, in `terrain-cache/sites/<site>/`. `FetchTerrain` does the same from code. Nothing
downloaded is ever committed; the manifests are.

**The site: Lauterbrunnen** (Bernese Oberland, Switzerland; the village church, 46.5937 N, 7.9087 E, about 800 m).
A U-shaped glacial valley with cliffs of 300 to 500 m, the Staubbach falls, the Jungfrau (4158 m), Mönch and Eiger
above it, Wengen and Mürren on terraces, and the Alps out to Mont Blanc 120 km away. Considered too: Hallstatt (47.5622
N, 13.6493 E: lake, Dachstein; Austria's 10 m ALS DGM under CC BY 4.0) and Zell am See (47.3238 N, 12.7966 E: lake,
Kitzsteinhorn, Grossglockner on the horizon); Lauterbrunnen's cliffs show the triplanar rock, and its valley is covered
by swissALTI3D at 2 m.

| Source | Where | Resolution | Licence and attribution |
|---|---|---|---|
| swissALTI3D 2025 (swisstopo), 45 tiles of 1 km, 53 MB | the valley round the village, 5 x 9 km | 2 m, bare ground, LV95 | free geodata (OGD); "Federal Office of Topography swisstopo" |
| Copernicus DEM GLO-30, 2 tiles, 84 MB (AWS open data `copernicus-dem-30m`) | 32 km round the site | 30 m, surface model | GLO-30 Public licence; "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved" |
| Copernicus DEM GLO-90, 12 tiles, 59 MB (`copernicus-dem-90m`) | 125 km round: the ring to the horizon | 90 m | as GLO-30, WorldDEM-90 |

`CREDITS.md` records them; `TerrainSite.attributions()` lists them for a game's credits. The sources blend at their
edges (swissALTI3D into GLO-30 over 150 m, GLO-30 into GLO-90 over 1.5 km); GLO-30 is a surface model, a few metres
above swissALTI3D's bare ground over forest (the median difference in the valley is about 8 m, tested).
Georeference: Copernicus tiles are WGS 84 with their samples on the georeference (pixel-is-point), swissALTI3D's LV95
with it on their corners; LV95 goes to WGS 84 by swisstopo's approximate formulas (within a metre); the local frame
(`LocalFrame`: x east, y up, z south, metres) measures the meridian's arc and each point's own parallel on WGS 84 from
the site's origin.

**The surface** (`TerrainSurface`): the sources' heights, bilinear from each raster's pyramid (2 x 2 averages) at the
level for the footprint a tile samples at; procedural detail below the data's resolution, octaves of gradient noise
from `terrain.detail.wavelength` down to twice the footprint, each damped by the slope the data and the octaves before
made (erosion-like: gullies on slopes, smooth cliff faces, quieter meadows), faded in between four and two
footprints; and the Earth's curvature (`terrain.curvature`): d² / 2R from the origin, 785 m at 100 km, so the far
mountains sink below the horizon. A height query at a data sample returns the sample (tested). The CPU queries read the
same function the tiles are built from, between the finest tiles' vertices on their triangles: `terrain.heightAt`,
`normalAt`, `layers` (the material's weights by its rules) and `vegetation` (the grass layer's weight: where trees
and grass may grow).

## The tiles

- **A pyramid streamed by the world.** `Terrain` registers a layer of `Levels.grid(terrain.tileSize, terrain.levels)`
  cells (128 m to 32 km by default) with the scene's `World` and follows the camera with an interest source
  (`follow`). Its profile's bands are where each level's measured error (`TerrainError`: the largest distance
  along the normal between a tile's triangles and the next finer surface, the median over sample tiles of the level)
  projects to `terrain.screenError` pixels: the world requests and releases cells by distance, coarse first, out to
  `terrain.range`.
- **Each tile's own error.** Built tiles carry their own error; a tile the levels' typical errors underrate (cliffs:
  a 300 m wall a vertex further along) is split by the terrain itself (`World.request` of its four finer cells) while
  its error, seen from the camera at its nearest point, is above the allowed pixels, and merged 15 % below it.
- **Meshes through the asset manager.** A cell is its tile's key; its mesh is a generated asset (`TileMeshes`, on the
  asset lanes): (quads + 1)² vertices at the level's spacing sampled from the surface (an apron for normals and
  curvature, so a tile needs nothing of its neighbours), two triangles a quad, flat meshlets (the pyramid is the
  level of detail; the engine culls meshlets by frustum and normal cone, and occlusion). Held in the scene resources
  (`terrain.buildBudget` a frame), the tile shows once its mesh is on the GPU.
- **No holes, no overlaps.** Each frame something changed, the tiles shown are worked out from the coarsest down: a
  wanted tile on the GPU shows; otherwise its finer tiles show if together they cover it, otherwise what showed there
  stays (a coarser tile or finer ones) until its replacement can show whole; a tile no wanted tile overlaps goes. Nodes
  are made and removed in the same frame, so the ground shows whole in every frame of a flight (tested).
- **Levels morph, never pop.** A switch is a transition over `terrain.morphSeconds` (0.6 s), eased: a split shows the
  finer tiles morphed exactly onto the coarser tile's surface (its triangles, heights and normals, skirts with them)
  in place of it, then unmorphs them; a merge morphs the finer tiles onto the coarser surface, then shows the coarser
  tile in their place. The morph is a morph target of a second mesh of the tile (`TileMeshes.TileMesh(key, level)`,
  asked for when the transition is due, held while it runs: it starts once every tile of it has its morphing mesh on
  the GPU), weighted through the node's `MorphWeights`; the engine draws such a tile as a deformed copy, posed each
  frame, so only tiles in a transition pay for it. Engine 0.197.0's per-material displacement (true-sim/engine#454)
  could run the morph in every tile's vertex stage instead, by distance, as CDLOD does, but not yet here: a morph
  moves a height and turns the normal (three values a vertex) while a tile's vertex channels are all taken (planar
  position high and low, altitude, curvature), the camera would have to reach the hook, and geometry displaced in
  the raster stage is not what the rays and the shadows see.
- **The layers do not jump with the level.** The curvature the material's layers follow is measured at
  `terrain.layerScale` (16 m) on a grid fixed in the world, the same samples at every level finer than that, carried
  in the vertex colour's blue; the slope is the surface's own, which morphs with the tiles' normals. As tiles switch,
  grass, scree, rock and snow move with the morph instead of jumping. Before, each level measured the curvature at its
  own spacing and tiles switched at once, and the layers jumped: the flicker in the first film (`docs/pitfalls.md`).
  Coarser than `terrain.layerScale` (tiles from 2 km, far off) each level still has its own curvature. The procedural detail and the lines' noise are functions of the world
  position, the same at every level; the detail a coarser level leaves out is part of what its tiles morph.
- **Crack-free seams.** Every tile hangs double-sided skirts from its four edges, as deep as the widest gap its edges
  can have against a tile up to two levels finer or coarser (sampled at the finer spacing), plus `terrain.skirtMargin`
  and the meshes' 16-bit height quantization. With them no background shows through anywhere; without them (the
  setting off) the gaps show (tested). One place they cannot reach: with the eye in a seam's vertical plane, a ray
  along the seam passes between two levels' edges and the skirts are seen edge on (a pixel, on lavapipe;
  `docs/pitfalls.md`).
- **What the vertices carry for the material:** the planar position (from the tile's origin plus that origin modulo
  16384 m) split in high and low float16 halves over UV0 and UV1 (millimetres, continuous across tiles for every
  texture whose repeat divides 16384 m); the altitude in two bytes of the vertex colour (1/8 m, linear between
  vertices since the bytes interpolate apart); the curvature at `terrain.layerScale` (`terrain.curvatureScale`) in its
  blue.
- **Large worlds.** Tiles are nodes at their world positions in doubles; the engine draws every instance relative to
  each view's camera (two floats per axis), so the terrain is as precise 100 km out as at the origin.

## The material

`terrain` (`TerrainType.TYPE`, registered with the renderer's `Materials`; scene data by `TerrainMaterial.data`) has
four layers, each a texture set (albedo, normal, roughness in green; assets: the showcase gives Poly Haven's, the tests
generate plain ones) times a colour, so it shows without textures too, and its rules (`TerrainParameters`): rock from
`rockSlopeStart` to `rockSlopeEnd` degrees (earlier on convex ridges), scree on moderate slopes, in hollows and above
the grass line, meadows below it, snow above `snowLine` (lower in hollows, higher on ridges) where it is not steeper
than `snowSlopeEnd`; value noise breaks the lines (`jitter`) and tints large slopes (`macro`). Rock is projected three
ways (triplanar, `triplanarSharpness`) with the normal maps whiteout-blended; the altitude's screen gradients come from
the slope. The rules' CPU twin (`TerrainLayers`) is tested against the shader point for point (a test kernel). Every
way the engine shades runs it (forward, the visibility buffer, ray and probe hits), and the shading hooks lie on it:
effects-weather's snow (`weather.snow`) covers it (tested; over steep terrain its occlusion map wants a bias of a
texel's rise, `weather.snow.bias` 3 m at 1.6 m texels), effects-atmosphere's aerial perspective fogs it.

## Shadows, global illumination, ray tracing

Tiles are ordinary instances of pool meshes, so the shadow maps (cascades, atlas tiles) draw them and their culling,
and ray tracing traces them: `resources.rayTracing(rayTracing)` gives every tile mesh a bottom level of its own (its
one level: flat meshes), built from the pool's quantized positions in the frames after its mesh arrives, compacted,
never refitted (terrain is static), and removed with the mesh; the top level holds every tile shown (tested: its
instance count is the shown tiles'; under GPU-assisted validation engine 0.194.0's traced masks trace NaN rays from the
view's edge pixels, a bug there, `docs/pitfalls.md`). Traced shadows, global illumination's probes and cascades and the reference path
tracer see the terrain as drawn. A bottom level per tile keeps builds small and lets tiles stream; a level's coarser
tiles replace finer ones in the top level as they do on screen. The cost is memory: over Lauterbrunnen's valley floor
1,554 tiles show and their bottom levels take 820 MiB (0.45 MiB a tile of 9,216 triangles). Not built: bottom levels
from a coarser grid for far tiles (a separate, smaller mesh per tile for rays), which would cut that by about 4x per
level of coarsening.

## What it costs

`TerrainBenchmark` (`TRUESIM_BENCHMARK=true`; RX 9070 XT, 1920 x 1080, Lauterbrunnen at the defaults, the sun's four
2048 cascades, JaCoCo off; medians of 120 frames after 60, from the frame graph's timestamps; 2026-11-08). The frames
include the test's read-back of the colour (0.28-0.30 ms).

| View | Tiles shown (level 0 ... 7) | Meshlets | Visibility buffer | Forward |
|---|---|---|---|---|
| valley floor, 25 m up, along the valley | 1,554 (464, 220, 104, 45, 193, 150, 201, 177) | 44,030 | 1.37 ms (IDs 0.38, shading 0.46) | 2.37 ms (meshlets 1.84) |
| 3,500 m, to the horizon | 1,161 (160, 100, 137, 89, 151, 158, 201, 165) | 17,325 | 0.90 ms (IDs 0.15, shading 0.27) | 1.20 ms (meshlets 0.70) |
| valley floor, the sun ray traced | 1,554, every one in the top level | | 1.46 ms | |

The flight of the films in real time (26 s at 30 frames a second, up to 1,857 tiles shown, at most 22 waiting for
their mesh at once): GPU 1.16 ms a frame (p99 2.5); the terrain's own update 0.22 ms a frame (p99 5.9, the frames
holding many new meshes); the scene's and the sync's 0.04 ms. The world's update is the main thread's cost: 5.1 ms a
frame (p99 12.9) with the interest moving 16 m steps (about every second frame of the flight), 5.8 ms when it moved
every frame. Alone on the CPU (no GPU, the same bands, about 1,000 cells), one selection of scene-world's
`World.update` after the interest moved took 3.3 to 6.0 ms, and 0.000 ms with it still: the selection over 9 levels
and 137 km of bands, scene-world's own cost. The interest follows in steps of `terrain.followStep` (32 m by default),
so the selection runs a few times a second in flight. Many level-0 tiles come from the valley's cliffs: their own
error keeps them split out to a few kilometres.

The level transitions, measured back to back with the code before them on a GPU shared with other renders (so the
absolute figures run above the ones above): up to 43 transitions at once on the flight; GPU 1.45 to 1.56 ms a frame
against 1.29 (the morphing tiles' deformed copies); the terrain's update 0.31 to 0.35 ms against 0.22 (p99 13.4 to
14.1 against 6.2, the frames that start many transitions); the scene's and sync's 0.10 to 0.11 against 0.05 (posing
the morphing tiles).

## Settings

`TerrainSettings` (group `terrain`): `tileSize`, `tileQuads`, `levels`, `detail.*` and `curvature` (read when a
`Terrain` is made), `screenError`, `errorSamples`, `errorScale`, `range`, `buildBudget`, `skirts`, `skirtMargin`,
`curvatureScale`.

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene`, `engine`, `effects-atmosphere` and `effects-weather` are used
as included builds when present (`-Ptruesim.<repo>=false` to use the published packages instead). The GPU tests run on
any Vulkan 1.3 device (CI: Mesa's lavapipe), validated; tests of the real site run where `fetchTerrain` has filled the
cache and are skipped elsewhere. Films: `TRUESIM_SCREENSHOTS=true ./gradlew :effects-terrain:test --tests
'*TerrainFilms*' -Pscreenshots=<dir>`.

MIT; the elevation data keeps its own licences (`CREDITS.md`).
