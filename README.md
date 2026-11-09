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
    implementation("com.truesim.effects:effects-terrain:0.6.0") // with engine-scene 0.200, scene 0.70
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

## Cliffs

Lauterbrunnen's walls are 300 to 500 m of limestone, near vertical. Elevation data 2 m apart holds them as smooth
curtains: a heightfield's grid has a vertex only every footprint times the slope's rise up a wall (20 m for 2 m tiles
on an 84-degree face), its bilinear samples leave creases that run down the face as streaks, and a texture projected
from above stretches down it. The cliffs get a material layer of their own and relief the data cannot hold.

**The material: a cliff layer.** Rock's share past `cliffSlopeStart` (50 degrees) is the walls' limestone, all of it
from `cliffSlopeEnd` (60) (`TerrainLayers.cliff`, the CPU twin, tested against the shader): a texture set of its own
(`TerrainMaterial.data(..., cover, cliff)`, a Poly Haven set plugs in as any layer's) times `cliffColor` (0.40, the
limestone's 0.35 to 0.45), `cliffSaturation` (0: grey; warm scans greyed to limestone), roughness `cliffRough` (0.85,
a texture's varying round it), projected three ways with `cliffSharpness`; then

- a **world-space detail normal**: bedding planes near horizontal (`beddingThickness` 1.3 m, every fourth stronger,
  dipping `beddingDip` towards `beddingAzimuth`, waving by `beddingWaviness`) and two sets of vertical joints
  (`jointSpacing`, `jointStrength`), its gradient by differences in world space (no projection to streak), each
  faded out where a pixel cannot hold it;
- **macro variation down the faces**: dark water streaks, long down the face and narrow across it (`streaks`); darker
  bands under the beds' ledges, dipping as the bedding (`bands`, `bandSpacing`, readable from afar); lighter, warmer
  fresh rockfall scars (`scars`); lichen patches (`lichen`, `lichenColor`), more where the wall is less steep; wet
  darkening and gloss at the walls' foot, where the ground turns concave (`wetFoot`).

**The geometry: relief on the walls** (`CliffParameters`, `terrain.cliffs.*`), only where the data's slope passes
`terrain.cliffs.slopeStart` (55 degrees, all of it from 60), every feature a horizontal shift of the wall, out of it
or into it:

- **smoothing**: on the walls the data is averaged over `smoothing` (2 m: a box over its bilinear samples, a quadratic
  B-spline), so its samples leave no creases;
- **bedding**: beds `bedThickness` (9 m) thick, varying by `bedVariation`, dipping `dip` towards `dipAzimuth`, waving
  by `waviness`; each bed's soft foot weathered back `ledge` metres under the hard bed above (an overhang, its
  underside facing down, the bed below ending in a ledge), its depth and thickness each bed's own (some beds none),
  deepening and fading along the wall, each hard bed leaning back a little as it rises;
- **joints**: two vertical sets at right angles (`jointAzimuth`), about `jointSpacing` apart, clefts `jointWidth` wide
  and `jointDepth` deep, some missing;
- **buttresses**: ribs and bays `buttressDepth` out and in, about `buttressLength` apart along the wall.

Where the relief goes (`terrain.cliffs.geometry`), both kept, since each wins somewhere:

| | `MESH` (default) | `HEIGHT` |
|---|---|---|
| How | the tiles' steep triangles refined, every vertex on a wall moved horizontally (`CliffMesh`) | the face shifted by reading the data a little up or down its slope (the heightfield stays a heightfield) |
| Ledges and overhangs | at any steepness: vertices every `step` up the walls (0.4 m at level 0, twice that each level up, `terrain.cliffs.levels` refined), true overhangs | only where the grid has vertices enough up the wall (slopes, not walls); a heightfield holds no overhang |
| CPU queries | the heightfield's (the data, smoothed on the walls): within the relief's few metres of the drawn wall | exact |
| Cost | vertices: see below | none beyond finer tiles |

