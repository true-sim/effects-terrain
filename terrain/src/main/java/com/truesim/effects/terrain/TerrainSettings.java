package com.truesim.effects.terrain;

import com.truesim.effects.terrain.data.CliffParameters;
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

    /**
     * Whether cliffs get the relief the data cannot hold ({@link CliffParameters}: bedding ledges, joints, buttresses).
     * It shows as far as the tiles' spacing allows: ledges and joints need the finest tiles at 0.5 m or finer
     * ({@code terrain.tileSize} 32 with 64 quads), buttresses show at every spacing.
     */
    public static final Setting<Boolean> CLIFFS = Setting.bool("terrain.cliffs.relief", true,
        "whether cliffs get bedding ledges, joints and buttresses below the data's resolution");
    /**
     * Where the cliff relief goes ({@link CliffParameters.Relief}): {@code MESH} (default), steep triangles refined and
     * vertices moved out of the walls and into them (ledges and overhangs at any steepness); {@code HEIGHT}, into the
     * heightfield (no extra vertices, the CPU queries exact, beds only where the grid holds them: slopes, not walls).
     */
    public static final Setting<CliffParameters.Relief> CLIFF_RELIEF = Setting.choice("terrain.cliffs.geometry",
        CliffParameters.Relief.MESH, "where the terrain's cliff relief goes: refined meshes (MESH) or the heightfield (HEIGHT)");
    /** How far up a wall the finest refined meshes' edges may rise, metres, each coarser level's twice ({@code MESH}). */
    public static final Setting<Float> CLIFF_STEP = Setting.decimal("terrain.cliffs.step", 0.4f, 0.05f, 100,
        "metres a refined cliff mesh's edge may rise at most (MESH)");
    /** How many of the finest levels refine their cliffs ({@code MESH}); coarser tiles keep their grid. */
    public static final Setting<Integer> CLIFF_LEVELS = Setting.integer("terrain.cliffs.levels", 3, 0, 16,
        "how many of the finest terrain levels refine their cliffs' meshes (MESH)");
    /** How many times an edge may be halved ({@code MESH}). */
    public static final Setting<Integer> CLIFF_ROUNDS = Setting.integer("terrain.cliffs.rounds", 9, 0, 16,
        "a cliff mesh edge is cut into at most 2 to this power pieces (MESH)");
    /** The most vertices a tile's refined mesh may have ({@code MESH}): refining stops at it. */
    public static final Setting<Integer> CLIFF_VERTICES = Setting.integer("terrain.cliffs.maxVertices", 150_000, 1000,
        4_000_000, "most vertices a terrain tile's refined cliff mesh may have (MESH)");
    /** The slope the cliff relief starts at, degrees. */
    public static final Setting<Float> CLIFF_SLOPE_START = Setting.decimal("terrain.cliffs.slopeStart", 55, 0, 89,
        "slope where the terrain's cliff relief starts, degrees");
    /** The slope the cliff relief is whole at, degrees. */
    public static final Setting<Float> CLIFF_SLOPE_END = Setting.decimal("terrain.cliffs.slopeEnd", 60, 1, 90,
        "slope where the terrain's cliff relief is whole, degrees");
    /** The width the data is averaged over on the walls, metres: no creases at its samples (streaks down a wall). */
    public static final Setting<Float> CLIFF_SMOOTHING = Setting.decimal("terrain.cliffs.smoothing", 2, 0, 100,
        "width the elevation data is averaged over on cliffs, metres (removes the creases its samples leave)");
    /** The cliffs' beds' thickness, metres. */
    public static final Setting<Float> CLIFF_BED = Setting.decimal("terrain.cliffs.bedThickness", 9, 0.5f, 200,
        "thickness of the beds of the terrain's cliffs, metres");
    /** How much the beds' thickness varies, 0 to 1. */
    public static final Setting<Float> CLIFF_BED_VARIATION = Setting.decimal("terrain.cliffs.bedVariation", 0.45f, 0,
        1, "how much the thickness of the cliffs' beds varies, 0 to 1");
    /** The width of the ledges on the beds' tops, metres. */
    public static final Setting<Float> CLIFF_LEDGE = Setting.decimal("terrain.cliffs.ledge", 1.5f, 0, 50,
        "width of the ledges on the cliffs' beds, metres (0: no bedding)");
    /** The beds' dip, degrees. */
    public static final Setting<Float> CLIFF_DIP = Setting.decimal("terrain.cliffs.dip", 4, -44, 44,
        "dip of the cliffs' beds, degrees");
    /** The direction the beds dip to, degrees clockwise from north. */
    public static final Setting<Float> CLIFF_DIP_AZIMUTH = Setting.decimal("terrain.cliffs.dipAzimuth", 150, -360, 360,
        "direction the cliffs' beds dip towards, degrees clockwise from north");
    /** How far the beds wave up and down, metres. */
    public static final Setting<Float> CLIFF_WAVINESS = Setting.decimal("terrain.cliffs.waviness", 2.5f, 0, 100,
        "how far the cliffs' beds wave up and down, metres");
    /** Over what length the beds wave, metres. */
    public static final Setting<Float> CLIFF_WAVINESS_LENGTH = Setting.decimal("terrain.cliffs.wavinessLength", 140, 1,
        10_000, "length over which the cliffs' beds wave, metres");
    /** The joints' spacing, metres. */
    public static final Setting<Float> CLIFF_JOINT_SPACING = Setting.decimal("terrain.cliffs.jointSpacing", 24, 1,
        1000, "spacing of the vertical joints in the terrain's cliffs, metres");
    /** The joints' width, metres. */
    public static final Setting<Float> CLIFF_JOINT_WIDTH = Setting.decimal("terrain.cliffs.jointWidth", 2.5f, 0.1f,
        100, "width of the joints in the terrain's cliffs, metres");
    /** The joints' depth, metres. */
    public static final Setting<Float> CLIFF_JOINT_DEPTH = Setting.decimal("terrain.cliffs.jointDepth", 2f, 0, 50,
        "depth of the joints in the terrain's cliffs, metres (0: none)");
    /** The first joint set's strike, degrees clockwise from north. */
    public static final Setting<Float> CLIFF_JOINT_AZIMUTH = Setting.decimal("terrain.cliffs.jointAzimuth", 25, -360,
        360, "strike of the first set of joints in the terrain's cliffs, degrees clockwise from north");
    /** How far the cliffs' ribs stand out and bays go in, metres. */
    public static final Setting<Float> CLIFF_BUTTRESS_DEPTH = Setting.decimal("terrain.cliffs.buttressDepth", 4, 0,
        100, "how far the cliffs' buttresses stand out and bays go in, metres (0: none)");
    /** The buttresses' spacing along the wall, metres. */
    public static final Setting<Float> CLIFF_BUTTRESS_LENGTH = Setting.decimal("terrain.cliffs.buttressLength", 90, 4,
        10_000, "spacing of the buttresses along the terrain's cliffs, metres");

    /** Every terrain setting. */
    public static final List<Setting<?>> ALL = List.of(TILE_SIZE, TILE_QUADS, LEVELS, SCREEN_ERROR, ERROR_SAMPLES, ERROR_SCALE,
        RANGE, FOLLOW_STEP, MORPH_SECONDS, LAYER_SCALE, BUILD_BUDGET, SKIRTS, SKIRT_MARGIN, DETAIL_AMPLITUDE, DETAIL_WAVELENGTH, DETAIL_OCTAVES,
        DETAIL_EROSION, CURVATURE, CURVATURE_SCALE, CLIFFS, CLIFF_RELIEF, CLIFF_STEP, CLIFF_LEVELS, CLIFF_ROUNDS, CLIFF_VERTICES, CLIFF_SLOPE_START, CLIFF_SLOPE_END, CLIFF_SMOOTHING, CLIFF_BED,
        CLIFF_BED_VARIATION, CLIFF_LEDGE, CLIFF_DIP, CLIFF_DIP_AZIMUTH, CLIFF_WAVINESS, CLIFF_WAVINESS_LENGTH,
        CLIFF_JOINT_SPACING, CLIFF_JOINT_WIDTH, CLIFF_JOINT_DEPTH, CLIFF_JOINT_AZIMUTH, CLIFF_BUTTRESS_DEPTH,
        CLIFF_BUTTRESS_LENGTH);

    private TerrainSettings() {
    }

    /** The surface's procedural detail and curvature as {@code settings} say. */
    public static SurfaceParameters surface(EngineSettings settings) {
        return new SurfaceParameters(settings.get(DETAIL_AMPLITUDE), settings.get(DETAIL_WAVELENGTH),
            settings.get(DETAIL_OCTAVES), settings.get(DETAIL_EROSION), settings.get(CURVATURE), cliffs(settings));
    }

    /** The cliff relief as {@code settings} say ({@link CliffParameters#NONE} with it off). */
    public static CliffParameters cliffs(EngineSettings settings) {
        if (!settings.get(CLIFFS)) {
            return CliffParameters.NONE;
        }
        float start = settings.get(CLIFF_SLOPE_START);
        return new CliffParameters(start, Math.max(settings.get(CLIFF_SLOPE_END), start + 0.5),
            settings.get(CLIFF_SMOOTHING), settings.get(CLIFF_BED), settings.get(CLIFF_BED_VARIATION), settings.get(CLIFF_LEDGE),
            settings.get(CLIFF_DIP), settings.get(CLIFF_DIP_AZIMUTH), settings.get(CLIFF_WAVINESS),
            settings.get(CLIFF_WAVINESS_LENGTH), settings.get(CLIFF_JOINT_SPACING), settings.get(CLIFF_JOINT_WIDTH),
            settings.get(CLIFF_JOINT_DEPTH), settings.get(CLIFF_JOINT_AZIMUTH), settings.get(CLIFF_BUTTRESS_DEPTH),
            settings.get(CLIFF_BUTTRESS_LENGTH), settings.get(CLIFF_RELIEF));
    }
}
