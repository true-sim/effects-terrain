package com.truesim.effects.terrain;

/**
 * The terrain's tiles: level 0's are {@code tileSize} metres wide, each level's twice the one below, every tile
 * {@code quads} quads a side; tile (x, z) of a level starts at (x, z) times its size in the local frame. Keys pack a
 * tile's level and indices into a long.
 *
 * @param tileSize the finest tiles' side, metres
 * @param quads quads along a tile's side
 * @param levels how many levels
 */
public record TerrainGrid(double tileSize, int quads, int levels) {

    /** Texture coordinates repeat every this many metres (tile origins are taken modulo it). */
    public static final double PLANAR_PERIOD = 16384;

    public TerrainGrid {
        if (!(tileSize > 0) || quads < 2 || levels < 1 || levels > 16) {
            throw new IllegalArgumentException("A grid of " + levels + " levels of " + tileSize + " m tiles, " + quads
                + " quads a side");
        }
        if (PLANAR_PERIOD % tileSize != 0 && tileSize % PLANAR_PERIOD != 0) {
            throw new IllegalArgumentException("Tiles' sides divide " + PLANAR_PERIOD + " m: " + tileSize);
        }
    }

    /** The side of level {@code level}'s tiles, metres. */
    public double size(int level) {
        return tileSize * (1L << level);
    }

    /** The spacing of level {@code level}'s vertices, metres. */
    public double spacing(int level) {
        return size(level) / quads;
    }

    /** The key of tile ({@code x}, {@code z}) of {@code level}. */
    public static long key(int level, int x, int z) {
        return ((long) level << 58) | ((x & 0x1fffffffL) << 29) | (z & 0x1fffffffL);
    }

    /** The level of tile {@code key}. */
    public static int level(long key) {
        return (int) (key >>> 58);
    }

    /** The x index of tile {@code key}. */
    public static int x(long key) {
        return (int) (((key >>> 29) & 0x1fffffffL) << 35 >> 35);
    }

    /** The z index of tile {@code key}. */
    public static int z(long key) {
        return (int) ((key & 0x1fffffffL) << 35 >> 35);
    }

    /** The key of the tile one level up holding tile {@code key}. */
    public static long parent(long key) {
        return key(level(key) + 1, Math.floorDiv(x(key), 2), Math.floorDiv(z(key), 2));
    }

    /** The key of child {@code child} (0 to 3: x then z) of tile {@code key}, one level down. */
    public static long child(long key, int child) {
        return key(level(key) - 1, 2 * x(key) + (child & 1), 2 * z(key) + (child >> 1));
    }
}
