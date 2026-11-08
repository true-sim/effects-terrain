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
