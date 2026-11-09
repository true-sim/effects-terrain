package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Terrain modifiers: they compose in order (each given the height the ones before left), each one's materials lie over
 * the earlier ones' as far as it covers, bounds keep them where they are, and the surface's every height query (with
 * the detail on or off, at any footprint) and the curvature follow them.
 */
class TerrainModifiersTest {

    /** A ditch 4 m deep and 10 m wide along x = 0, lined with gravel and sand at its shoulders. */
    private static final TerrainModifier DITCH = new TerrainModifier() {
        @Override
        public double height(double x, double z, double footprint, double height) {
            return Math.abs(x) < 5 ? height - 4 : height;
        }

        @Override
        public double materials(double x, double z, double footprint, double[] cover) {
            if (Math.abs(x) < 5) {
                cover[GRAVEL] = 1;
                return 1;
            }
            if (Math.abs(x) < 8) {
                cover[SAND] = 0.5;
                return 0.5;
            }
            return 0;
        }
    };

    /** A road levelled at 990 m across z = 0, cobbled, 6 m wide, only within its bounds. */
    private static final TerrainModifier ROAD = new TerrainModifier() {
        @Override
        public double height(double x, double z, double footprint, double height) {
            return Math.abs(z) < 3 ? 990 : height;
        }

        @Override
        public double materials(double x, double z, double footprint, double[] cover) {
            if (Math.abs(z) < 3) {
                cover[COBBLES] = 0.8;
                return 0.8;
            }
            return 0;
        }

        @Override
        public Bounds bounds() {
            return new Bounds(-100, -3, 100, 3);
        }
    };

    private static TerrainSite site() {
        DemRaster raster = TerrainSurfaceTest.geographic(1 / 3600.0, 128, (i, j) -> 1000 + 0.5 * i);
        return new TerrainSite("test", "test", new LocalFrame(46.5937, 7.9087),
            List.of(new TerrainSite.Source("dem", raster, 0, "")));
    }

    @Test
    void modifiersComposeInOrder() {
        TerrainModifiers both = TerrainModifiers.of(DITCH).with(ROAD);
        assertThat(both.modifiers()).containsExactly(DITCH, ROAD);
        // the road levels what the ditch cut, where they cross; elsewhere each its own
        assertThat(both.height(0, 0, 1, 1000)).isEqualTo(990);
        assertThat(both.height(0, 50, 1, 1000)).isEqualTo(996);
        assertThat(both.height(50, 1, 1, 1000)).isEqualTo(990);
        assertThat(both.height(500, 1, 1, 1000)).as("outside the road's bounds").isEqualTo(1000);
        assertThat(TerrainModifiers.of(ROAD, DITCH).height(0, 0, 1, 1000)).as("the other order").isEqualTo(986);
        assertThat(TerrainModifiers.NONE.isEmpty()).isTrue();
        assertThat(TerrainModifiers.of(List.of())).isSameAs(TerrainModifiers.NONE);
        assertThat(TerrainModifiers.NONE.height(1, 2, 3, 4)).isEqualTo(4);
    }

    @Test
    void laterMaterialsLieOverEarlierOnes() {
        TerrainModifiers both = TerrainModifiers.of(DITCH, ROAD);
        double[] cover = new double[TerrainModifier.MATERIALS];
        // the road's cobbles (0.8) over the ditch's gravel (1): gravel keeps 0.2
        assertThat(both.materials(0, 0, 1, cover)).isCloseTo(1, within(1e-12));
        assertThat(cover[TerrainModifier.COBBLES]).isCloseTo(0.8, within(1e-12));
        assertThat(cover[TerrainModifier.GRAVEL]).isCloseTo(0.2, within(1e-12));
        // the ditch's shoulder: half sand
        assertThat(both.materials(6, 50, 1, cover)).isCloseTo(0.5, within(1e-12));
        assertThat(cover[TerrainModifier.SAND]).isCloseTo(0.5, within(1e-12));
        assertThat(cover[TerrainModifier.GRAVEL]).isZero();
        // nothing: the layers alone
        assertThat(both.materials(50, 50, 1, cover)).isZero();
        assertThat(cover).containsOnly(0.0);
        // a modifier laying nothing leaves the materials alone
        TerrainModifier none = new TerrainModifier() {
        };
        assertThat(TerrainModifiers.of(none).materials(0, 0, 1, cover)).isZero();
        assertThat(none.height(0, 0, 1, 7)).isEqualTo(7);
        assertThat(none.bounds()).isNull();
    }

    @Test
    void theSurfaceFollowsItsModifiers() {
        TerrainSite site = site();
        for (SurfaceParameters parameters : List.of(SurfaceParameters.DATA,
            new SurfaceParameters(2.5, 64, 6, 1.5, true))) {
            TerrainSurface plain = new TerrainSurface(site, parameters);
            TerrainSurface cut = plain.withModifiers(TerrainModifiers.of(DITCH));
            assertThat(cut.modifiers().modifiers()).containsExactly(DITCH);
            assertThat(cut.unmodified().modifiers().isEmpty()).isTrue();
            assertThat(plain.unmodified()).isSameAs(plain);
            for (double footprint : new double[] {1, 4, 32}) {
                assertThat(cut.height(0, 40, footprint)).isCloseTo(plain.height(0, 40, footprint) - 4, within(1e-9));
                assertThat(cut.altitude(0, 40, footprint)).isCloseTo(plain.altitude(0, 40, footprint) - 4,
                    within(1e-9));
                assertThat(cut.height(30, 40, footprint)).isEqualTo(plain.height(30, 40, footprint));
            }
            // queries between grid points follow the modified grid
            assertThat(cut.heightAt(1, 40, 2)).isCloseTo(plain.heightAt(1, 40, 2) - 4, within(1e-9));
            double[] cover = new double[TerrainModifier.MATERIALS];
            assertThat(cut.materials(0, 40, 2, cover)).isEqualTo(1);
            assertThat(plain.materials(0, 40, 2, cover)).isZero();
        }
        // far from the origin the curvature still applies once, outside the modifier
        TerrainSurface curved = new TerrainSurface(site, new SurfaceParameters(2.5, 64, 6, 1.5, true),
            TerrainModifiers.of(DITCH));
        double plain = new TerrainSurface(site, new SurfaceParameters(2.5, 64, 6, 1.5, true)).height(0, 2000, 8);
        assertThat(curved.height(0, 2000, 8)).isCloseTo(plain - 4, within(1e-9));
    }

    @Test
    void boundsAreChecked() {
        assertThatThrownBy(() -> new TerrainModifier.Bounds(1, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        TerrainModifier.Bounds b = new TerrainModifier.Bounds(0, 0, 10, 10);
        assertThat(b.contains(5, 5)).isTrue();
        assertThat(b.contains(5, 11)).isFalse();
    }
}
