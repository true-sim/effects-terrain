package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.terrain.data.CliffParameters;
import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.effects.terrain.data.TerrainSurface;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tiles of a wall with the cliff relief in their meshes ({@link CliffMesh}), on the CPU: refined until no edge on the
 * wall rises more than a row of the step (or a diagonal across one), the mesh conforming (every inner edge between exactly two triangles, one winding),
 * its vertices moved; neighbouring tiles of a level meet vertex for vertex on their border, moved alike; a tile's skirts
 * reach below its coarser neighbour's border wherever that one is lower and the other way round (the gaps between
 * levels close); without the relief, or with it in the heightfield, the tiles are the grid's.
 */
class CliffMeshTest {

    static final TerrainGrid GRID = new TerrainGrid(32, 32, 6);
    static final CliffMesh.Settings SETTINGS = new CliffMesh.Settings(0.5, 3, 9, 200_000);

    static TileMeshes meshes(CliffParameters cliffs) {
        TerrainSurface surface = new TerrainSurface(TestSites.wall(), SurfaceParameters.DATA.withCliffs(cliffs));
        return new TileMeshes(surface, GRID, 0.25, 0.05).cliffs(SETTINGS);
    }

    @Test
    void wallsAreRefinedToTheStepAndStayConforming() {
        TileMeshes meshes = meshes(CliffParameters.LIMESTONE);
        long key = TerrainGrid.key(0, -1, 0); // x from -32 to 0: the wall's lower part
        TileMeshes.Geometry g = meshes.geometry(key, -1);
        int grid = (GRID.quads() + 1) * (GRID.quads() + 1);
        assertThat(g.top()).as("vertices: the grid's and the wall's refinement").isGreaterThan(2 * grid);
        CliffMesh.Vertices v = g.vertices();
        double step = SETTINGS.step(0);
        Map<Long, Integer> uses = new HashMap<>();
        int[] idx = g.indices();
        for (int t = 0; t < g.surface(); t += 3) {
            int[] tri = {idx[t], idx[t + 1], idx[t + 2]};
            // one winding over the tile's plane (where the vertices lie before the relief moves them)
            double cross = (v.px[tri[1]] - v.px[tri[0]]) * (v.pz[tri[2]] - v.pz[tri[0]])
                - (v.pz[tri[1]] - v.pz[tri[0]]) * (v.px[tri[2]] - v.px[tri[0]]);
            assertThat(cross).as("triangle %d's winding", t / 3).isNegative();
            boolean wall = v.weight[tri[0]] >= 1 && v.weight[tri[1]] >= 1 && v.weight[tri[2]] >= 1;
            for (int e = 0; e < 3; e++) {
                int a = tri[e];
                int b = tri[(e + 1) % 3];
                uses.merge((long) Math.min(a, b) << 32 | Math.max(a, b), 1, Integer::sum);
                if (wall) {
                    assertThat(Math.abs(v.positions[3 * a + 1] - v.positions[3 * b + 1]))
                        .as("rise of an edge on the wall (a row, or a diagonal across one)").isLessThanOrEqualTo((float) (2 * step));
                }
            }
        }
        Set<Integer> border = new HashSet<>();
        for (int[] side : g.sides()) {
            for (int b : side) {
                border.add(b);
            }
        }
        for (Map.Entry<Long, Integer> e : uses.entrySet()) {
            int a = (int) (e.getKey() >>> 32);
            int b = (int) (e.getKey() & 0xffffffffL);
            if (e.getValue() == 1 && border.contains(a) && border.contains(b)) {
                continue; // a border edge
            }
            assertThat(e.getValue()).as("triangles on edge (%d, %d)", a, b).isEqualTo(2);
        }
        int moved = 0;
        for (int i = 0; i < g.top(); i++) {
            if (Math.abs(v.positions[3 * i] - v.px[i]) > 0.05) {
                moved++;
            }
        }
        assertThat(moved).as("vertices the relief moved").isGreaterThan(g.top() / 4);
        // the rows run along the bedding planes: every cut vertex on its plane (a level of bedding-plane heights a step
        // apart), so the beds are sampled alike from column to column
        int cuts = 0;
        for (int i = 0; i < g.top(); i++) {
            if (Double.isNaN(v.cut[i])) {
                continue;
            }
            cuts++;
            double wx = -32 + v.px[i];
            double wz = v.pz[i];
            double level = v.cut[i] / step;
            assertThat(level).as("vertex %d's plane, in steps", i).isCloseTo(Math.rint(level), within(1e-9));
            double bed = meshes.surface().bedHeight(wx, wz, meshes.surface().altitude(wx, wz, GRID.spacing(0)));
            assertThat(bed).as("vertex %d on its plane", i).isCloseTo(v.cut[i], within(0.02 * step));
        }
        assertThat(cuts).isGreaterThan(g.top() / 2);
        // the wall's vertices flagged for the material by their tangent (y + z below 0), the rest not
        for (int i = 0; i < g.top(); i++) {
            float flag = v.tangents[4 * i + 1] + v.tangents[4 * i + 2];
            assertThat(v.weight[i] >= 0.5 ? flag < -1 : flag >= 0).as("vertex %d's tangent at weight %s: %s", i,
                v.weight[i], flag).isTrue();
            assertThat(v.tangents[4 * i + 3]).isEqualTo(1f);
        }
    }

