package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cliff relief on the CPU ({@link CliffParameters}), on a synthetic wall: the slope blend (none below its start,
 * all from its end, smooth and rising between), nothing changed off the walls in either mode, the heightfield's relief
 * bounded by the wall's rim and foot, the mesh's relief horizontal and bounded, its normals unit and outward, and
 * every answer the same for the same point (tiles build on several threads and must agree at their borders).
 */
class CliffReliefTest {

    private static final LocalFrame FRAME = new LocalFrame(46.5937, 7.9087);
    /** The wall's foot and rim, metres above the sea; its foot at x = 0, its rim 50 m west. */
    static final double FOOT = 800;
    static final double RIM = 1100;

    /**
     * A wall of 300 m facing east, from x = -50 m (the rim) to x = 0 (the foot), between a floor and a plateau, the
     * data 2 m apart (as swissALTI3D); a meadow sloping 10 degrees east of x = 150.
     */
    static TerrainSite wall() {
        int size = 300;
        double metres = 2;
        double dy = metres / 111_200.0;
        double dx = metres / (111_200.0 * Math.cos(Math.toRadians(FRAME.latitude())));
        float[] heights = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double x = (i - size / 2) * metres;
                double t = Math.clamp((-x) / 50, 0, 1);
                double h = FOOT + (RIM - FOOT) * t * t * (3 - 2 * t);
                if (x > 150) {
                    h -= (x - 150) * Math.tan(Math.toRadians(10));
                }
                heights[j * size + i] = (float) h;
            }
        }
        DemRaster raster = new DemRaster(DemRaster.GEOGRAPHIC, FRAME.longitude() - dx * (size / 2),
            FRAME.latitude() + dy * (size / 2), dx, dy, size, size, heights);
        return new TerrainSite("wall", "a synthetic wall", FRAME, List.of(new TerrainSite.Source("wall", raster, 0, "")));
    }

    private static SurfaceParameters with(CliffParameters cliffs) {
        return SurfaceParameters.DATA.withCliffs(cliffs);
    }

    @Test
    void theSlopeBlendStartsAtItsStartAndIsWholeFromItsEnd() {
        CliffParameters c = CliffParameters.LIMESTONE;
        assertThat(c.weight(0)).isZero();
        assertThat(c.weight(c.slopeStart() - 0.01)).isZero();
        assertThat(c.weight(c.slopeStart())).isZero();
        assertThat(c.weight(c.slopeEnd())).isEqualTo(1);
        assertThat(c.weight(89)).isEqualTo(1);
        assertThat(c.weight(0.5 * (c.slopeStart() + c.slopeEnd()))).isCloseTo(0.5, within(1e-9));
        double before = 0;
        for (double slope = c.slopeStart(); slope <= c.slopeEnd(); slope += 0.25) {
            double w = c.weight(slope);
            assertThat(w).as("rising at %s degrees", slope).isGreaterThanOrEqualTo(before);
            before = w;
        }
        assertThatThrownBy(() -> new CliffParameters(60, 55, 2, 9, 0, 1, 0, 0, 0, 100, 10, 1, 1, 0, 1, 50,
            CliffParameters.Relief.MESH)).hasMessageContaining("out of range");
        assertThat(CliffParameters.NONE.any()).isFalse();
        assertThat(CliffParameters.LIMESTONE.any()).isTrue();
    }

    @Test
    void nothingChangesOffTheWalls() {
        TerrainSite site = wall();
        TerrainSurface plain = new TerrainSurface(site, SurfaceParameters.DATA);
        double[] point = new double[7];
        for (CliffParameters.Relief relief : CliffParameters.Relief.values()) {
            TerrainSurface cliffs = new TerrainSurface(site, with(CliffParameters.LIMESTONE.withRelief(relief)));
            // the floor well out from the foot, the plateau well back from the rim, the meadow
            for (double x : new double[] {-150, -100, 40, 100, 200, 250}) {
                for (double z = -200; z <= 200; z += 37) {
                    assertThat(cliffs.height(x, z, 0.5)).as("%s at (%s, %s)", relief, x, z)
                        .isEqualTo(plain.height(x, z, 0.5));
                    assertThat(cliffs.cliffPoint(x, z, 0.5, 0.4, point)).isZero();
                    assertThat(point[0]).isZero();
                    assertThat(point[2]).isZero();
                    assertThat(point[1]).isEqualTo(plain.height(x, z, 0.5));
                }
            }
        }
    }

    @Test
    void theHeightfieldsReliefChangesTheWallWithinItsRimAndFoot() {
        TerrainSite site = wall();
        CliffParameters c = CliffParameters.LIMESTONE.withRelief(CliffParameters.Relief.HEIGHT).withSmoothing(0);
        TerrainSurface plain = new TerrainSurface(site, SurfaceParameters.DATA);
        TerrainSurface cliffs = new TerrainSurface(site, with(c));
        int changed = 0;
        int samples = 0;
        for (double x = -45; x <= -5; x += 0.25) {
            for (double z = -200; z <= 200; z += 3.1) {
                double h = cliffs.height(x, z, 0.25);
                assertThat(h).as("(%s, %s) within the wall's foot and rim", x, z).isBetween(FOOT - 1e-6, RIM + 1e-6);
                samples++;
                if (Math.abs(h - plain.height(x, z, 0.25)) > 0.5) {
                    changed++;
                }
            }
        }
        assertThat(changed).as("points of the wall the relief moves by more than 0.5 m").isGreaterThan(samples / 4);
    }

    @Test
    void theMeshsReliefMovesTheWallHorizontallyWithinItsReach() {
        TerrainSite site = wall();
        CliffParameters c = CliffParameters.LIMESTONE;
        TerrainSurface cliffs = new TerrainSurface(site, with(c));
        TerrainSurface smooth = new TerrainSurface(site, with(c.withLedge(0).withJointDepth(0).withButtressDepth(0)));
        double reach = c.ledge() + c.jointDepth() + 1.5 * c.buttressDepth();
        double[] point = new double[7];
        double[] again = new double[7];
        double largest = 0;
        for (double x = -44; x <= -10; x += 0.5) {
            for (double z = -200; z <= 200; z += 1.7) {
                double w = cliffs.cliffPoint(x, z, 0.5, 0.4, point);
                assertThat(w).as("on the wall at (%s, %s)", x, z).isPositive();
                // the height is the surface's, smoothed on the wall; the shift horizontal, out of it or into it
                assertThat(point[1]).isEqualTo(cliffs.height(x, z, 0.5));
                assertThat(point[1]).isCloseTo(smooth.height(x, z, 0.5), within(1e-9));
                double shift = Math.hypot(point[0], point[2]);
                assertThat(shift).as("shift at (%s, %s)", x, z).isLessThanOrEqualTo(reach);
                // across the wall: the wall faces east (+x), so the shift is along x
                assertThat(Math.abs(point[2])).isLessThan(1e-6 + 0.05 * shift);
                largest = Math.max(largest, shift);
                double n = Math.sqrt(point[4] * point[4] + point[5] * point[5] + point[6] * point[6]);
                assertThat(n).isCloseTo(1, within(1e-9));
                // the same point, the same answer
                cliffs.cliffPoint(x, z, 0.5, 0.4, again);
                assertThat(again).containsExactly(point);
            }
        }
        assertThat(largest).as("the relief's largest shift").isGreaterThan(1);
        // the wall faces east: its normals point east on the whole, a few on the overhangs' undersides down
        double east = 0;
        int down = 0;
        int count = 0;
        for (double y = -44; y <= -10; y += 0.05) {
            cliffs.cliffPoint(y, 13.3, 0.5, 0.4, point);
            east += point[4];
            down += point[5] < 0 ? 1 : 0;
            count++;
        }
        assertThat(east / count).as("the normals' mean east part").isGreaterThan(0.5);
        assertThat(down).as("normals facing down (overhangs)").isPositive();
    }

    @Test
    void bedsFadeWhereTheMeshCannotHoldThem() {
        TerrainSite site = wall();
        TerrainSurface cliffs = new TerrainSurface(site,
            with(CliffParameters.LIMESTONE.withJointDepth(0).withButtressDepth(0)));
        double[] point = new double[7];
        // vertices every 0.4 m up the wall hold the beds: the shift varies with the altitude
        double fine = spread(cliffs, 0.4, point);
        // vertices every 20 m up it (a coarse grid's) cannot: the beds are gone, the wall unmoved
        double coarse = spread(cliffs, 20, point);
        assertThat(fine).isGreaterThan(0.5);
        assertThat(coarse).isLessThan(1e-9);
    }

    /** How far the shift ranges down a line across the wall with its vertices {@code vertical} apart up it. */
    private static double spread(TerrainSurface surface, double vertical, double[] point) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double x = -44; x <= -10; x += 0.05) {
            surface.cliffPoint(x, 21.1, 0.5, vertical, point);
            min = Math.min(min, point[0]);
            max = Math.max(max, point[0]);
        }
        return max - min;
    }
}
