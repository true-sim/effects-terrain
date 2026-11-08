package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.ShadowSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Shadows and rays see the terrain: the mountains, lit by a low sun, cast shadows across the valleys by cascaded
 * shadow maps and by ray-traced shadows (the tiles' meshes each a bottom level of ray tracing's top level, which holds
 * every tile shown), darkening ground the sun alone would light. Validated.
 */
class TerrainShadowsTest {

    enum Method { CASCADES, RAY_TRACED }

    /** Dark pixels of a view along the valley under a low sun, with the sun's shadows by {@code spec} (null: none). */
    private static int dark(Method method, boolean shadowed, int[] traced) throws Exception {
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, 192, 128, false, false, TerrainSeamsTest.small())) {
            if (method == Method.RAY_TRACED) {
                assumeThat(rig.device.rayTracing()).as("ray tracing on this GPU").isTrue();
            }
            rig.lights.directional(0, -0.96f, -0.28f, 0.0f, 3, 3, 2.8f);
            RayTracing rays = null;
            if (shadowed) {
                if (method == Method.RAY_TRACED) {
                    rays = rig.closing(new RayTracing(rig.device, rig.pool, 4096));
                    rig.resources.rayTracing(rays);
                    rig.lights.shadow(0, ShadowSpec.rayTraced());
                } else {
                    rig.lights.shadow(0, ShadowSpec.cascades(4, 1024, 12_000));
                }
                Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights));
                if (rays != null) {
                    shadows.rayTracing(rays);
                }
                rig.port.add(shadows.view(rig.view));
            }
            rig.output();
            Terrain terrain = rig.terrain(TestSites.rugged(900), TerrainSeamsTest.plain().ref());
            // Down across the valleys, no sky in view: the engine's traced masks rebuild a normal at the horizon from a
            // sky neighbour (depth 0) into NaN, which GPU-assisted validation reports (docs/pitfalls.md)
            rig.look(0, terrain.heightAt(0, 0) + 600, 0, 0.3, -0.75, -1);
            rig.settle(400);
            for (int f = 0; f < 6; f++) { // the bottom levels build in frames after their meshes arrive
                rig.frame();
            }
            int[] argb = rig.argb();
            rig.save("shadows-" + method + (shadowed ? "" : "-none"), argb);
            if (rays != null) {
                traced[0] = rays.topLevelInstances(rig.gpuScene);
                traced[1] = terrain.shownTiles();
            }
            int dark = 0;
            for (int p : argb) {
                int luma = ((p >> 16) & 0xff) + ((p >> 8) & 0xff) + (p & 0xff);
                if (luma < 3 * 18) {
                    dark++;
                }
            }
            return dark;
        }
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void mountainsShadowTheValleysUnderALowSun(Method method) throws Exception {
        int[] traced = new int[2];
        int lit = dark(method, false, traced);
        int shadowed = dark(method, true, traced);
        assertThat(shadowed).as("dark pixels with the sun's shadows, against %d without", lit)
            .isGreaterThan(lit + 192 * 128 / 20);
        if (method == Method.RAY_TRACED) {
            assertThat(traced[0]).as("instances in the top level: every tile shown").isEqualTo(traced[1]);
        }
    }
}
