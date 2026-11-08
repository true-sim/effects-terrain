package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * GeoTIFFs as the sources write them, written here by a small encoder: Copernicus's (tiled, Deflate, floating-point
 * prediction, pixel-is-point) and swisstopo's (tiled, LZW, pixel-is-area), strips, 16-bit integers with horizontal
 * prediction, both byte orders, no-data; each read back sample for sample, with its georeference.
 */
class GeoTiffTest {

    private static final int W = 37;
    private static final int H = 23;

    private static float value(int x, int y) {
        return (float) (1000 + 13.25 * x - 7.5 * y + Math.sin(x * 0.7) * 3 + (x * y % 5) * 0.125);
    }

    /** A test image: each sample its {@link #value}, but (3, 4) no data (-9999). */
    private static float[] image() {
        float[] v = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                v[y * W + x] = value(x, y);
            }
        }
        v[4 * W + 3] = -9999;
        return v;
    }

    @Test
    void copernicusTilesDeflatedWithFloatingPointPrediction() throws IOException {
        byte[] file = new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3).tiled(16, 16).compression(8).predictor(3)
            .georeference(7, 47, 1 / 3600.0, 1 / 3600.0, true).write(image());
        GeoTiff tiff = GeoTiff.read(file);
        checkSamples(tiff, false);
        assertThat(tiff.pixelIsPoint()).isTrue();
        assertThat(tiff.centreX(0)).isEqualTo(7.0);
        assertThat(tiff.centreY(0)).isEqualTo(47.0);
        assertThat(tiff.centreX(36)).isEqualTo(7 + 36 / 3600.0);
    }

    @Test
    void swisstopoTilesInLzwSitOnTheirCorner() throws IOException {
        byte[] file = new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3).tiled(16, 16).compression(5).predictor(1)
            .georeference(2_635_000, 1_161_000, 2, 2, false).write(image());
        GeoTiff tiff = GeoTiff.read(file);
        checkSamples(tiff, false);
        assertThat(tiff.pixelIsPoint()).isFalse();
        assertThat(tiff.centreX(0)).as("the first sample's centre, a metre in").isEqualTo(2_635_001.0);
        assertThat(tiff.centreY(0)).isEqualTo(1_160_999.0);
    }

    @Test
    void bigEndianStripsAndSixteenBitIntegersWithHorizontalPrediction() throws IOException {
        GeoTiff floats = GeoTiff.read(new TiffWriter(ByteOrder.BIG_ENDIAN, 32, 3).strips(5).compression(1).predictor(1)
            .georeference(0, 0, 1, 1, true).write(image()));
        checkSamples(floats, false);
        GeoTiff shorts = GeoTiff.read(new TiffWriter(ByteOrder.LITTLE_ENDIAN, 16, 2).strips(7).compression(8).predictor(2)
            .georeference(0, 0, 1, 1, true).write(image()));
        checkSamples(shorts, true);
        GeoTiff lzwShorts = GeoTiff.read(new TiffWriter(ByteOrder.BIG_ENDIAN, 16, 2).strips(3).compression(5).predictor(2)
            .georeference(0, 0, 1, 1, true).write(image()));
        checkSamples(lzwShorts, true);
    }

    @Test
    void whatItCannotReadIsReported() {
        assertThatThrownBy(() -> GeoTiff.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})).hasMessageContaining("Not a TIFF");
        assertThatThrownBy(() -> GeoTiff.read(new TiffWriter(ByteOrder.LITTLE_ENDIAN, 32, 3).strips(H).compression(7)
            .predictor(1).georeference(0, 0, 1, 1, true).write(image()))).hasMessageContaining("Compression 7");
    }

    private static void checkSamples(GeoTiff tiff, boolean integers) {
        assertThat(tiff.width()).isEqualTo(W);
        assertThat(tiff.height()).isEqualTo(H);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (x == 3 && y == 4) {
                    assertThat(tiff.at(x, y)).as("no data").isNaN();
                } else {
                    float expected = integers ? (short) value(x, y) : value(x, y);
                    assertThat(tiff.at(x, y)).as("sample (%d, %d)", x, y).isEqualTo(expected);
                }
            }
        }
    }
}