**Refining** (`MESH`): each grid edge on a cliff is cut into as many even pieces as it rises steps
(`terrain.cliffs.step`), its points sampled from the surface; a triangle with cut edges is triangulated between its
edge that rises most and the other two (they rise as much together) by a zipper up both sides, each new triangle a row
of the wall, so a wall of H metres takes about 2 H / step triangles a grid triangle (halving edges recursively instead
grew as (H / step)^1.6: a 128 m tile filled 150,000 vertices). How an edge is cut depends on its two ends alone and its
points on where they lie, so two triangles sharing an edge, in a tile or in neighbouring tiles of a level, cut it alike
(tested: neighbours meet vertex for vertex, moved alike; the mesh conforming, one winding). The skirts hang from every
vertex of a border, refined ones too (tested: each tile's skirt foot lies below its coarser neighbour's border and the
other way round; on the GPU no background shows through a refined wall where levels meet, both shading paths). A
tile's normals are the moved wall's, from the shift's change up and along the wall (smooth across tiles: a function
of the point). Morph targets move wall vertices onto the coarser level's wall. A tile's error counts how far its
relief lies from the finest level's (`TerrainError.tile`), so walls split where their relief would show coarse.
`terrain.cliffs.maxVertices` caps a tile.

**What it costs** (`CliffBenchmark`, `TRUESIM_BENCHMARK=true`; RX 9070 XT, 1920 x 1080, the visibility buffer, the
sun's four 2048 cascades, the cliff film's two views at their middle, each configuration in a JVM of its own, medians
of 120 frames after 80; 2026-11-09, other agents' renders sharing the GPU, so the frames are noisy: two of them, rerun,
dropped from 6.7 and 13-14 ms to the figures here):

| Configuration | Valley floor: GPU frame, tiles (refined), vertices | Staubbach wall | Settled in |
|---|---|---|---|
| before: 128 m tiles, no relief | 1.21-1.26 ms, 1,929, 8.2 M | 1.41 ms, 2,010, 8.5 M | 8-9 s |
| `HEIGHT`, 32 m tiles (0.5 m) | 1.38-1.41 ms, 3,780, 16.0 M | 1.54 ms, 3,711, 15.7 M | 17-19 s |
| `MESH`, 32 m tiles (0.5 m) | 1.86-2.15 ms, 2,508 (687), 21.1 M | 1.69 ms, 2,535 (679), 20.9 M | 31-43 s |
| `MESH`, the default 128 m tiles | 1.53 ms, 1,875 (609), 24.6 M | 1.66 ms, 1,917 (612), 24.2 M | 27-35 s |

