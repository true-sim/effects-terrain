package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.testing.AllocationAssertions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The surface on the CPU, on synthetic rasters: a height query at a sample of the data returns that sample (the data
 * goes through unchanged), sources blend at their edges, the detail is band-limited by the footprint and erodes on
 * slopes, the curvature lowers the far terrain, and queries between grid points follow the tiles' triangles.
 */
class TerrainSurfaceTest {

    private static final LocalFrame FRAME = new LocalFrame(46.5937, 7.9087);

    /** A geographic raster round the frame's origin, {@code step} degrees apart, of {@code height(i, j)}. */
    static DemRaster geographic(double step, int size, Height height) {
        float[] heights = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                heights[j * size + i] = (float) height.at(i, j);
            }
        }
        double x0 = FRAME.longitude() - step * (size / 2);
        double y0 = FRAME.latitude() + step * (size / 2);
        return new DemRaster(DemRaster.GEOGRAPHIC, x0, y0, step, step, size, size, heights);
    }

    interface Height {
        double at(int i, int j);
    }

    private static double hills(int i, int j) {
        return 1000 + 300 * Math.sin(i * 0.11) * Math.cos(j * 0.07) + 2 * i;
    }

    @Test
    void aHeightQueryAtADataSampleReturnsThatSample() {
        DemRaster raster = geographic(1 / 3600.0, 256, TerrainSurfaceTest::hills);
        TerrainSite site = new TerrainSite("test", "test", FRAME, List.of(new TerrainSite.Source("dem", raster, 0, "")));
        TerrainSurface surface = new TerrainSurface(site, SurfaceParameters.DATA);
        for (int j = 3; j < 250; j += 31) {
            for (int i = 5; i < 250; i += 29) {
                double lon = raster.x() + i * raster.dx();
                double lat = raster.y() - j * raster.dy();
                double z = FRAME.z(lat);
                double x = FRAME.x(lat, lon);
                assertThat(surface.height(x, z, 1)).as("sample (%d, %d)", i, j)
                    .isCloseTo(raster.at(i, j), within(1e-3));
            }
        }
    }

    @Test
    void sourcesBlendIntoTheNextFromTheirEdge() {
        DemRaster fine = geographic(1 / 3600.0, 64, (i, j) -> 500);
        DemRaster coarse = geographic(3 / 3600.0, 512, (i, j) -> 400);
        TerrainSite site = new TerrainSite("test", "test", FRAME, List.of(
            new TerrainSite.Source("fine", fine, 200, ""), new TerrainSite.Source("coarse", coarse, 0, "")));
        // The fine raster reaches about 32 samples (~690 m east-west, ~990 m north-south) from the origin
        assertThat(site.height(0, 0, 1)).as("well inside the fine source").isEqualTo(500);
        assertThat(site.height(0, -5000, 1)).as("beyond it").isEqualTo(400);
        double edge = fine.inside(FRAME.longitude(0, FRAME.latitude(-900)), FRAME.latitude(-900));
        assertThat(edge).as("900 m north, inside its edge").isBetween(0.0, 200.0);
        assertThat(site.height(0, -900, 1)).as("within the blend").isStrictlyBetween(400.0, 500.0);
        assertThat(site.height(1e6, 0, 1)).as("beyond every source: the last one's edge").isEqualTo(400);
    }

    @Test
    void theDetailIsBandLimitedByTheFootprintAndCurvatureLowersTheFarTerrain() {
        DemRaster raster = geographic(1 / 3600.0, 256, (i, j) -> 1000 + 3 * i); // a 10 % slope
        TerrainSite site = new TerrainSite("test", "test", FRAME, List.of(new TerrainSite.Source("dem", raster, 0, "")));
        SurfaceParameters detail = new SurfaceParameters(4, 64, 6, 1.5, false);
        TerrainSurface fine = new TerrainSurface(site, detail);
        TerrainSurface plain = new TerrainSurface(site, SurfaceParameters.DATA);
        double fineVariance = 0;
        double coarseVariance = 0;
        int n = 0;
        for (double x = -1000; x < 1000; x += 7.3) {
            double z = 0.37 * x;
            double d1 = fine.height(x, z, 1) - plain.height(x, z, 1);
            double d32 = fine.height(x, z, 32) - plain.height(x, z, 32);
            fineVariance += d1 * d1;
            coarseVariance += d32 * d32;
            n++;
        }
        assertThat(Math.sqrt(fineVariance / n)).as("detail at a 1 m footprint, rms").isBetween(0.3, 4.0);
        assertThat(Math.sqrt(coarseVariance / n)).as("at 32 m: the octaves under 64 m left out")
            .isLessThan(Math.sqrt(fineVariance / n) * 0.9);
        assertThat(new TerrainSurface(site, detail).height(0, 0, 200)).as("nothing at a 200 m footprint")
            .isEqualTo(plain.height(0, 0, 200));
        // Steeper data, damped finer octaves: a cliff takes less fine detail than a gentle slope
        SurfaceParameters curved = new SurfaceParameters(0, 64, 0, 0, true);
        TerrainSurface earth = new TerrainSurface(site, curved);
        assertThat(plain.height(50_000, 0, 30) - earth.height(50_000, 0, 30)).isCloseTo(196.2, within(0.1));
        assertThat(earth.altitude(50_000, 0, 30)).isEqualTo(plain.height(50_000, 0, 30));
    }

    @Test
    void queriesBetweenGridPointsFollowTheTilesTriangles() {
        DemRaster raster = geographic(1 / 3600.0, 256, TerrainSurfaceTest::hills);
        TerrainSite site = new TerrainSite("test", "test", FRAME, List.of(new TerrainSite.Source("dem", raster, 0, "")));
        TerrainSurface surface = new TerrainSurface(site, new SurfaceParameters(2, 32, 4, 1, true));
        double s = 2;
        double h00 = surface.height(10, 20, s);
        double h10 = surface.height(12, 20, s);
        double h01 = surface.height(10, 22, s);
        double h11 = surface.height(12, 22, s);
        assertThat(surface.heightAt(10, 20, s)).isEqualTo(h00);
        assertThat(surface.heightAt(10.5, 20.5, s)).isCloseTo(h00 + 0.25 * (h10 - h00) + 0.25 * (h01 - h00),
            within(1e-9));
        assertThat(surface.heightAt(11.5, 21.5, s)).isCloseTo(h11 + 0.25 * (h01 - h11) + 0.25 * (h10 - h11),
            within(1e-9));
        double[] normal = new double[3];
        surface.normalAt(10.5, 20.5, s, normal);
        assertThat(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).isCloseTo(1, within(1e-9));
        assertThat(normal[0] * 2 + normal[1] * (h10 - h00)).as("perpendicular to the triangle's x edge")
            .isCloseTo(0, within(1e-9));
        AllocationAssertions.assertAllocationFree(() -> surface.heightAt(10.5, 20.5, s));
    }

    @Test
    void sitesAndRastersRoundTripThroughTheirFiles() throws IOException {
        DemRaster raster = geographic(1 / 3600.0, 64, TerrainSurfaceTest::hills);
        TerrainSite site = new TerrainSite("test", "a test", FRAME, List.of(new TerrainSite.Source("dem", raster, 50,
            "© someone")));
        ByteArrayOutputStream dem = new ByteArrayOutputStream();
        raster.write(dem);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        site.write(file);
        TerrainSite read = TerrainSite.read(new ByteArrayInputStream(file.toByteArray()), name -> {
            assertThat(name).isEqualTo("dem.dem");
            return new ByteArrayInputStream(dem.toByteArray());
        });
        assertThat(read.attributions()).containsExactly("© someone");
        assertThat(read.frame().latitude()).isEqualTo(FRAME.latitude());
        assertThat(read.height(123, -456, 1)).isEqualTo(site.height(123, -456, 1));
        assertThat(read.bytes()).isEqualTo(site.bytes());
    }
}