    @Test
    void neighboursOfALevelMeetVertexForVertex() {
        TileMeshes meshes = meshes(CliffParameters.LIMESTONE);
        // north-south neighbours on the wall (z = 32 shared), east-west neighbours across it (x = -32 shared)
        long[][] pairs = {{TerrainGrid.key(0, -1, 0), TerrainGrid.key(0, -1, 1), 1, 0},
            {TerrainGrid.key(0, -2, 3), TerrainGrid.key(0, -1, 3), 3, 2}};
        for (long[] pair : pairs) {
            TileMeshes.Geometry a = meshes.geometry(pair[0], -1);
            TileMeshes.Geometry b = meshes.geometry(pair[1], -1);
            int[] sa = a.sides()[(int) pair[2]];
            int[] sb = b.sides()[(int) pair[3]];
            assertThat(sa.length).as("vertices on the shared border").isEqualTo(sb.length);
            double[] oa = origin(pair[0]);
            double[] ob = origin(pair[1]);
            for (int k = 0; k < sa.length; k++) {
                for (int c = 0; c < 3; c++) {
                    double wa = a.vertices().positions[3 * sa[k] + c] + (c == 0 ? oa[0] : c == 2 ? oa[1] : 0);
                    double wb = b.vertices().positions[3 * sb[k] + c] + (c == 0 ? ob[0] : c == 2 ? ob[1] : 0);
                    assertThat(wa).as("border vertex %d, axis %d", k, c).isCloseTo(wb, within(1e-3));
                }
            }
        }
        // the border down the wall is refined too
        assertThat(meshes.geometry(pairs[0][0], -1).sides()[1].length).isGreaterThan(GRID.quads() + 1);
    }

    @Test
    void skirtsReachBelowTheCoarserNeighboursBorder() {
        TileMeshes meshes = meshes(CliffParameters.LIMESTONE);
        // a fine tile on the wall (z from 32 to 64) and the coarser one south of it (z from 64 to 128)
        long fine = TerrainGrid.key(0, -1, 1);
        long coarse = TerrainGrid.key(1, -1, 1);
        TileMeshes.Geometry f = meshes.geometry(fine, -1);
        TileMeshes.Geometry c = meshes.geometry(coarse, -1);
        int[] fs = f.sides()[1]; // the fine tile's z = 64
        int[] cs = c.sides()[0]; // the coarse tile's z = 64, x from -64 to 0
        for (int k = 0; k < fs.length; k++) {
            double x = -32 + f.vertices().px[fs[k]];
            double theirs = along(c, cs, -64, x);
            double ours = f.vertices().positions[3 * fs[k] + 1];
            assertThat(ours - f.skirtDepth()).as("the fine skirt's foot at x = %s under the coarse border", x)
                .isLessThanOrEqualTo(theirs + 1e-3);
            assertThat(theirs - c.skirtDepth()).as("the coarse skirt's foot at x = %s under the fine border", x)
                .isLessThanOrEqualTo(ours + 1e-3);
        }
    }

    @Test
    void withoutTheReliefInTheMeshTilesAreTheGrids() {
        int grid = (GRID.quads() + 1) * (GRID.quads() + 1);
        long key = TerrainGrid.key(0, -1, 0);
        assertThat(meshes(CliffParameters.NONE).geometry(key, -1).top()).isEqualTo(grid);
        assertThat(meshes(CliffParameters.LIMESTONE.withRelief(CliffParameters.Relief.HEIGHT)).geometry(key, -1).top())
            .isEqualTo(grid);
        // on the floor, far from the wall, the relief in the mesh changes nothing either
        TileMeshes.Geometry floor = meshes(CliffParameters.LIMESTONE).geometry(TerrainGrid.key(0, 3, 0), -1);
        assertThat(floor.top()).isEqualTo(grid);
    }

    @Test
    void morphTargetsMoveWallsOntoTheCoarserLevel() {
        TileMeshes meshes = meshes(CliffParameters.LIMESTONE);
        long key = TerrainGrid.key(0, -1, 0);
        TileMeshes.Geometry g = meshes.geometry(key, 1);
        assertThat(g.morph()).isNotNull();
        assertThat(g.morph()[0]).hasSize(3 * g.count());
        // a wall vertex morphs to where the coarser level puts that point, its x moving with the coarser relief
        CliffMesh.Vertices v = g.vertices();
        TerrainSurface surface = meshes.surface();
        double[] point = new double[7];
        int checked = 0;
        for (int i = 0; i < g.top() && checked < 200; i += 17) {
            if (v.weight[i] <= 0) {
                continue;
            }
            surface.cliffPoint(-32 + v.px[i], v.pz[i], GRID.spacing(1), SETTINGS.step(1), point);
            assertThat(v.positions[3 * i] + g.morph()[0][3 * i]).isCloseTo((float) (v.px[i] + point[0]),
                within(1e-3f));
            assertThat(v.positions[3 * i + 1] + g.morph()[0][3 * i + 1]).isCloseTo((float) point[1], within(1e-2f));
            checked++;
        }
        assertThat(checked).isPositive();
    }

    /** The height of border {@code side} of {@code g} (its origin's x {@code x0}) at {@code x}, between its vertices. */
    private static double along(TileMeshes.Geometry g, int[] side, double x0, double x) {
        CliffMesh.Vertices v = g.vertices();
        for (int k = 0; k + 1 < side.length; k++) {
            double a = x0 + v.px[side[k]];
            double b = x0 + v.px[side[k + 1]];
            if (x >= a - 1e-9 && x <= b + 1e-9) {
                double t = b > a ? (x - a) / (b - a) : 0;
                return v.positions[3 * side[k] + 1] * (1 - t) + v.positions[3 * side[k + 1] + 1] * t;
            }
        }
        throw new AssertionError("x = " + x + " is not on the border");
    }

    private static double[] origin(long key) {
        double size = GRID.size(TerrainGrid.level(key));
        return new double[] {TerrainGrid.x(key) * size, TerrainGrid.z(key) * size};
    }
}
