package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.math.color.Color;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.effects.weather.snow.SimpleSnow;
import com.truesim.effects.weather.snow.SnowSettings;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import org.junit.jupiter.api.Test;

/**
 * effects-weather's snow (the surface shading hook {@code weather.snow}) lies on the terrain: the terrain material
 * refuses no hook, so at full coverage the gentle ground round the camera turns white, while the same view without
 * snow shows the material's (dark, here) layers. Validated.
 */
class TerrainSnowTest {

    /** The share of bright pixels in the view down the meadows, snowed on at {@code coverage}. */
    private static double bright(float coverage) throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        settings.set(SnowSettings.AREA, 400f);
        settings.set(SnowSettings.MAP_RESOLUTION, 512);
        settings.set(SnowSettings.MAP_HEIGHT, 3000f);
        settings.set(SnowSettings.MAP_DEPTH, 6000f);
        settings.set(SnowSettings.ACCUMULATION_RESOLUTION, 64);
        settings.set(SnowSettings.BIAS, 3f); // 1.6 m texels over slopes: past the ground's own depth (acne)
        try (TestGpu gpu = TestGpu.open(); TerrainRig rig = new TerrainRig(gpu, 128, 96, false, false, settings)) {
            SimpleSnow snow = rig.closing(new SimpleSnow(rig.geometry, rig.view));
            rig.port.add(snow);
            rig.output();
            ShadingHookValues.of(rig.device).set(SimpleSnow.HOOK, SnowParameters.DEFAULTS.withCoverage(coverage), true);
            rig.beforeScene = r -> snow.update(0);
            Color dark = new Color(0.08, 0.08, 0.08, 1);
            TerrainParameters rules = TerrainParameters.DEFAULTS.withRockColor(dark).withGrassColor(dark)
                .withScreeColor(dark).withSnowColor(dark);
            Terrain terrain = rig.terrain(TestSites.rugged(900), TerrainMaterial.data(rules,
                TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN,
                TerrainMaterial.Layer.PLAIN).ref());
            rig.look(0, terrain.heightAt(0, 0) + 120, 0, 0.2, -1, -0.4);
            rig.settle(400);
            int[] argb = rig.argb();
            rig.save("snow-" + coverage, argb);
            int bright = 0;
            for (int p : argb) {
                if (((p >> 16) & 0xff) > 160 && ((p >> 8) & 0xff) > 160 && (p & 0xff) > 160) {
                    bright++;
                }
            }
            return bright / (double) argb.length;
        }
    }

    @Test
    void snowLiesOnTheTerrain() throws Exception {
        double bare = bright(0);
        double snowed = bright(1);
        assertThat(bare).as("bright pixels without snow").isLessThan(0.1);
        assertThat(snowed).as("bright pixels under full snow cover").isGreaterThan(0.5);
    }
}
