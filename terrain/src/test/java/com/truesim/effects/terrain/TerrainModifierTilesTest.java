package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.effects.terrain.data.TerrainModifier;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import org.junit.jupiter.api.Test;

/**
 * Tiles of a modified surface: their heights are the modifiers' (a cut bed is lower in every level's tiles, as the CPU
 * queries say), and their vertices carry the surface materials' cover and mix as the terrain material reads them back
 * (the mix's encoding round-trips; uncovered vertices beside covered ones take their mix, so shoulders fade as the
 * material itself).
 */
class TerrainModifierTilesTest {

    /** A bed 6 m deep and 30 m wide along x = 100, gravel; sand on 10 m beside it. */
    private static final TerrainModifier BED = new TerrainModifier() {
        @Override
        public double height(double x, double z, double footprint, double height) {
            return Math.abs(x - 100) < 15 ? height - 6 : height;
        }

        @Override
        public double materials(double x, double z, double footprint, double[] cover) {
            double d = Math.abs(x - 100);
            if (d < 15) {
                cover[GRAVEL] = 1;
                return 1;
            }
            if (d < 25) {
                cover[SAND] = 0.6;
                return 0.6;
            }
            return 0;
        }
    };

    @Test
    void theMixRoundTrips() {
        double[] mix = new double[2];
        float[] tangent = new float[4];
        double[] back = new double[2];
        for (double a = 0; a <= 1; a += 0.25) {
            for (double b = 0; b <= 1; b += 0.25) {
                TileMeshes.encodeMix(a, b, tangent, 0);
                assertThat(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2])
                    .isCloseTo(1f, within(1e-5f));
                assertThat(tangent[2]).isPositive();
                TileMeshes.decodeMix(tangent[0], tangent[1], tangent[2], back);
                assertThat(back[0]).isCloseTo(a, within(1e-6));
                assertThat(back[1]).isCloseTo(b, within(1e-6));
            }
        }
        // the corners: gravel (0, 0), sand (1, 0), cobbles (0, 1), dirt (1, 1)
        double[] cover = new double[TerrainModifier.MATERIALS];
        cover[TerrainModifier.SAND] = 0.5;
        TileMeshes.mix(cover, 0.5, mix, 0);
        assertThat(mix).containsExactly(1, 0);
        cover[TerrainModifier.SAND] = 0;
        cover[TerrainModifier.DIRT] = 0.3;
        TileMeshes.mix(cover, 0.3, mix, 0);
        assertThat(mix).containsExactly(1, 1);
        cover[TerrainModifier.DIRT] = 0;
        cover[TerrainModifier.COBBLES] = 1;
        TileMeshes.mix(cover, 1, mix, 0);
        assertThat(mix).containsExactly(0, 1);
    }

    @Test
    void tilesFollowTheModifiers() {
        TerrainSite site = TestSites.rugged(256);
        TerrainGrid grid = new TerrainGrid(256, 32, 4);
        TerrainSurface plain = new TerrainSurface(site, SurfaceParameters.DATA);
        TerrainSurface cut = plain.withModifiers(TerrainModifiers.of(BED));
        TileMeshes before = new TileMeshes(plain, grid, 1, 0.05);
        TileMeshes after = new TileMeshes(cut, grid, 1, 0.05);
        long key = TerrainGrid.key(0, 0, 0);
        before.build(key);
        after.build(key);
        assertThat(after.info(key).low()).isLessThan(before.info(key).low() + 1e-9);
        // a coarser level's tile over the bed is cut too (its morph target as well: no throw)
        long coarse = TerrainGrid.key(1, 0, 0);
        after.build(TerrainGrid.key(0, 0, 0), 1);
        after.build(coarse);
        // the vertices' cover and mix, as the material reads them
        int n = grid.quads();
        int count = (n + 1) * (n + 1);
        float[] colors = new float[4 * count];
        float[] tangents = new float[4 * count];
        double s = grid.spacing(0);
        after.cover(0, 0, s, colors, tangents);
        double[] mix = new double[2];
        for (int i = 0; i <= n; i++) {
            double x = i * s;
            int v = 5 * (n + 1) + i;
            double d = Math.abs(x - 100);
            float cover = 1 - colors[4 * v + 3];
            TileMeshes.decodeMix(tangents[4 * v], tangents[4 * v + 1], tangents[4 * v + 2], mix);
            if (d < 15) {
                assertThat(cover).as("gravel at %s", x).isCloseTo(1f, within(1e-3f));
                assertThat(mix[0]).isCloseTo(0, within(1e-6));
                assertThat(mix[1]).isCloseTo(0, within(1e-6));
            } else if (d < 25) {
                assertThat(cover).as("sand at %s", x).isCloseTo(0.6f, within(3e-3f));
                assertThat(mix[0]).isCloseTo(1, within(1e-6));
            } else {
                assertThat(cover).as("none at %s", x).isZero();
            }
        }
        // the first uncovered vertex past the sand takes the sand's mix: its shoulder fades as sand
        int beyond = (int) Math.ceil(125 / s);
        if (125 / s == Math.floor(125 / s)) {
            beyond++;
        }
        int v = 5 * (n + 1) + beyond;
        TileMeshes.decodeMix(tangents[4 * v], tangents[4 * v + 1], tangents[4 * v + 2], mix);
        assertThat(mix[0]).isCloseTo(1, within(1e-6));
        // without modifiers: no cover anywhere
        before.cover(0, 0, s, colors, tangents);
        for (int k = 0; k < count; k++) {
            assertThat(colors[4 * k + 3]).isEqualTo(1f);
        }
    }
}
