package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.terrain.data.CliffParameters;
import com.truesim.effects.terrain.data.TerrainSurface;

/**
 * How far each level's tiles stand off the finer level's surface, measured on the terrain itself: for sample tiles of
 * each level round the site, the largest distance between a tile's triangles and the next finer level's surface at
 * the finer vertices it lacks, along the surface's normal (a cliff a vertex further along moves sideways by about a
 * spacing, not by its whole height). The streaming switches a level when this, projected from the camera, reaches the
 * allowed screen-space error, so the switch moves the surface by no more than that.
 */
public final class TerrainError {

    private TerrainError() {
    }

    /**
     * The error of tile {@code key} of {@code grid} on {@code surface}, metres: the largest distance, along the finer
     * surface's normal (its slope by central differences at the finer spacing), between the tile's triangles and the
     * finer level's surface at every finer vertex. 0 at level 0.
     */
    @AllowAllocation("with a tile's build, in the background")
    public static double tile(TerrainSurface surface, TerrainGrid grid, long key) {
        int level = TerrainGrid.level(key);
        if (level == 0) {
            return 0;
        }
        double size = grid.size(level);
        double coarse = grid.spacing(level);
        double fine = grid.spacing(level - 1);
        double x0 = TerrainGrid.x(key) * size;
        double z0 = TerrainGrid.z(key) * size;
        int steps = 2 * grid.quads();
        int a = steps + 3; // the finer grid with an apron, for the slopes at its edges
        double[] h = new double[a * a];
        for (int j = 0; j < a; j++) {
            for (int i = 0; i < a; i++) {
                h[j * a + i] = surface.height(x0 + (i - 1) * fine, z0 + (j - 1) * fine, fine);
            }
        }
        // The coarse tile's vertices, for its triangles
        int n = grid.quads();
        double[] c = new double[(n + 1) * (n + 1)];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                c[j * (n + 1) + i] = surface.height(x0 + i * coarse, z0 + j * coarse, coarse);
            }
        }
        double worst = 0;
        for (int j = 0; j <= steps; j++) {
            for (int i = 0; i <= steps; i++) {
                if (((i | j) & 1) == 0) {
                    continue; // a coarse vertex: the triangles pass through it (the octaves left out aside)
                }
                // the coarse triangles at (i / 2, j / 2): split from the (+x, -z) corner to the (-x, +z) one
                int ci = Math.min(i / 2, n - 1);
                int cj = Math.min(j / 2, n - 1);
                double u = i / 2.0 - ci;
                double v = j / 2.0 - cj;
                double h00 = c[cj * (n + 1) + ci];
                double h10 = c[cj * (n + 1) + ci + 1];
                double h01 = c[(cj + 1) * (n + 1) + ci];
                double h11 = c[(cj + 1) * (n + 1) + ci + 1];
                double triangles = u + v <= 1 ? h00 + u * (h10 - h00) + v * (h01 - h00)
                    : h11 + (1 - u) * (h01 - h11) + (1 - v) * (h10 - h11);
                int at = (j + 1) * a + i + 1;
                double gx = (h[at + 1] - h[at - 1]) / (2 * fine);
                double gz = (h[at + a] - h[at - a]) / (2 * fine);
                worst = Math.max(worst, Math.abs(triangles - h[at]) / Math.sqrt(1 + gx * gx + gz * gz));
            }
        }
        return worst;
    }

    /**
     * {@link #tile(TerrainSurface, TerrainGrid, long)} with the cliffs' relief in the mesh ({@code MESH}) counted too:
     * how far the tile's relief (its beds faded to its vertices' spacing up the wall, its joints and buttresses to its
     * grid's) lies from the finest level's, at every finer vertex on a wall (against the finest: each level fades a
     * little more than the one below, and those little steps add up). So a wall's tiles split where their relief
     * would show coarser than the allowed pixels, as their heights do.
     */
    @AllowAllocation("with a tile's build, in the background")
    public static double tile(TerrainSurface surface, TerrainGrid grid, long key, CliffMesh.Settings cliffs) {
        double worst = tile(surface, grid, key);
        int level = TerrainGrid.level(key);
        CliffParameters c = surface.parameters().cliffs();
        if (level == 0 || c.relief() != CliffParameters.Relief.MESH || !c.any()) {
            return worst;
        }
        double size = grid.size(level);
        double coarse = grid.spacing(level);
        double fine = grid.spacing(level - 1);
        double x0 = TerrainGrid.x(key) * size;
        double z0 = TerrainGrid.z(key) * size;
        double coarseStep = level < cliffs.levels() ? cliffs.step(level) : -1;
        double finest = grid.spacing(0);
        double finestStep = cliffs.levels() > 0 ? cliffs.step(0) : -1;
        // walls anywhere? (a lattice of 33 a side: none on most tiles, which end here)
        boolean walls = false;
        for (int j = 0; j <= 32 && !walls; j++) {
            for (int i = 0; i <= 32 && !walls; i++) {
                walls = surface.cliffWeight(x0 + i * size / 32, z0 + j * size / 32, fine) > 0;
            }
        }
        if (!walls) {
            return worst;
        }
        int steps = 2 * grid.quads();
        double[] a = new double[7];
        double[] b = new double[7];
        for (int j = 0; j <= steps; j++) {
            for (int i = 0; i <= steps; i++) {
                double x = x0 + i * fine;
                double z = z0 + j * fine;
                if (surface.cliffPoint(x, z, finest, finestStep, b) <= 0) {
                    continue;
                }
                surface.cliffPoint(x, z, coarse, coarseStep, a);
                worst = Math.max(worst, Maths.hypot(a[0] - b[0], a[2] - b[2]));
            }
        }
        return worst;
    }

    /**
     * The typical error of each level of {@code grid} on {@code surface}, metres (level 0's is 0: the finest has no
     * finer to miss): the median of {@code tiles} tiles' ({@link #tile}), the origin's and tiles spread round it out to
     * where the level is drawn. Rough tiles past it (cliffs) are refined by their own error.
     */
    @AllowAllocation("measured once")
    public static double[] measure(TerrainSurface surface, TerrainGrid grid, double reach, int tiles) {
        double[] error = new double[grid.levels()];
        double[] samples = new double[tiles];
        for (int level = 1; level < grid.levels(); level++) {
            double size = grid.size(level);
            for (int t = 0; t < tiles; t++) {
                // the origin's tile, then tiles on a spiral of golden-angle steps out to where the level is drawn
                // (32 tiles out, at most the reach)
                double radius = Math.min(reach, 32 * size) * Math.sqrt((double) t / tiles);
                double angle = t * 2.399963229728653;
                int x = (int) Math.floor(radius * Maths.cos(angle) / size);
                int z = (int) Math.floor(radius * Maths.sin(angle) / size);
                samples[t] = tile(surface, grid, TerrainGrid.key(level, x, z));
            }
            java.util.Arrays.sort(samples);
            error[level] = samples[tiles / 2];
        }
        return error;
    }
}
