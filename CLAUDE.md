# Working on effects-terrain

- Read `docs/pitfalls.md` here and the engine's (true-sim/engine `docs/pitfalls.md`) before changing per-frame code,
  shaders or benchmarks: they list mistakes made once, with their evidence and the rule that follows.
- Add to `docs/pitfalls.md` when a bug, a regression or a wrong assumption costs real time. Only verified things: a
  measurement, a reproduced failure, a test that fails without the fix, or code that shows the fault, with a link to
  the proof. A cause not found is written as an observation, never as a guess.
- Everything loads through the asset manager and goes through the scene: tiles are scene nodes with renderables whose
  meshes are assets; nothing is uploaded or drawn by hand.
- Every cap, budget, rate and threshold is a setting (`TerrainSettings`), never a constant.
- Elevation data is fetched on demand into the terrain cache (`terrain-cache/`, never committed) from the sources the
  manifests pin (URL, SHA-256, licence); a new source needs its licence checked and recorded in `CREDITS.md`.
- Per-frame code allocates nothing (`truesim.allocation-free`). Shaders live under `src/main/slang/terrain/`.
- No libraries beyond the JDK, true-sim's own and LWJGL. MIT; the data keeps its own licences.
