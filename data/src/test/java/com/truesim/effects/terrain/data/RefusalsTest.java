package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the data refuses, said plainly, and the Swiss grid's sources sampled through the site as the geographic ones
 * are.
 */
class RefusalsTest {

    @Test
    void geoTiffsItCannotReadAreNamed() throws Exception {
        byte[] big = {'I', 'I', 43, 0, 8, 0, 0, 0};
        assertThatThrownBy(() -> GeoTiff.read(big)).hasMessageContaining("BigTIFF");
        assertThatThrownBy(() -> GeoTiff.read(new byte[] {'I', 'I', 7, 0, 8, 0, 0, 0})).hasMessageContaining("magic");
        assertThatThrownBy(() -> GeoTiff.read(new byte[3])).hasMessageContaining("Not a TIFF");
        float[] one = new float[37 * 23];
        // half floats, and no georeference
        assertThatThrownBy(() -> GeoTiff.read(new TiffWriter(ByteOrder.LITTLE_ENDIAN, 16, 3).georeference(0, 0, 1, 1,
            true).write(one))).hasMessageContaining("bits");
        byte[] plain = new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3).georeference(0, 0, 1, 1, true).write(one);
        assertThatThrownBy(() -> GeoTiff.read(withoutTag(plain, 33550))).hasMessageContaining("georeference");
        assertThatThrownBy(() -> GeoTiff.read(new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3).predictor(5)
            .georeference(0, 0, 1, 1, true).write(one))).hasMessageContaining("Predictor 5");
    }

    /** {@code file} with IFD entry {@code tag} renamed to an unknown tag. */
    private static byte[] withoutTag(byte[] file, int tag) {
        ByteBuffer b = ByteBuffer.wrap(file.clone()).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int n = b.getShort(ifd);
        for (int i = 0; i < n; i++) {
            if ((b.getShort(ifd + 2 + 12 * i) & 0xffff) == tag) {
                b.putShort(ifd + 2 + 12 * i, (short) 65000);
            }
        }
        return b.array();
    }

    @Test
    void rastersAndSitesCheckWhatTheyAreGiven() {
        assertThatThrownBy(() -> new DemRaster(7, 0, 0, 1, 1, 2, 2, new float[4])).hasMessageContaining("crs");
        assertThatThrownBy(() -> new DemRaster(DemRaster.LV95, 0, 0, 1, 1, 2, 2, new float[3]))
            .hasMessageContaining("raster of");
        assertThatThrownBy(() -> DemRaster.read(new ByteArrayInputStream(new byte[64])))
            .hasMessageContaining("Not a .dem");
        assertThatThrownBy(() -> new TerrainSite("s", "s", new LocalFrame(46, 7), List.of()))
            .hasMessageContaining("at least one source");
        assertThatThrownBy(() -> TerrainSite.read(new ByteArrayInputStream("nonsense\n".getBytes(StandardCharsets.UTF_8)),
            name -> null)).hasMessageContaining("Unknown entry");
        assertThatThrownBy(() -> TerrainSite.read(new ByteArrayInputStream("# empty\n".getBytes(StandardCharsets.UTF_8)),
            name -> null)).hasMessageContaining("names its site");
        assertThatThrownBy(() -> new LocalFrame(89, 0)).hasMessageContaining("Origin");
        assertThatThrownBy(() -> new SurfaceParameters(-1, 1, 1, 1, false)).hasMessageContaining("out of range");
        assertThatThrownBy(() -> Manifest.named("nowhere")).hasMessageContaining("No manifest");
        assertThatThrownBy(() -> Manifest.named("lauterbrunnen").source("nowhere"))
            .hasMessageContaining("No source");
    }

    @Test
    void swissGridSourcesAreSampledWhereTheSiteIs() throws Exception {
        LocalFrame frame = new LocalFrame(46.5937, 7.9087);
        double e0 = Math.floor(SwissGrid.easting(46.5937, 7.9087)) - 200;
        double n0 = Math.floor(SwissGrid.northing(46.5937, 7.9087)) + 200;
        int size = 201;
        float[] heights = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                heights[j * size + i] = (float) (800 + 0.5 * i - 0.25 * j);
            }
        }
        heights[5 * size + 5] = Float.NaN; // a hole: the next source shows through
        DemRaster swiss = new DemRaster(DemRaster.LV95, e0, n0, 2, 2, size, size, heights);
        DemRaster flat = new DemRaster(DemRaster.GEOGRAPHIC, 7.8, 46.7, 0.01, 0.01, 30, 30, new float[900]);
        TerrainSite site = new TerrainSite("s", "s", frame, List.of(new TerrainSite.Source("swiss", swiss, 0, "a"),
            new TerrainSite.Source("flat", flat, 0, "b")));
        // The origin: e0 + 200 m, n0 - 200 m, sample (100, 100) of a plane
        double h = site.height(0, 0, 1);
        double i = (SwissGrid.easting(46.5937, 7.9087) - e0) / 2;
        double j = (n0 - SwissGrid.northing(46.5937, 7.9087)) / 2;
        assertThat(h).isCloseTo(800 + 0.5 * i - 0.25 * j, within(1e-3));
        double holeLat = SwissGrid.latitude(e0 + 10.5, n0 - 10.5);
        double holeLon = SwissGrid.longitude(e0 + 10.5, n0 - 10.5);
        assertThat(site.height(frame.x(holeLat, holeLon), frame.z(holeLat), 1)).as("through the hole").isZero();
        assertThat(site.attributions()).containsExactly("a", "b");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        swiss.write(out);
        DemRaster back = DemRaster.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(back.crs()).isEqualTo(DemRaster.LV95);
        assertThat(back.x()).isEqualTo(e0);
        assertThat(back.y()).isEqualTo(n0);
        assertThat(back.dx()).isEqualTo(2);
        assertThat(back.dy()).isEqualTo(2);
        assertThat(back.metres()).isEqualTo(2);
    }
}
