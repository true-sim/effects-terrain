# true-sim effects-terrain

Terrain for true-sim from real elevation data, fetched on demand.

| Module | Contents |
|---|---|
| `effects-terrain-data` | pinned elevation sources, their fetcher and importer, the terrain's surface on the CPU |
| `effects-terrain` | tiles streamed by the scene's world, built into meshlet meshes through the asset manager, the terrain material type |

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene`, `engine`, `effects-atmosphere` and `effects-weather` are used
as included builds when present (`-Ptruesim.<repo>=false` to use the published packages instead).

MIT; the elevation data keeps its own licences (`CREDITS.md`).
