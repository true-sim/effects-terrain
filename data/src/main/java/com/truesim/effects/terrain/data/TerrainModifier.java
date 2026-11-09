package com.truesim.effects.terrain.data;

/**
 * Something that changes the terrain where it lies: its height (a river's bed, a road's cut and fill, a levelled
 * square) and the surface materials laid into the terrain's own material (gravel bars, cobbles, a dirt track), with
 * soft shoulders. Modifiers compose in order ({@link TerrainModifiers}): each gets the height the ones before left,
 * and each one's materials lie over the earlier ones' as far as it covers. The tiles, the CPU queries
 * ({@link TerrainSurface#heightAt}, {@link TerrainSurface#materials}) and everything placed from them read the
 * modified surface, so what stands on the terrain stands where it is drawn.
 *
 * <pre>{@code
 * TerrainModifier bed = new TerrainModifier() {
 *     public double height(double x, double z, double footprint, double height) {
 *         return water.carve(x, z, height);                 // never raises it
 *     }
 *     public double materials(double x, double z, double footprint, double[] cover) {
 *         double bare = 1 - water.grass(x, z);
 *         cover[TerrainModifier.GRAVEL] = bare;
 *         return bare;
 *     }
 * };
 * TerrainSurface surface = new TerrainSurface(site, parameters, TerrainModifiers.of(bed, roads));
 * }</pre>
 *
 * <p>Implementations must be pure (the same point always gives the same answer, whatever was asked before) and
 * thread-safe: tiles build on several threads. They are sampled at every tile vertex of every level, the footprint
 * the spacing of that level's grid: a feature narrower than the footprint should fade (or widen) rather than alias.
 */
public interface TerrainModifier {

    /** Gravel: river beds, gravel bars and banks, gravel roads and yards. */
    int GRAVEL = 0;
    /** Cobbles: paved village streets and squares. */
    int COBBLES = 1;
    /** Dirt: tracks, paths, trodden ground. */
    int DIRT = 2;
    /** Sand and silt: river margins, flood deposits. */
    int SAND = 3;
    /** How many surface materials there are. */
    int MATERIALS = 4;

    /**
     * The height at ({@code x}, {@code z}) given {@code height}, what the surface and the modifiers before this one
     * make it there, metres in the frame the tiles are drawn in (without the curvature's fall), sampled at
     * {@code footprint} metres. By default unchanged.
     */
    default double height(double x, double z, double footprint, double height) {
        return height;
    }

    /**
     * The surface materials this modifier lays at ({@code x}, {@code z}): each one's cover, 0 to 1 and summing to the
     * total returned (at most 1), written into {@code cover} ({@link #MATERIALS} entries, all 0 on entry). By default
     * none: 0.
     */
    default double materials(double x, double z, double footprint, double[] cover) {
        return 0;
    }

    /** Where it changes anything (null: anywhere); outside, it is not asked. */
    default Bounds bounds() {
        return null;
    }

    /**
     * A rectangle of the local frame (x east, z south, metres).
     *
     * @param x0 the west edge
     * @param z0 the north edge
     * @param x1 the east edge
     * @param z1 the south edge
     */
    record Bounds(double x0, double z0, double x1, double z1) {

        public Bounds {
            if (!(x1 >= x0) || !(z1 >= z0)) {
                throw new IllegalArgumentException("Bounds from west-north to east-south: " + x0 + ", " + z0 + ", "
                    + x1 + ", " + z1);
            }
        }

        /** Whether ({@code x}, {@code z}) lies within. */
        public boolean contains(double x, double z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }
}
