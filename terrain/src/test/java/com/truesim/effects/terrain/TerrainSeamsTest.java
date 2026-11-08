package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.render.MaterialData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Crack-free seams at level boundaries, on the GPU: synthetic rugged mountains seen from a few hundred metres above
 * the ground, looking down across them so every pixel is terrain and several levels of tiles show, meeting where the
 * screen-space error steps from one to the next. With skirts not one pixel of the background shows through, in
 * forward shading and the visibility buffer; without them (the setting off) the gaps between the levels show, so the
 * test sees what the skirts close. Validated.
 */
class TerrainSeamsTest {

    static EngineSettings small() {
        EngineSettings settings = new EngineSettings();
        settings.set(TerrainSettings.TILE_SIZE, 128f);
        settings.set(TerrainSettings.TILE_QUADS, 16);
        settings.set(TerrainSettings.LEVELS, 7);
        settings.set(TerrainSettings.RANGE, 12_000f);
        settings.set(TerrainSettings.SCREEN_ERROR, 2f);
        settings.set(TerrainSettings.BUILD_BUDGET, 4096);
        return settings;
    }

    static MaterialData plain() {
        return TerrainMaterial.data(TerrainParameters.DEFAULTS, TerrainMaterial.Layer.PLAIN,
            TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN);
    }

    /** The background pixels of a view across the mountains, with or without skirts; the levels shown in {@code levels}. */
    static int backgroundAcross(boolean visibilityBuffer, boolean skirts, int[] levels) throws Exception {
        EngineSettings settings = small();
        settings.set(TerrainSettings.SKIRTS, skirts);
        try (TestGpu gpu = TestGpu.open();
             TerrainRig rig = new TerrainRig(gpu, 192, 128, visibilityBuffer, false, settings)) {
            rig.output();
            Terrain terrain = rig.terrain(TestSites.rugged(900), plain().ref());
            double ground = terrain.heightAt(0, 0);
            rig.look(0, ground + 350, 0, 0, -0.55, -1);
            rig.settle(400);
            int[] argb = rig.argb();
            rig.save("seams-" + (visibilityBuffer ? "vb" : "forward") + (skirts ? "" : "-noskirts"), argb);
            for (int level = 0; level < levels.length; level++) {
                levels[level] = terrain.shownTiles(level);
            }
            System.out.println("levels " + java.util.Arrays.toString(levels) + ", background " + rig.background(argb)
                + (skirts ? "" : " without skirts"));
            return rig.background(argb);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void levelBoundariesShowNoGapWithSkirtsAndGapsWithout(boolean visibilityBuffer) throws Exception {
        int[] levels = new int[7];
        int with = backgroundAcross(visibilityBuffer, true, levels);
        int shownLevels = 0;
        for (int count : levels) {
            shownLevels += count > 0 ? 1 : 0;
        }
        assertThat(shownLevels).as("levels shown (%s)", java.util.Arrays.toString(levels)).isGreaterThanOrEqualTo(3);
        assertThat(with).as("background pixels through the terrain, with skirts").isZero();
        int without = backgroundAcross(visibilityBuffer, false, levels);
        assertThat(without).as("background pixels through the gaps between levels, without skirts").isPositive();
    }
}