The meshlet IDs pass carries most of the difference (0.27 ms before, 0.41 `HEIGHT`, 0.55-0.72 `MESH`); shading
changes little (0.51-0.71 ms: the cliff layer's detail normal and macro variation are a few value-noise lookups). One
tile on the Staubbach wall builds on the CPU in 2-35 ms before (by level), 3-35 ms `HEIGHT`, and 187 ms (32 m, 41,585
vertices), 219 ms (64 m, 36,153), 160 ms (128 m, 24,476), 60 ms (256 m, unrefined: its error against the finest
relief) `MESH`: the views settle three to four times slower, on the asset lanes, not the frame. With the sun ray
traced the bottom levels hold every vertex: 1,065 MiB before (staubbach view, traced shadows 0.30 ms, frame 1.69 ms);
`MESH` on 32 m tiles would need about 2.5 times that (by its vertices) and did not fit beside another process's 7 GiB
on the 16 GiB card (`VK_ERROR_OUT_OF_DEVICE_MEMORY`), so where rays shadow the terrain keep `terrain.cliffs.levels`
low or `terrain.cliffs.step` coarse, or the relief in `HEIGHT`.

**Shadows.** The relief is in the tiles' meshes, so the shadow maps draw it and ray tracing's bottom levels hold it:
under a sun grazing a wall its overhangs and joints shadow the wall below them, by the cascades (which pancake the
casters towards the sun) and by rays alike (`CliffGpuTest`: the shadows darken 1,474 and 1,321 pixels of a 192 x 128
view of the relief, none of the flat wall).

## Modifiers: beds, roads, squares and the materials they lay

What other libraries change in the terrain comes in as **terrain modifiers** (`TerrainModifier`, in
`effects-terrain-data`), composed in order (`TerrainModifiers`): a river's bed and banks, a road's cut and fill, a
levelled square, and the surface materials they lay into the terrain's own material (gravel, cobbles, dirt, sand)
with soft shoulders. The tiles of every level, their morph targets and skirts, and every CPU query (`heightAt`,
`normalAt`, `layers`, `materials`, and so what is placed on the terrain) read the modified surface.

```java
TerrainModifier roads = new TerrainModifier() {
    public double height(double x, double z, double footprint, double height) {
        return grading.height(x, z, height);                 // cut and fill: given the height below, the new one
    }
    public double materials(double x, double z, double footprint, double[] cover) {
        double c = grading.cobbles(x, z);                   // 0 to 1, soft at the shoulders
        cover[TerrainModifier.COBBLES] = c;
        return c;                                           // the total cover laid here
    }
    public Bounds bounds() { return new Bounds(-600, -400, 900, 1200); }   // asked only within
};
TerrainModifiers all = TerrainModifiers.of(riverBeds).with(roads);       // roads over the rivers' gravel
Terrain terrain = new Terrain(world, resources, site, material, settings, all);
double[] cover = new double[TerrainModifier.MATERIALS];
double laid = terrain.materials(x, z, cover);                            // gravel, cobbles, dirt, sand
MaterialData data = TerrainMaterial.data(rules, rock, grass, scree, snow,
    new TerrainMaterial.Cover(gravel, cobbles, dirt, sand));             // or Cover.PLAIN: colors alone
```

- **Heights** pass through each modifier in turn, in the frame the tiles are drawn in (without the curvature's fall):
  each gets the height the surface and the modifiers before it make, and returns its own. The footprint (the grid's
  spacing at that level) is given, so a feature narrower than it can fade rather than alias. Work modifiers out from
  the unmodified surface (`surface().unmodified()`), as a river network or a settlement's grading needs the ground
  before it is changed.
- **Materials**: each modifier writes the cover of each material it lays (0 to 1) and returns their total; a later
  modifier's lie over the earlier ones' as far as it covers (`earlier x (1 - c) + own`). The tiles carry the total
  cover in one less the vertex color's alpha and the mix in the tangent (as a direction: corners gravel, sand, cobbles
  and dirt; the pairs met along rivers and roads blend exactly), and an uncovered vertex beside covered ones takes
  their mix, so a material fades out at its shoulder as itself. The terrain material lays the materials over its
  layers with value noise breaking the shoulders' edges (`coverJitter`); each material is a texture set like the
  layers' (`gravelAlbedo`, ..., `gravelSize`, ...) or its color alone (`gravelColor`, ...).
- **Bounds** (optional): a modifier is asked only within them.
- **Pure and thread-safe**, as the surface is: tiles build on several threads, and a point's answer must not depend
  on what was asked before.
- Modifiers are fixed when the terrain is made (a new set: a new `Terrain`, its tiles built again through the asset
  cache).

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

`TerrainSettings` (group `terrain`): `tileSize`, `tileQuads`, `levels`, `detail.*`, `curvature` and `cliffs.*` (read
when a `Terrain` is made), `screenError`, `errorSamples`, `errorScale`, `range`, `buildBudget`, `skirts`, `skirtMargin`,
`curvatureScale`. The cliffs: `cliffs.relief` (on), `cliffs.geometry` (`MESH` or `HEIGHT`), `cliffs.step`,
`cliffs.levels`, `cliffs.rounds`, `cliffs.maxVertices`, `cliffs.slopeStart`, `cliffs.slopeEnd`, `cliffs.smoothing`,
`cliffs.bedThickness`, `cliffs.bedVariation`, `cliffs.ledge`, `cliffs.dip`, `cliffs.dipAzimuth`, `cliffs.waviness`,
`cliffs.wavinessLength`, `cliffs.jointSpacing`, `cliffs.jointWidth`, `cliffs.jointDepth`, `cliffs.jointAzimuth`,
`cliffs.buttressDepth`, `cliffs.buttressLength`; the material's cliff layer is in its parameters (`cliff*`,
`bedding*`, `joint*`, `streaks`, `bands`, `scars`, `lichen`, `wetFoot`).

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene`, `engine`, `effects-atmosphere` and `effects-weather` are used
as included builds when present (`-Ptruesim.<repo>=false` to use the published packages instead). The GPU tests run on
any Vulkan 1.3 device (CI: Mesa's lavapipe), validated; tests of the real site run where `fetchTerrain` has filled the
cache and are skipped elsewhere. Films: `TRUESIM_SCREENSHOTS=true ./gradlew :effects-terrain:test --tests
'*TerrainFilms*' -Pscreenshots=<dir>`; the cliffs' (`*CliffFilms*`) take Poly Haven's sets from `~/.truesim/polyhaven`
(`-Ptruesim.polyhaven=<dir>`) where the showcase fetched them.

MIT; the elevation data keeps its own licences (`CREDITS.md`).
