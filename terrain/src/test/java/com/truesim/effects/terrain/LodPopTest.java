package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import org.junit.jupiter.api.Test;

/**
 * Level transitions do not pop beyond tolerance: for every tile shown, what replacing it by its finer tiles would move
 * on screen (the largest distance between the two levels' surfaces over the tile, along the normal, projected from the
 * camera at that point) stays within the screen-space error (and the world's hysteresis) plus a pixel. The levels'
 * errors must be what the streaming assumes (measured, {@link TerrainError}): taken for ten times smaller than they
 * are ({@code terrain.errorScale} 0.1), the world keeps coarse tiles too close and their switch jumps several pixels,
 * which this test catches. And the image itself: drawn with
 * half the error (finer tiles everywhere) it changes in only a few pixels.
 */
class LodPopTest {

    /** The allowed error (2 px), past which a split level stays split by the world's hysteresis (15 %), and a pixel. */
    static final double TOLERANCE = 2 * 1.15 + 1;

    /** The largest on-screen pop, pixels, of the shown tiles' switch to their finer tiles. */
    static double worstPop(TerrainRig rig, Terrain terrain) {
        TerrainGrid grid = terrain.grid();
        TerrainSurface surface = terrain.surface();
        double cx = rig.camera.position(0);
        double cy = rig.camera.position(1);
        double cz = rig.camera.position(2);
        double projection = rig.height / (2 * Math.tan(rig.fovY() / 2));
        double[] worst = {0};
        terrain.forEachShown(key -> {
            int level = TerrainGrid.level(key);
            if (level == 0) {
                return;
            }
            double size = grid.size(level);
            double coarse = grid.spacing(level);
            double fine = grid.spacing(level - 1);
            double x0 = TerrainGrid.x(key) * size;
            double z0 = TerrainGrid.z(key) * size;
            int steps = (int) Math.round(size / fine);
            for (int j = 0; j <= steps; j++) {
                for (int i = 0; i <= steps; i++) {
                    if (((i | j) & 1) == 0) {
                        continue; // the coarse vertices: both levels sample them (but for the octaves left out)
                    }
                    double x = x0 + i * fine;
                    double z = z0 + j * fine;
                    double now = surface.heightAt(x, z, coarse);
                    double then = surface.height(x, z, fine);
                    double gx = (surface.height(x + fine, z, fine) - surface.height(x - fine, z, fine)) / (2 * fine);
                    double gz = (surface.height(x, z + fine, fine) - surface.height(x, z - fine, fine)) / (2 * fine);
                    double along = Math.abs(now - then) / Math.sqrt(1 + gx * gx + gz * gz); // along the normal
                    double dx = x - cx;
                    double dy = now - cy;
                    double dz = z - cz;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double pop = along * projection / distance;
                    if (pop > worst[0] && Boolean.getBoolean("truesim.popDebug")) {
                        System.out.printf("pop %.2f px at level %d (%.0f, %.0f), %.1f m along the normal, %.0f m away,"
                            + " the level's measured error %.1f m, band %.0f m%n", pop, level, x, z, along, distance,
                            terrain.levelError(level), terrain.band(level - 1));
                    }
                    worst[0] = Math.max(worst[0], pop);
                }
            }
        });
        return worst[0];
    }

    private static double popAt(TerrainSite site, EngineSettings settings, double height, int[] changed)
        throws Exception {
        try (TestGpu gpu = TestGpu.open(); TerrainRig rig = new TerrainRig(gpu, 192, 128, false, false, settings)) {
            rig.output();
            Terrain terrain = rig.terrain(site, TerrainSeamsTest.plain().ref());
            rig.look(0, terrain.heightAt(0, 0) + height, 0, 0.2, -0.45, -1);
            rig.settle(600);
            double pop = worstPop(rig, terrain);
            if (changed != null) {
                int[] before = rig.argb();
                settings.set(TerrainSettings.SCREEN_ERROR, settings.get(TerrainSettings.SCREEN_ERROR) / 2);
                rig.settle(600);
                int[] after = rig.argb();
                for (int i = 0; i < before.length; i++) {
                    int d = 0;
                    for (int c = 0; c < 24; c += 8) {
                        d = Math.max(d, Math.abs(((before[i] >> c) & 0xff) - ((after[i] >> c) & 0xff)));
                    }
                    if (d > 32) {
                        changed[0]++;
                    }
                }
            }
            return pop;
        }
    }

    @Test
    void levelSwitchesMoveTheSurfaceByNoMoreThanTheScreenErrorAndAPixel() throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        int[] changed = {0};
        double pop = popAt(TestSites.rugged(900), settings, 300, changed);
        assertThat(pop).as("worst pop, pixels").isLessThanOrEqualTo(TOLERANCE);
        assertThat(changed[0]).as("pixels changing much at half the error, of %d", 192 * 128)
            .isLessThan(192 * 128 / 50);
    }

    @Test
    void tilesTakenForTenTimesFinerThanTheyArePopVisibly() throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        settings.set(TerrainSettings.ERROR_SCALE, 0.1f);
        double pop = popAt(TestSites.rugged(900), settings, 300, null);
        assertThat(pop).as("worst pop, pixels, with the error understated").isGreaterThan(TOLERANCE);
    }

    @Test
    void theRealValleyPopsWithinTheTolerance() throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        EngineSettings settings = new EngineSettings();
        settings.set(TerrainSettings.RANGE, 20_000f);
        settings.set(TerrainSettings.BUILD_BUDGET, 4096);
        double pop = popAt(site, settings, 150, null);
        assertThat(pop).as("worst pop over Lauterbrunnen, pixels").isLessThanOrEqualTo(TOLERANCE);
    }
}
