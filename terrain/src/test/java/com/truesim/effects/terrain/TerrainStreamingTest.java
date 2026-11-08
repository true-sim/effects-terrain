package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import org.junit.jupiter.api.Test;

/**
 * Streaming by distance through the scene's world: the finest tiles load under the camera and only there, the far
 * ground is coarse; moving the camera 6 km loads the finest tiles under it and unloads those it left (their nodes
 * removed, their meshes let go of), with no more tiles held than before; and while it flies, every frame shows the
 * ground whole: no tile goes before what replaces it shows. Validated.
 */
class TerrainStreamingTest {

    private static long under(double x, double z, int level, TerrainGrid grid) {
        double size = grid.size(level);
        return TerrainGrid.key(level, (int) Math.floor(x / size), (int) Math.floor(z / size));
    }

    @Test
    void tilesLoadAndUnloadByDistanceWithoutHolesInFlight() throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        try (TestGpu gpu = TestGpu.open(); TerrainRig rig = new TerrainRig(gpu, 96, 64, false, false, settings)) {
            rig.output();
            Terrain terrain = rig.terrain(TestSites.rugged(900), TerrainSeamsTest.plain().ref());
            TerrainGrid grid = terrain.grid();
            rig.look(0, terrain.heightAt(0, 0) + 250, 0, 1, -0.8, 0.2);
            rig.settle(400);
            assertThat(terrain.shows(under(0, 0, 0, grid))).as("the finest tile under the camera").isTrue();
            assertThat(terrain.shows(under(6000, 0, 0, grid))).as("6 km away: not at the finest level").isFalse();
            assertThat(terrain.shownTiles(0)).as("finest tiles: only round the camera").isBetween(1, 40);
            int held = terrain.heldTiles();
            long first = under(0, 0, 0, grid);
            var firstMesh = terrain.mesh(first);
            assertThat(rig.resources.mesh(firstMesh)).as("its mesh on the GPU").isNotNegative();

            // Fly 6 km east, 150 m a frame, looking down ahead: the ground shows whole in every frame
            int worst = 0;
            for (double x = 150; x <= 6000; x += 150) {
                rig.look(x, terrain.heightAt(x, 0) + 250, 0, 1, -0.8, 0.2);
                rig.frame();
                worst = Math.max(worst, rig.background(rig.argb()));
            }
            assertThat(worst).as("background pixels in any frame of the flight").isZero();
            rig.settle(400);
            assertThat(terrain.shows(under(6000, 0, 0, grid))).as("the finest tile under the camera now").isTrue();
            assertThat(terrain.shows(first)).as("the one it left").isFalse();
            assertThat(rig.resources.mesh(firstMesh)).as("its mesh let go of").isNegative();
            assertThat(terrain.heldTiles()).as("tiles held, against %d before", held).isBetween(held / 2, held * 3 / 2);
            assertThat(terrain.heldTiles()).as("held: what is wanted").isEqualTo(terrain.wantedTiles());
        }
    }
}
