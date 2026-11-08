package com.truesim.effects.terrain;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Where the film's level switches show ({@code TRUESIM_SCREENSHOTS=true}): the films' flight at 640 x 360, each frame
 * drawn twice at the same camera, with the tiles before the terrain's update and after; their difference, by blocks of
 * 40 x 40 pixels, is what the switches alone change. Prints the frames whose switches change a block by more than the
 * camera's own motion from frame to frame does, the tiles that came and went, and tiles shown, hidden and shown again.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class LodSwitchAnalysis {

    private static final int W = 640;
    private static final int H = 360;
    private static final int B = 40;

    static double[] blocks(float[] rgb) {
        int bw = W / B;
        int bh = H / B;
        double[] out = new double[bw * bh];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                double l = TerrainFilms.tone(0.2126 * rgb[3 * i] + 0.7152 * rgb[3 * i + 1] + 0.0722 * rgb[3 * i + 2]);
                out[(y / B) * bw + x / B] += l;
            }
        }
        for (int k = 0; k < out.length; k++) {
            out[k] /= B * B;
        }
        return out;
    }

    static double maxDiff(double[] a, double[] b) {
        double m = 0;
        for (int k = 0; k < a.length; k++) {
            m = Math.max(m, Math.abs(a[k] - b[k]));
        }
        return m;
    }

    @Test
    void switchesOfTheFlight() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).isNotNull();
        try (TestGpu gpu = TestGpu.open()) {
            TerrainFilms.Sky sky = TerrainFilms.Sky.of(gpu, W, H);
            try (TerrainRig rig = sky.rig()) {
                Terrain terrain = rig.terrain(TerrainFilms.load(rig), TerrainFilms.alpine().ref());
                rig.fov(Math.toRadians(55));
                TerrainFilms.place(rig, terrain, 0);
                rig.settle(400);
                rig.graph.settings().set(TerrainFilms.EV100, 11.5f);
                Set<Long> shown = new HashSet<>();
                terrain.forEachShown(shown::add);
                Map<Long, Integer> hiddenAt = new HashMap<>();
                double[] previous = null;
                List<Double> motion = new ArrayList<>();
                List<String> events = new ArrayList<>();
                int toggles = 0;
                int frames = 26 * 30;
                for (int f = 0; f <= frames; f++) {
                    TerrainFilms.place(rig, terrain, f / 30.0);
                    rig.frame(false);
                    double[] before = blocks(rig.rgb());
                    rig.frame(true);
                    double[] after = blocks(rig.rgb());
                    Set<Long> now = new HashSet<>();
                    terrain.forEachShown(now::add);
                    int added = 0;
                    int removed = 0;
                    int[] addedLevels = new int[9];
                    for (long k : now) {
                        if (!shown.contains(k)) {
                            added++;
                            addedLevels[TerrainGrid.level(k)]++;
                            Integer h = hiddenAt.get(k);
                            if (h != null && f - h < 60) {
                                toggles++;
                                events.add(String.format(Locale.ROOT, "frame %d: tile L%d (%d, %d) shown again %d"
                                    + " frames after it went", f, TerrainGrid.level(k), TerrainGrid.x(k),
                                    TerrainGrid.z(k), f - h));
                            }
                        }
                    }
                    for (long k : shown) {
                        if (!now.contains(k)) {
                            removed++;
                            hiddenAt.put(k, f);
                        }
                    }
                    shown = now;
                    double pop = maxDiff(before, after);
                    if (previous != null && added == 0 && removed == 0) {
                        motion.add(maxDiff(previous, before));
                    }
                    if (added + removed > 0) {
                        events.add(String.format(Locale.ROOT, "frame %d (%.2f s): +%d -%d tiles (added by level %s),"
                            + " switch change %.1f of 255 in the worst block", f, f / 30.0, added, removed,
                            java.util.Arrays.toString(addedLevels), pop));
                    }
                    previous = after;
                }
                motion.sort(null);
                double steady = motion.get((int) (motion.size() * 0.95));
                System.out.printf(Locale.ROOT, "ANALYSIS steady motion: worst block change frame to frame, p95 %.1f, median"
                    + " %.1f; toggles %d%n", steady, motion.get(motion.size() / 2), toggles);
                for (String e : events) {
                    System.out.println("ANALYSIS " + e);
                }
            }
        }
    }
}
