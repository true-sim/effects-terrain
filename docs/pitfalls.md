# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository, in the engine's
[docs/pitfalls.md](https://github.com/true-sim/engine/blob/main/docs/pitfalls.md) form: what went wrong, how that was
established, the rule that follows, and where the proof is. Only verified things go here: a measurement, a reproduced
failure, a test that fails without the fix, or a reading of the code that shows the fault.

The engine's entries apply here too.

## A material record's `[Range]` must admit the engine's probe values

**What happened.** The terrain material's `triplanarSharpness` was `[Range(1, 64)]`. Every terrain material failed to
load: `Materials.findMasks` builds the type's record through `fromValues` with probe values (0.123456) to find its mask
fields, and the record's range check threw `IllegalArgumentException: triplanarSharpness is 1 to 64: 0.123456`.

**Rule.** Ranges of material parameters start at 0 (or below what a probe passes); clamp in the shader what must be
larger (`max(p.triplanarSharpness, 1.0)`). Proof: `TerrainSeamsTest` failed with that message before the change.

## A test listing of shading hooks needs test Slang sources

**What happened.** With `slang.shadingHooks` set, the test listing `TestTerrainShadingHooks` was generated, but
`compileTestSlang` skipped itself ("there are Slang sources" false), so its variants were not built and every GPU test
failed: `TestTerrainShadingHooks's variant shaders/shading-hooks/TestTerrainShadingHooks/atmosphere/froxels.hooks.spv
... is not on the classpath` (build-logic 0.19.1).

**Rule.** A project whose tests draw with other libraries' hooks keeps at least one Slang file under `src/test/slang`
(here `test/layers.slang`, the layer rules' test kernel).

## One error per metre of size underrates a terrain's coarse levels and its cliffs

**What happened.** Choosing levels with the scene's `Detail.screenError(px, errorPerMeter)` (one geometric error per
metre of a cell's side for every level) left pops of 5.2 px on the synthetic test range at 2 px and 9.0 px over
Lauterbrunnen. Measured (the largest distance along the normal between a level's triangles and the next finer
surface, over sample tiles): the error per metre of side grows from about 0.003 at 256 m tiles to 0.008-0.011 at 8 km
(Lauterbrunnen, medians), and single tiles on the valley's cliffs reach 0.07 at level 1 (19 m on a 256 m tile).

**Rule.** Levels switch at bands from each level's measured median error (`TerrainError.measure`), and each tile's own
error, measured as it is built, splits it further where it is seen too close (`Terrain.refine`). Proof: `LodPopTest`
(the real valley's worst pop 8.97 px before, within 3.3 px after; the synthetic range's too).

## The snow's occlusion map on sloping terrain needs a bias of a texel's rise

**What happened.** effects-weather's snow at full coverage covered a third of a rugged slope seen from 120 m, in
concentric bands: the ground was below its own depth in the top-down map (1.6 m texels over slopes of 30 degrees and
more) by more than the default 0.15 m bias, so it counted as sheltered (`TerrainSnowTest`, 0.33 of the pixels bright).
With `weather.snow.bias` 3 m, the whole view is snow.

**Rule.** Over terrain, set the snow's bias to about a map texel times the steepest slope's rise.

## Skirts cannot close a gap seen along its own seam

**What happened.** `TerrainStreamingTest` flew along z = 0, a line every level's tiles have a seam on, and on CI's
lavapipe one pixel showed the background in 3 of 40 frames, always pixel (36, 9) (the RX 9070 XT showed none; with
occlusion culling off the same). Marching that pixel's ray on the CPU: it ran within the plane z = 0, meeting the
terrain exactly on the seam between a 1 km tile and a 512 m tile. With the eye in the seam's vertical plane, a ray
along it passes between the two levels' edges, and the skirts, lying in that plane, are seen edge on and cover no
pixel.

**Rule.** Not a gap the skirts leave anywhere else: the eye must be in the seam's plane to the precision of a pixel.
Tests keep the camera off seam planes (`TerrainStreamingTest.LANE`). Proof: run 42513466369's log (the ray's hit at
z = -0.0 and the tiles there) and its `hole-*.png`.

## lavapipe's shader cache is machine code for the CPU that filled it

**What happened.** CI's shard 2 (`LodPopTest`, `TerrainSeamsTest`) passed four times, then its test JVM died with
SIGILL (exit 132, no hs_err) three times on one commit whose change touched neither test, 14 s in, right after the
device came up. The failing runners had an AMD EPYC 7763 (Zen 3, AVX2); the shards that passed in the same run had
EPYC 9V45 and 9V74 (AVX-512) (the tests print the CPU since). CI restores lavapipe's disk cache of compiled shaders
(`MESA_SHADER_CACHE_DIR`) by shard alone: one filled on an AVX-512 runner hands the 7763 instructions it lacks.

**Rule.** The cache's key names the runner's CPU model (`ci.yml`, step `cpu`). Proof: run 42514599944 (shard 2:
`CPU: AMD EPYC 7763`, SIGILL; shards 3 and 4: 9V45, 9V74, passing); run 42515034244, the key with the CPU: shards 2
and 3 on EPYC 7763s pass.

## The engine's traced shadow masks trace NaN rays from the view's edge pixels

**What happened.** `TerrainShadowsTest`'s ray-traced case under GPU-assisted validation
(`TRUESIM_GPU_ASSISTED_VALIDATION=true`, RX 9070 XT) reported 10 errors, `VUID-RuntimeSpirv-OpRayQueryInitializeKHR-06351`
"[ Debug region: traced shadows ] OpRayQueryInitializeKHR operand Ray Origin contains a NaN", with the sky in view and
again looking steeply down with none; every other test was clean. engine 0.194.0's `shadows/masks.slang` rebuilds a
pixel's normal from the neighbours nearest in depth (`neighbour`), each clamped into the view: at its first and last
row and column the clamped neighbour is the pixel itself, nearest in depth (a difference of 0), so one difference
vector is zero, the cross product is zero and `normalize` gives NaN, then the ray's origin (`p.position + p.normal *
offset`). Terrain covers the view's edges; scenes with sky at the edges (depth 0, `pointAt`'s sky test) never reach it.

**Rule.** (By reading of the code; the errors came with and without sky in view.) An engine bug to fix there (take the
other neighbour when the clamped one is the pixel, or skip it);
until then the ray-traced shadows test is skipped under GPU-assisted validation, and runs under core validation.

## Layers that follow each level's own slope and curvature flicker as tiles switch

**What happened.** The first film (`terrain-flight.mp4`, v0.1.0) flickered: looking for blocks of 10 x 10 pixels (of
160 x 90) that change and change back from one frame to the next, frames 50 to 71 (the camera 25 m over the slope
under the cliffs) changed blocks by up to 26-42 of 255 and back, where the camera's own motion changes the
worst block by 6 in 95 % of frames; the patches of grass and scree jumped from frame to frame. Drawing each frame twice
at one camera, before and after the terrain's update (`LodSwitchAnalysis`), put it on the switches: the material's
layers followed the slope of each level's normals and a curvature measured at each level's spacing, and tiles
switched levels at once, so a switch moved the layers' lines across whole tiles.

**Rule.** Nothing the material reads may depend on a tile's level unless it morphs with the level: tiles morph between
levels' surfaces (normals too), the curvature is measured on a grid fixed in the world (`terrain.layerScale`).
Proof: `LodTransitionTest` (switching at once with each level's own curvature changes a block by more than the
camera's motion within a frame; morphing, it does not).

## A deformed copy does not carry data in its tangents

**What happened.** Carrying the layers' slope normal in the TANGENT attribute, tiles drew right until they morphed:
as deformed copies (a morph target, `GeometryPool.addDeformed`) the morphing tiles drew flat grey, all rock, in
`LodTransitionTest`'s pictures, and the change in a frame reached 30 of 255. In the colour's alpha the slope survived
the copies. Observed, not traced in the engine: the deformation pass presumably rebuilds tangents against the morphed
normals, which would turn a normal-like tangent into a near-zero vector.

**Rule.** Data for the material goes in the colour or the UV sets, never the tangent, on anything that may be
deformed.

## A heightfield holds no beds on a near-vertical wall, however fine its tiles

**What happened.** The cliff relief first went into the surface's height only (`CliffParameters.Relief.HEIGHT`): beds
as terraces, the face shifted by reading the data up or down its slope. On Lauterbrunnen's Staubbach wall, rendered
plain (no textures, no detail normal), the wall showed buttresses and joints but not one bed, on 0.5 m tiles and on
0.125 m tiles alike: a grid has a vertex only every footprint times the slope's rise up a wall (at 84 degrees, 0.5 m
of grid is 5 m of wall), so a bed of 9 m gets two vertices. Tiles of 16 m with 128 quads (0.125 m) asked for 3,838
tiles of 33,000 triangles in that one view and did not settle in two minutes.

**Rule.** Detail up a wall needs vertices up the wall: the relief goes into the mesh (`Relief.MESH`, `CliffMesh`:
steep edges cut every step up the wall, vertices moved horizontally), the heightfield's relief kept for slopes.

## Halving steep edges recursively grows as a power of the wall's height

**What happened.** `CliffMesh` first refined by longest-edge bisection in rounds (each triangle split by the edges it
had halved). A triangle with two steep edges becomes three each round, each again with two steep edges: after
log2(H / step) rounds, 3^log2(H / step) = (H / step)^1.58 triangles instead of H / step. One 128 m tile of the
Staubbach wall hit the cap of 150,000 vertices (a timing run of `TileMeshes.build` on the real site); cut edges with a
zipper between the triangle's edge rising most and the other two: 24,476 vertices, 151 ms.

**Rule.** Refine a heightfield's wall by cutting edges by their rise and zipping rows between them, not by recursive
halving.

## A feature that fades a little more at each level never splits a tile by the next level's error

**What happened.** A tile's error is measured against the next finer level. The cliffs' relief fades with each level
(beds by the vertices' spacing up the wall, joints by the grid's), each level only a little more than the one below:
`CliffGpuTest`'s wall view showed no refined tile at all (levels shown 4, 3, 3, 11, 13, 14 from the finest), the
wall drawn by tiles of 256 m.

**Rule.** The relief's error is measured against the finest level's relief (`TerrainError.tile` with the cliff
settings): the same view then shows 7 refined tiles, the levels 4, 15, 8, 9, 13, 14.

## Negative tangent signs on the terrain's tiles crash CI's lavapipe

**What happened.** Flagging a wall's vertices for the material by their tangent's sign (w = -1) made CI's shard 2 die
in `LodPopTest` (the synthetic range, whose steep faces refine) three times in a row: SIGSEGV in lavapipe's compiled
shader code on a worker thread (`hs_err`: frames in `libvulkan_lvp.so`, SEGV_MAPERR), on an AMD EPYC 7763. The same
tests passed on the local lavapipe (Mesa 26.2.6, Windows, also with `LP_NATIVE_VECTOR_WIDTH=256`) and on the RX 9070
XT. Bisected on CI (workflow_dispatch): the branch without the flag passed, the branch with the flag and the shader as
released crashed; so the sign alone, not the shader reading it. Cause in lavapipe or the engine not found.

**Rule.** Data for the material does not go in the tangent's sign; the wall flag is the tangent's direction
((0, -0.6, -0.8), y + z below 0). Proof: runs 42653968325 (three attempts), 42661161606 (no flag: green),
42661168328 (flag, old shader: SIGSEGV).

## Rows cut evenly up a wall sample a dipping ledge in dashes

**What happened.** In the cliffs' close flight (v0.6.1) every ledge read as a row of evenly spaced dark dashes. Not the
shadows (the same with the sun's shadows off), not the shader's lines (the same with a plain material): the
geometry's overhang undersides. The rows of a refined wall lay where each grid edge was cut evenly, at heights that
differ from column to column, and the bed profile (a sharp notch, and a sudden step at each bed's top) is a function
of the height: a row fell on the notch's edge in one column and beside it in the next. Cut at levels of the altitude,
the dipping beds (4 degrees) still crossed the rows: downward-facing triangles of a level-1 tile came in clumps along
the wall with gaps of about 5 m (a scratch histogram of their positions), the dashes' period.

**Rule.** A refined wall's rows run along the bedding planes (edges cut where the surface crosses levels of
`TerrainSurface.bedHeight`, found by bisection), and nothing in the relief is sharper than two and a half rows. Proof:
`CliffMeshTest` (every cut vertex on its plane); the stills before and after (`CliffFilms`, plain and shadowless).
