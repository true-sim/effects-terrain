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
