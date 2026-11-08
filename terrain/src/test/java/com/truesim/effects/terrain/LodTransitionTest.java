package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Level switches do not flicker: along a slow, low flight over synthetic mountains, each frame is drawn twice at the
 * same camera, with the tiles before the terrain's update and after; their difference, by blocks of 16 x 16 pixels,
 * is what switching levels changes in that frame. With tiles morphing from one level's surface to the other over
 * {@code terrain.morphSeconds} (and the material's layers following a slope and curvature the same at every level),
 * no block changes more in a frame than the camera's own motion changes blocks from frame to frame (its 95th
 * percentile over the flight). As the terrain
 * was (switching at once, {@code terrain.morphSeconds} 0, each level's layers following its own slope and curvature,
 * {@code terrain.layerScale} below every spacing) switches change blocks by more than that within a frame: the
 * flicker this guards against. Validated.
 */
class LodTransitionTest {

    private static final int W = 128;
    private static final int H = 96;
    private static final int B = 16;

    record Flight(double worstSwitch, double steady, int switches) {
    }

    /** Mean absolute difference of each block of {@code a} and {@code b} (RGB, 0 to 255), the largest. */
    static double worstBlock(int[] a, int[] b) {
        double worst = 0;
        for (int by = 0; by < H; by += B) {
            for (int bx = 0; bx < W; bx += B) {
                double sum = 0;
                for (int y = by; y < by + B; y++) {
                    for (int x = bx; x < bx + B; x++) {
                        int p = a[y * W + x];
                        int q = b[y * W + x];
                        for (int c = 0; c < 24; c += 8) {
                            sum += Math.abs(((p >> c) & 0xff) - ((q >> c) & 0xff));
                        }
                    }
                }
                worst = Math.max(worst, sum / (3.0 * B * B));
            }
        }
        return worst;
    }

    static Flight fly(float morphSeconds, float layerScale) throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        settings.set(TerrainSettings.MORPH_SECONDS, morphSeconds);
        settings.set(TerrainSettings.LAYER_SCALE, layerScale);
        settings.set(TerrainSettings.FOLLOW_STEP, 0f);
        try (TestGpu gpu = TestGpu.open(); TerrainRig rig = new TerrainRig(gpu, W, H, false, false, settings)) {
            rig.output();
            Terrain terrain = rig.terrain(TestSites.rugged(900), TerrainSeamsTest.plain().ref());
            double z = 21.7;
            rig.look(0, terrain.heightAt(0, z) + 60, z, 1, -0.45, 0.1);
            rig.settle(400);
            List<Double> motion = new ArrayList<>();
            double worstSwitch = 0;
            int switches = 0;
            int[] previous = null;
            for (int f = 1; f <= 320; f++) {
                double x = 1.5 * f; // 45 m/s at 30 frames a second, 60 m above the ground
                rig.look(x, terrain.heightAt(x, z) + 60, z, 1, -0.45, 0.1);
                rig.frame(false);
                int[] before = rig.argb();
                int shownBefore = terrain.shownTiles();
                int transitionsBefore = terrain.transitioning();
                rig.frame(true);
                int[] after = rig.argb();
                double change = worstBlock(before, after);
                if (Boolean.getBoolean("truesim.popDebug") && change > 4) {
                    System.out.printf("POP frame %d change %.1f: shown %d -> %d, transitions %d -> %d%n", f, change,
                        shownBefore, terrain.shownTiles(), transitionsBefore, terrain.transitioning());
                    rig.save("pop-" + morphSeconds + "-" + layerScale + "-" + f + "-before", before);
                    rig.save("pop-" + morphSeconds + "-" + layerScale + "-" + f + "-after", after);
                }
                if (terrain.shownTiles() != shownBefore || terrain.transitioning() + transitionsBefore > 0
                    || change > 0) {
                    switches += change > 0 ? 1 : 0;
                    worstSwitch = Math.max(worstSwitch, change);
                }
                if (previous != null) {
                    motion.add(worstBlock(previous, before));
                }
                previous = after;
            }
            motion.sort(null);
            return new Flight(worstSwitch, motion.get((int) (motion.size() * 0.95)), switches);
        }
    }

    @Test
    void levelSwitchesChangeNoBlockMoreThanTheCamerasMotion() throws Exception {
        // The layers' scale at the defaults' ratio to the finest spacing (16 m over 2 m): 64 m over these tiles' 8 m
        Flight morphing = fly(TerrainSettings.MORPH_SECONDS.defaultValue(), 64);
        assertThat(morphing.switches()).as("frames whose switching changed something").isPositive();
        assertThat(morphing.worstSwitch()).as("the worst block's change from switching in a frame, against the camera"
            + " motion's %.1f (the worst block's change from frame to frame, 95th percentile)", morphing.steady())
            .isLessThanOrEqualTo(morphing.steady());
        // As before: switching at once, each level's layers following its own slope and curvature (a scale below
        // every spacing)
        Flight instant = fly(0, 1);
        assertThat(instant.worstSwitch()).as("switching at once with each level's own layers: the worst block's change"
            + " in a frame, against the camera motion's %.1f", instant.steady()).isGreaterThan(instant.steady());
    }
}
