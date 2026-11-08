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
    /** A tile's geometric error per metre of its side (what the screen-space error is measured from). */
    public static final Setting<Float> ERROR_PER_METER = Setting.decimal("terrain.errorPerMeter", 0.004f, 0.0001f, 1,
        "a terrain tile's geometric error per metre of its side");
    /** How far round the camera tiles are loaded, metres. */
    public static final Setting<Float> RANGE = Setting.decimal("terrain.range", 120_000, 100, 10_000_000,
        "how far round the camera terrain tiles load, metres");
    /** How many loaded cells the world attaches per frame. */
    public static final Setting<Integer> ATTACH_BUDGET = Setting.integer("terrain.attachBudget", 64, 1, 100_000,
        "terrain cells attached per frame at most");
    /** How many tile meshes start building per frame. */
    public static final Setting<Integer> BUILD_BUDGET = Setting.integer("terrain.buildBudget", 32, 1, 100_000,
        "terrain tile meshes asked for per frame at most");
    /** Metres a tile's skirts reach below the deepest gap they must close, against quantization. */
    public static final Setting<Float> SKIRT_MARGIN = Setting.decimal("terrain.skirtMargin", 0.25f, 0, 1000,
        "metres terrain skirts reach past the gaps they close");
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
    public static final List<Setting<?>> ALL = List.of(TILE_SIZE, TILE_QUADS, LEVELS, SCREEN_ERROR, ERROR_PER_METER,
        RANGE, ATTACH_BUDGET, BUILD_BUDGET, SKIRT_MARGIN, DETAIL_AMPLITUDE, DETAIL_WAVELENGTH, DETAIL_OCTAVES,
        DETAIL_EROSION, CURVATURE, CURVATURE_SCALE);

    private TerrainSettings() {
    }

    /** The surface's procedural detail and curvature as {@code settings} say. */
    public static SurfaceParameters surface(EngineSettings settings) {
        return new SurfaceParameters(settings.get(DETAIL_AMPLITUDE), settings.get(DETAIL_WAVELENGTH),
            settings.get(DETAIL_OCTAVES), settings.get(DETAIL_EROSION), settings.get(CURVATURE));
    }
}
