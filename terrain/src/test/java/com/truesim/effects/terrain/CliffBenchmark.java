package com.truesim.effects.terrain;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.terrain.data.CliffParameters;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.ShadowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What the cliffs cost ({@code TRUESIM_BENCHMARK=true}): Lauterbrunnen's walls at 1920 x 1080 through the visibility
 * buffer, the sun's four cascades (and rays), in the cliff film's two views (the walls from the valley floor, along
 * the Staubbach wall 170 m out), in four configurations: as before (128 m tiles, no relief), the relief in the
 * heightfield and in the mesh on 32 m tiles (0.5 m), and the relief in the mesh on the default tiles. GPU time per
 * frame and of the passes that change (medians over 120 frames after 60), the tiles and vertices shown, how long the
 * view took to settle (the tiles' builds), and what one wall tile costs to build on the CPU at each level.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class CliffBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    enum Config { BEFORE, HEIGHT_32, MESH_32, MESH_128 }

    static EngineSettings settings(Config config) {
        EngineSettings settings = TerrainFilms.settings();
        settings.set(TerrainSettings.CLIFFS, config != Config.BEFORE);
        if (config == Config.HEIGHT_32) {
            settings.set(TerrainSettings.CLIFF_RELIEF, CliffParameters.Relief.HEIGHT);
        }
        if (config == Config.HEIGHT_32 || config == Config.MESH_32) {
            settings.set(TerrainSettings.TILE_SIZE, 32f);
            settings.set(TerrainSettings.LEVELS, 11);
        }
        return settings;
    }

    private static double median(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length == 0 ? Double.NaN : v[v.length / 2];
    }

    private static void view(Config config, CliffFilms.Shot shot, boolean rayTraced) throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, WIDTH, HEIGHT, true, true, settings(config))) {
            RayTracing rays = null;
            if (rayTraced) {
                assumeThat(rig.device.rayTracing()).isTrue();
                rays = rig.closing(new RayTracing(rig.device, rig.pool, 8192));
                rig.resources.rayTracing(rays);
                rig.lights.shadow(0, ShadowSpec.rayTraced());
            } else {
                rig.lights.shadow(0, ShadowSpec.cascades(4, 2048, 20_000));
            }
            Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights));
            if (rays != null) {
                shadows.rayTracing(rays);
            }
            rig.port.add(shadows.view(rig.view));
            rig.output();
            Terrain terrain = rig.terrain(site, TerrainFilms.alpine().ref());
            rig.fov(Math.toRadians(50));
            double[] p = shot.at(0.5);
            rig.look(p[0], p[1], p[2], p[3] - p[0], p[4] - p[1], p[5] - p[2]);
            long t0 = System.nanoTime();
            rig.settle(2000);
            double settle = (System.nanoTime() - t0) / 1e9;
            for (int f = 0; f < 20; f++) {
                rig.frame();
            }
            rig.graph.timings(true);
            List<Double> frames = new ArrayList<>();
            java.util.Map<String, List<Double>> passes = new java.util.TreeMap<>();
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
            long[] vertices = new long[1];
            int[] refined = new int[1];
            int grid = (terrain.grid().quads() + 1) * (terrain.grid().quads() + 1);
            terrain.forEachShown(key -> {
                TileMeshes.Info info = terrain.meshes().info(key);
                if (info != null) {
                    vertices[0] += info.vertices();
                    refined[0] += info.vertices() > grid ? 1 : 0;
                }
            });
            System.out.printf(Locale.ROOT, "BENCH %s, %s%s: GPU frame %.3f ms; %d tiles shown (%d refined), %.2f M"
                + " vertices, %d meshlets drawn; settled in %.1f s%s%n", shot.name(), config,
                rayTraced ? ", sun ray traced" : "", median(frames), terrain.shownTiles(), refined[0],
                vertices[0] / 1e6, rig.view.view().visibleMeshlets(), settle,
                rays == null ? "" : String.format(Locale.ROOT, "; bottom levels %.0f MiB",
                    rays.bottomLevelBytes() / 1048576.0));
            for (java.util.Map.Entry<String, List<Double>> pass : passes.entrySet()) {
                double m = median(pass.getValue());
                if (m >= 0.05) {
                    System.out.printf(Locale.ROOT, "BENCH   %s: %.3f ms%n", pass.getKey(), m);
                }
            }
        }
    }

    @Test
    void views() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        for (CliffFilms.Shot shot : CliffFilms.SHOTS) {
            for (Config config : Config.values()) {
                view(config, shot, false);
            }
        }
        view(Config.BEFORE, CliffFilms.FLIGHT, true);
        view(Config.MESH_32, CliffFilms.FLIGHT, true);
    }

    /** One wall tile's build on the CPU at each level, both modes (median of 5 after 2). */
    @Test
    void tileBuilds() throws Exception {
        TerrainSite site = TestSites.lauterbrunnen();
        assumeThat(site).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        for (Config config : new Config[] {Config.BEFORE, Config.HEIGHT_32, Config.MESH_32}) {
            EngineSettings settings = settings(config);
            TerrainSurface surface = new TerrainSurface(site, TerrainSettings.surface(settings));
            TerrainGrid grid = new TerrainGrid(settings.get(TerrainSettings.TILE_SIZE),
                settings.get(TerrainSettings.TILE_QUADS), settings.get(TerrainSettings.LEVELS));
            TileMeshes meshes = new TileMeshes(surface, grid, 0.25, 0.05).cliffs(CliffMesh.Settings.DEFAULTS);
            for (int level = 0; level < 4; level++) {
                double size = grid.size(level);
                // on the Staubbach wall
                long key = TerrainGrid.key(level, (int) Math.floor(-330 / size), (int) Math.floor(260 / size));
                List<Double> times = new ArrayList<>();
                int top = 0;
                for (int k = 0; k < 7; k++) {
                    long t0 = System.nanoTime();
                    top = meshes.geometry(key, -1).top();
                    if (k >= 2) {
                        times.add((System.nanoTime() - t0) / 1e6);
                    }
                }
                System.out.printf(Locale.ROOT, "BENCH tile build %s, level %d (%.0f m): %.1f ms, %d vertices%n", config,
                    level, size, median(times), top);
            }
        }
    }
}
