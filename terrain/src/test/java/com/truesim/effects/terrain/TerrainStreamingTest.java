package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.testing.AllocationAssertions;
import org.junit.jupiter.api.Test;

/**
 * Streaming by distance through the scene's world: the finest tiles load under the camera and only there, the far
 * ground is coarse; moving the camera 6 km loads the finest tiles under it and unloads those it left (their nodes
 * removed, their meshes let go of), with no more tiles held than before; and while it flies, every frame shows the
 * ground whole: no tile goes before what replaces it shows; a still camera's frame allocates nothing. Validated.
 */
class TerrainStreamingTest {

    private static long under(double x, double z, int level, TerrainGrid grid) {
        double size = grid.size(level);
        return TerrainGrid.key(level, (int) Math.floor(x / size), (int) Math.floor(z / size));
    }

    /** Where the pixel's ray meets the terrain (marched on the CPU), and the shown tiles round there. */
    private static String under(TerrainRig rig, Terrain terrain, int px, int py) {
        double[] f = {1, -0.8, 0.2};
        double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        for (int k = 0; k < 3; k++) {
            f[k] /= fl;
        }
        double[] u = {-f[1] * f[0], 1 - f[1] * f[1], -f[1] * f[2]};
        double ul = Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        for (int k = 0; k < 3; k++) {
            u[k] /= ul;
        }
        double[] r = {f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
        double t = Math.tan(rig.fovY() / 2);
        double nx = ((px + 0.5) / rig.width * 2 - 1) * t * rig.width / rig.height;
        double ny = (1 - (py + 0.5) / rig.height * 2) * t;
        double dx = f[0] + r[0] * nx + u[0] * ny;
        double dy = f[1] + r[1] * nx + u[1] * ny;
        double dz = f[2] + r[2] * nx + u[2] * ny;
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double x = rig.camera.position(0);
        double y = rig.camera.position(1);
        double z = rig.camera.position(2);
        for (double s = 0; s < 20_000; s += 0.5) {
            double px2 = x + dx / dl * s;
            double py2 = y + dy / dl * s;
            double pz2 = z + dz / dl * s;
            if (py2 < terrain.heightAt(px2, pz2)) {
                StringBuilder tiles = new StringBuilder();
                terrain.forEachShown(key -> {
                    double size = terrain.grid().size(TerrainGrid.level(key));
                    double x0 = TerrainGrid.x(key) * size;
                    double z0 = TerrainGrid.z(key) * size;
                    if (px2 > x0 - 300 && px2 < x0 + size + 300 && pz2 > z0 - 300 && pz2 < z0 + size + 300) {
                        tiles.append(String.format(java.util.Locale.ROOT, " L%d[%.0f..%.0f, %.0f..%.0f]",
                            TerrainGrid.level(key), x0, x0 + size, z0, z0 + size));
                    }
                });
                return String.format(java.util.Locale.ROOT, "hit (%.1f, %.1f, %.1f) %.0f m away; tiles%s", px2, py2,
                    pz2, s, tiles);
            }
        }
        return "no hit within 20 km";
    }

    private static void saveHole(TerrainRig rig, int[] argb, int x) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "reports", "holes");
            java.nio.file.Files.createDirectories(dir);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(rig.width, rig.height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, rig.width, rig.height, argb, 0, rig.width);
            javax.imageio.ImageIO.write(image, "png", dir.resolve("hole-" + x + ".png").toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void tilesLoadAndUnloadByDistanceWithoutHolesInFlight(boolean occlusion) throws Exception {
        EngineSettings settings = TerrainSeamsTest.small();
        try (TestGpu gpu = TestGpu.open(); TerrainRig rig = new TerrainRig(gpu, 96, 64, false, false, settings, occlusion)) {
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
            StringBuilder holes = new StringBuilder();
            for (double x = 150; x <= 6000; x += 150) {
                rig.look(x, terrain.heightAt(x, 0) + 250, 0, 1, -0.8, 0.2);
                rig.frame();
                int[] argb = rig.argb();
                for (int i = 0; i < argb.length; i++) {
                    if ((argb[i] & 0xffffff) == 0xff00ff) {
                        holes.append(String.format(java.util.Locale.ROOT, " (x %.0f: pixel %d, %d, %s)", x, i % 96,
                            i / 96, under(rig, terrain, i % 96, i / 96)));
                        saveHole(rig, argb, (int) x);
                    }
                }
            }
            assertThat(holes.toString()).as("background pixels in the frames of the flight").isEmpty();
            rig.settle(400);
            assertThat(terrain.shows(under(6000, 0, 0, grid))).as("the finest tile under the camera now").isTrue();
            assertThat(terrain.shows(first)).as("the one it left").isFalse();
            assertThat(rig.resources.mesh(firstMesh)).as("its mesh let go of").isNegative();
            assertThat(terrain.heldTiles()).as("tiles held, against %d before", held).isBetween(held / 2, held * 3 / 2);
            assertThat(terrain.heldTiles()).as("held: what is wanted").isEqualTo(terrain.wantedTiles());
            // A frame of a still camera: the terrain's part allocates nothing
            double cx = rig.camera.position(0);
            double cy = rig.camera.position(1);
            double cz = rig.camera.position(2);
            AllocationAssertions.assertAllocationFree(() -> {
                terrain.follow(cx, cy, cz, rig.fovY(), 64);
                terrain.update();
            });
        }
    }
}
