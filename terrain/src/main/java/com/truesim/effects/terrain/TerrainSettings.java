package com.truesim.effects.terrain;

import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The terrain's settings: its tiles and levels and the procedural detail read when a terrain is made (a change
 * takes a new one), the streaming's error, range and budgets read every frame.
 */
public final class TerrainSettings {

    /** The finest tiles' side, metres; each level's tiles are twice the size of the one below. */
    public static final Setting<Float> TILE_SIZE = Setting.decimal("terrain.tileSize", 128, 16, 4096,
        "side of the finest terrain tiles, metres (each coarser level doubles it)");
    /** Quads a tile has along a side, at every level. */
    public static final Setting<Integer> TILE_QUADS = Setting.integer("terrain.tileQuads", 64, 4, 252,
        "quads along a terrain tile's side, at every level");
    /** How many levels of tiles there are: the coarsest tiles are the finest's side times 2^(levels - 1). */
    public static final Setting<Integer> LEVELS = Setting.integer("terrain.levels", 9, 1, 16,
        "how many levels of terrain tiles, each twice as coarse as the one below");
    /** The screen-space error a tile may show before its finer tiles replace it, pixels. */
    public static final Setting<Float> SCREEN_ERROR = Setting.decimal("terrain.screenError", 2, 0.25f, 64,
        "pixels of error a terrain tile may show before finer tiles replace it");
    /**
     * How many tiles of each level the terrain measures its levels' errors on when it is made ({@link TerrainError}).
     */
    public static final Setting<Integer> ERROR_SAMPLES = Setting.integer("terrain.errorSamples", 8, 1, 1024,
        "tiles of each level the terrain's level errors are measured on");
    /** The measured errors times this are what the streaming works with (below 1: finer tiles kept further out). */
    public static final Setting<Float> ERROR_SCALE = Setting.decimal("terrain.errorScale", 1, 0.001f, 1000,
        "how much the terrain's measured level errors count when choosing levels");
    /** How far round the camera tiles are loaded, metres. */
    public static final Setting<Float> RANGE = Setting.decimal("terrain.range", 120_000, 100, 10_000_000,
        "how far round the camera terrain tiles load, metres");
    /**
     * How far the camera moves before the terrain's interest follows it, metres: the world selects its cells anew only
     * then (selecting costs the main thread a few milliseconds over a 120 km range).
     */
    public static final Setting<Float> FOLLOW_STEP = Setting.decimal("terrain.followStep", 32, 0, 10_000,
        "metres the camera moves before the terrain's streaming selects tiles anew");
    /** How long a level transition takes: tiles morph between levels' surfaces over it, seconds (0: switch at once). */
    public static final Setting<Float> MORPH_SECONDS = Setting.decimal("terrain.morphSeconds", 0.6f, 0, 10,
        "seconds terrain tiles take to morph from one level's surface to another's");
    /**
     * The scale the material's slope and curvature are measured at, metres: the same for every level finer than it, so
     * the layers do not change with the tiles' level.
     */
    public static final Setting<Float> LAYER_SCALE = Setting.decimal("terrain.layerScale", 16, 1, 10_000,
        "metres over which the terrain material's slope and curvature are measured, the same at every level");
    /** How many tile meshes start building per frame. */
    public static final Setting<Integer> BUILD_BUDGET = Setting.integer("terrain.buildBudget", 32, 1, 100_000,
        "terrain tile meshes asked for per frame at most");
    /** Metres a tile's skirts reach below the deepest gap they must close, against quantization. */
    public static final Setting<Float> SKIRT_MARGIN = Setting.decimal("terrain.skirtMargin", 0.25f, 0, 1000,
        "metres terrain skirts reach past the gaps they close");
    /** Whether tiles hang skirts from their edges (off only to see the gaps between levels they close). */
    public static final Setting<Boolean> SKIRTS = Setting.bool("terrain.skirts", true,
        "whether terrain tiles hang skirts closing the gaps between levels (off: to see them)");
    /** The procedural detail's height, metres. */
    public static final Setting<Float> DETAIL_AMPLITUDE = Setting.decimal("terrain.detail.amplitude", 2.5f, 0, 1000,
        "height of the terrain's procedural detail below the data's resolution, metres (0: none)");
    /** The longest wavelength of the procedural detail, metres. */
    public static final Setting<Float> DETAIL_WAVELENGTH = Setting.decimal("terrain.detail.wavelength", 64, 1,
        100_000, "longest wavelength of the terrain's procedural detail, metres");
    /** How many octaves the procedural detail has. */
    public static final Setting<Integer> DETAIL_OCTAVES = Setting.integer("terrain.detail.octaves", 6, 0, 16,
        "octaves of the terrain's procedural detail");
    /** How strongly slopes damp the detail's finer octaves (erosion-like gullies and ridges). */
    public static final Setting<Float> DETAIL_EROSION = Setting.decimal("terrain.detail.erosion", 1.5f, 0, 100,
        "how strongly slopes damp the terrain detail's finer octaves (erosion)");
    /** Whether the terrain falls away with the Earth's curvature from the site's origin. */
    public static final Setting<Boolean> CURVATURE = Setting.bool("terrain.curvature", true,
        "whether the terrain falls away with the Earth's curvature");
    /** The curvature (Laplacian) per metre that maps to full convexity or concavity in the material's blend. */
    public static final Setting<Float> CURVATURE_SCALE = Setting.decimal("terrain.curvatureScale", 0.05f, 0.0001f,
        100, "terrain curvature (1/m) that counts as fully concave or convex for the material");

    /** Every terrain setting. */
    public static final List<Setting<?>> ALL = List.of(TILE_SIZE, TILE_QUADS, LEVELS, SCREEN_ERROR, ERROR_SAMPLES, ERROR_SCALE,
        RANGE, FOLLOW_STEP, MORPH_SECONDS, LAYER_SCALE, BUILD_BUDGET, SKIRTS, SKIRT_MARGIN, DETAIL_AMPLITUDE, DETAIL_WAVELENGTH, DETAIL_OCTAVES,
        DETAIL_EROSION, CURVATURE, CURVATURE_SCALE);

    private TerrainSettings() {
    }

    /** The surface's procedural detail and curvature as {@code settings} say. */
    public static SurfaceParameters surface(EngineSettings settings) {
        return new SurfaceParameters(settings.get(DETAIL_AMPLITUDE), settings.get(DETAIL_WAVELENGTH),
            settings.get(DETAIL_OCTAVES), settings.get(DETAIL_EROSION), settings.get(CURVATURE));
    }
}
