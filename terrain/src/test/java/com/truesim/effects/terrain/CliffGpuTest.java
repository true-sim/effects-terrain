package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.ShadowSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cliff relief in the tiles' meshes on the GPU, on a synthetic wall of 300 m: seen along the wall from its foot,
 * where tiles of several levels meet on it refined and moved, not one pixel of the background shows through (forward
 * shading and the visibility buffer); under a sun grazing the wall, the relief's overhangs and joints shadow the wall
 * below them, by the cascades (which pancake the casters towards the sun) and by rays (the moved tiles are what the
 * acceleration structures hold): the sun's shadows darken many pixels of the wall with the relief, few of it without.
 */
class CliffGpuTest {

    static EngineSettings settings(boolean relief) {
        EngineSettings settings = new EngineSettings();
        settings.set(TerrainSettings.TILE_SIZE, 32f);
        settings.set(TerrainSettings.TILE_QUADS, 64);
        settings.set(TerrainSettings.LEVELS, 6);
        settings.set(TerrainSettings.RANGE, 2_000f);
        settings.set(TerrainSettings.SCREEN_ERROR, 2f);
        settings.set(TerrainSettings.BUILD_BUDGET, 4096);
        settings.set(TerrainSettings.CLIFFS, relief);
        return settings;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRefinedWallShowsNoGap(boolean visibilityBuffer) throws Exception {
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, 192, 128, visibilityBuffer, false, settings(true))) {
            rig.output();
            Terrain terrain = rig.terrain(TestSites.wall(), TerrainSeamsTest.plain().ref());
            // from the floor 70 m out from the wall's foot, along it and up it: the wall fills the view
            rig.fov(Math.toRadians(50));
            rig.look(70, 930, 13.7, -1, -0.12, 0.75);
            rig.settle(400);
            int[] argb = rig.argb();
            rig.save("cliff-wall-" + (visibilityBuffer ? "vb" : "forward"), argb);
            int[] refined = new int[1];
            int grid = (terrain.grid().quads() + 1) * (terrain.grid().quads() + 1);
            int[] levels = new int[terrain.grid().levels()];
            terrain.forEachShown(key -> {
                TileMeshes.Info info = terrain.meshes().info(key);
                refined[0] += info != null && info.vertices() > grid ? 1 : 0;
                levels[TerrainGrid.level(key)]++;
            });
            System.out.println("levels " + java.util.Arrays.toString(levels) + ", refined tiles " + refined[0]
                + ", background " + rig.background(argb));
            assertThat(refined[0]).as("refined tiles shown").isGreaterThan(2);
            assertThat(levels[0] > 0 && levels[1] > 0).as("levels meeting on the wall: %s",
                java.util.Arrays.toString(levels)).isTrue();
            assertThat(rig.background(argb)).as("background pixels through the wall").isZero();
        }
    }

    enum Method { CASCADES, RAY_TRACED }

    /** The wall under a grazing sun, with or without the relief and the sun's shadows. */
    private static int[] view(Method method, boolean relief, boolean shadowed) throws Exception {
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, 192, 128, false, false, settings(relief))) {
            if (method == Method.RAY_TRACED) {
                assumeThat(rig.device.rayTracing()).as("ray tracing on this GPU").isTrue();
                assumeThat(System.getenv(com.truesim.engine.vulkan.VulkanInstance.GPU_ASSISTED_VARIABLE))
                    .as("the engine's NaN edge rays under GPU-assisted validation (docs/pitfalls.md)")
                    .isNotEqualTo("true");
            }
            // the sun from the east, high: it grazes the east-facing wall from above
            rig.lights.directional(0, -0.45f, -0.88f, 0.15f, 3, 3, 2.8f);
            RayTracing rays = null;
            if (shadowed) {
                if (method == Method.RAY_TRACED) {
                    rays = rig.closing(new RayTracing(rig.device, rig.pool, 4096));
                    rig.resources.rayTracing(rays);
                    rig.lights.shadow(0, ShadowSpec.rayTraced());
                } else {
                    rig.lights.shadow(0, ShadowSpec.cascades(4, 2048, 1_500));
                }
                Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights));
                if (rays != null) {
                    shadows.rayTracing(rays);
                }
                rig.port.add(shadows.view(rig.view));
            }
            rig.output();
            rig.terrain(TestSites.wall(), TerrainSeamsTest.plain().ref());
            rig.fov(Math.toRadians(40));
            rig.look(45, 950, 9.1, -1, 0, 0.12);
            rig.settle(400);
            for (int f = 0; f < 6; f++) { // the bottom levels build in frames after their meshes arrive
                rig.frame();
            }
            int[] argb = rig.argb();
            rig.save("cliff-shadows-" + method + (relief ? "" : "-flat") + (shadowed ? "" : "-none"), argb);
            return argb;
        }
    }

    /** How many pixels the sun's shadows darken by more than a third. */
    private static int darkened(Method method, boolean relief) throws Exception {
        int[] lit = view(method, relief, false);
        int[] shadowed = view(method, relief, true);
        int count = 0;
        for (int i = 0; i < lit.length; i++) {
            if (luma(shadowed[i]) < 0.67 * luma(lit[i])) {
                count++;
            }
        }
        return count;
    }

    private static int luma(int p) {
        return ((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff);
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void theReliefShadowsTheWall(Method method) throws Exception {
        int flat = darkened(method, false);
        int relief = darkened(method, true);
        System.out.println(method + ": pixels the shadows darken on the flat wall " + flat + ", with the relief "
            + relief);
        assertThat(relief).as("pixels the relief's shadows darken, against %d on the flat wall", flat)
            .isGreaterThan(flat + 192 * 128 / 40);
    }
}
