package com.truesim.effects.terrain;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.ShadowSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What the terrain costs ({@code TRUESIM_BENCHMARK=true}): Lauterbrunnen at 1920 x 1080 through the visibility buffer
 * and forward shading, the sun's four cascades, the defaults (128 m tiles of 64 quads, 9 levels, 2 px, 120 km), from
 * the valley floor and from 3,500 m looking to the horizon; GPU time per frame and by pass (the frame graph's
 * timestamps), the tiles and triangles shown; then the films' flight in real time (30 frames a second of camera
 * motion), the main thread's world, terrain, scene and sync updates per frame, tiles waiting; and ray
 * tracing's bottom levels. Medians over 120 frames after 60.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class TerrainBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    private static double median(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length == 0 ? Double.NaN : v[v.length / 2];
    }

    private static double percentile(List<Double> values, double p) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length == 0 ? Double.NaN : v[Math.min(v.length - 1, (int) (v.length * p))];
    }

    /** A still view: settles, then times 120 frames. */
    private static void still(String name, boolean visibilityBuffer, double x, double y, double z, double dx,
        double dy, double dz, boolean aboveGround) throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, WIDTH, HEIGHT, visibilityBuffer, true, TerrainFilms.settings())) {
            rig.lights.shadow(0, ShadowSpec.cascades(4, 2048, 20_000));
            Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights));
            rig.port.add(shadows.view(rig.view));
            rig.output();
            Terrain terrain = rig.terrain(site, TerrainFilms.alpine().ref());
            double cy = aboveGround ? terrain.heightAt(x, z) + y : y;
            rig.look(x, cy, z, dx, dy, dz);
            rig.settle(600);
            rig.graph.timings(true);
            List<Double> frames = new ArrayList<>();
            Map<String, List<Double>> passes = new TreeMap<>();
            for (int f = 0; f < 180; f++) {
                rig.frame();
                GpuTimings timings = rig.graph.timings();
                if (f < 60 || timings == null) {
                    continue;
                }
                frames.add(timings.spanMillis());
                for (int i = 0; i < timings.count(); i++) {
                    if (!Double.isNaN(timings.millis(i))) {
                        passes.computeIfAbsent(timings.pass(i), k -> new ArrayList<>()).add(timings.millis(i));
                    }
                }
            }
            int[] shown = new int[terrain.grid().levels()];
            for (int level = 0; level < shown.length; level++) {
                shown[level] = terrain.shownTiles(level);
            }
            System.out.printf(Locale.ROOT, "BENCH %s, %s: GPU frame %.3f ms (p95 %.3f), %d tiles shown %s,"
                + " %d meshlets drawn%n", name, visibilityBuffer ? "visibility buffer" : "forward", median(frames),
                percentile(frames, 0.95), terrain.shownTiles(), Arrays.toString(shown), rig.view.view()
                    .visibleMeshlets());
            for (Map.Entry<String, List<Double>> pass : passes.entrySet()) {
                double m = median(pass.getValue());
                if (m >= 0.01) {
                    System.out.printf(Locale.ROOT, "BENCH   %s: %.3f ms%n", pass.getKey(), m);
                }
            }
        }
    }

    @Test
    void stillViews() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        for (boolean vb : new boolean[] {true, false}) {
            still("valley floor, up the valley", vb, -120, 25, 500, 0.05, 0.03, 1, true);
            still("3,500 m, to the horizon", vb, 4000, 3500, -1000, 0.4, -0.12, -1, false);
        }
    }

    @Test
    void flightInRealTime() throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, WIDTH, HEIGHT, true, false, TerrainFilms.settings())) {
            rig.output();
            Terrain terrain = rig.terrain(site, TerrainFilms.alpine().ref());
            rig.fov(Math.toRadians(55));
            TerrainFilms.place(rig, terrain, 0);
            rig.settle(600);
            rig.graph.timings(true);
            List<Double> update = new ArrayList<>();
            List<Double> worldUpdate = new ArrayList<>();
            List<Double> terrainUpdate = new ArrayList<>();
            List<Double> sceneSync = new ArrayList<>();
            List<Double> gpuFrames = new ArrayList<>();
            int maxPending = 0;
            int maxTransitions = 0;
            int maxShown = 0;
            long start = System.nanoTime();
            int frames = 26 * 30;
            for (int f = 0; f <= frames; f++) {
                TerrainFilms.place(rig, terrain, f / 30.0);
                long t0 = System.nanoTime();
                int slot = rig.graph.beginFrame();
                rig.slots.begin(slot);
                rig.tasks.drainCompleted();
                rig.resources.beginFrame();
                long t1 = System.nanoTime();
                terrain.follow(rig.camera.position(0), rig.camera.position(1), rig.camera.position(2), rig.fovY(),
                    HEIGHT);
                long tw = System.nanoTime();
                rig.world.update();
                long tt = System.nanoTime();
                terrain.update(1 / 30.0);
                long t2 = System.nanoTime();
                rig.scene.update();
                rig.sync.update();
                long t3 = System.nanoTime();
                worldUpdate.add((tt - tw) / 1e6);
                terrainUpdate.add((t2 - tt) / 1e6);
                sceneSync.add((t3 - t2) / 1e6);
                rig.view.set(1, com.truesim.engine.render.MeshletRenderer.MODE_LIT);
                rig.graph.submit(0, 0);
                rig.slots.end();
                update.add((t2 - t1) / 1e6);
                GpuTimings timings = rig.graph.timings();
                if (timings != null) {
                    gpuFrames.add(timings.spanMillis());
                }
                maxPending = Math.max(maxPending, terrain.pendingTiles());
                maxTransitions = Math.max(maxTransitions, terrain.transitioning());
                maxShown = Math.max(maxShown, terrain.shownTiles());
                // wait out the frame's remaining 33 ms, as a game paced at 30 fps would
                long wait = 33_333_333L - (System.nanoTime() - t0);
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            }
            System.out.printf(Locale.ROOT, "BENCH flight (%.1f s): terrain + world update median %.3f ms, p99 %.3f ms,"
                + " max %.3f ms (world %.3f / p99 %.3f, terrain %.3f / p99 %.3f; scene and sync after %.3f / p99 %.3f);"
                + " GPU frame median %.3f ms, p99 %.3f; up to %d tiles shown, up to %d waiting for their mesh, up to %d"
                + " level transitions at once%n",
                (System.nanoTime() - start) / 1e9, median(update), percentile(update, 0.99), percentile(update, 1),
                median(worldUpdate), percentile(worldUpdate, 0.99), median(terrainUpdate),
                percentile(terrainUpdate, 0.99), median(sceneSync), percentile(sceneSync, 0.99), median(gpuFrames),
                percentile(gpuFrames, 0.99), maxShown, maxPending, maxTransitions);
        }
    }

    @Test
    void bottomLevels() throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, WIDTH, HEIGHT, true, false, TerrainFilms.settings())) {
            assumeThat(rig.device.rayTracing()).isTrue();
            RayTracing rays = rig.closing(new RayTracing(rig.device, rig.pool, 4096));
            rig.resources.rayTracing(rays);
            rig.lights.shadow(0, ShadowSpec.rayTraced());
            Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights).rayTracing(rays));
            rig.port.add(shadows.view(rig.view));
            rig.output();
            Terrain terrain = rig.terrain(site, TerrainFilms.alpine().ref());
            rig.look(-120, terrain.heightAt(-120, 500) + 25, 500, 0.05, 0.03, 1);
            rig.settle(600);
            for (int f = 0; f < 20; f++) {
                rig.frame();
            }
            rig.graph.timings(true);
            List<Double> frames = new ArrayList<>();
            for (int f = 0; f < 120; f++) {
                rig.frame();
                GpuTimings timings = rig.graph.timings();
                if (f >= 30 && timings != null) {
                    frames.add(timings.spanMillis());
                }
            }
            System.out.printf(Locale.ROOT, "BENCH ray-traced sun, valley floor: GPU frame %.3f ms; %d tiles shown, %d"
                + " in the top level; bottom levels %.1f MiB (%.3f MiB a tile)%n", median(frames),
                terrain.shownTiles(), rays.topLevelInstances(rig.gpuScene), rays.bottomLevelBytes() / 1048576.0,
                rays.bottomLevelBytes() / 1048576.0 / Math.max(1, terrain.heldTiles()));
        }
    }
}
