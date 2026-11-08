package com.truesim.effects.terrain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The local frame and the Swiss grid: round trips, known distances and swisstopo's own worked example. */
class GeoreferenceTest {

    private final LocalFrame frame = new LocalFrame(46.5937, 7.9087);

    @Test
    void theLocalFrameRoundTripsAndMeasuresTheMeridianAndParallels() {
        for (double z = -125_000; z <= 125_000; z += 12_500) {
            for (double x = -125_000; x <= 125_000; x += 12_500) {
                double lat = frame.latitude(z);
                double lon = frame.longitude(x, lat);
                assertThat(frame.z(lat)).isCloseTo(z, within(1e-6));
                assertThat(frame.x(lat, lon)).isCloseTo(x, within(1e-6));
            }
        }
        // A degree of latitude at 46.6 N is 111.17 km of meridian (WGS 84), north is -z
        assertThat(frame.z(47.0933) - frame.z(46.0933)).isCloseTo(-111_170, within(30.0));
        // A degree of longitude along the 46.5937 N parallel: N cos(phi) pi / 180
        assertThat(frame.x(46.5937, 8.9087)).isCloseTo(76_660, within(30.0));
        assertThat(frame.x(46.5937, 7.9087)).isZero();
        // The curvature's fall: 100 km out, 785 m
        assertThat(LocalFrame.drop(100_000, 0)).isCloseTo(784.8, within(0.1));
    }

    @Test
    void theSwissGridMatchesSwisstoposWorkedExample() {
        // swisstopo's example: 46 02' 38.87" N, 8 43' 49.79" E is about E 2 700 000, N 1 100 000
        double lat = 46 + 2 / 60.0 + 38.87 / 3600;
        double lon = 8 + 43 / 60.0 + 49.79 / 3600;
        assertThat(SwissGrid.easting(lat, lon)).isCloseTo(2_700_000, within(1.0));
        assertThat(SwissGrid.northing(lat, lon)).isCloseTo(1_100_000, within(1.0));
        assertThat(SwissGrid.latitude(2_700_000, 1_100_000)).isCloseTo(lat, within(2e-5));
        assertThat(SwissGrid.longitude(2_700_000, 1_100_000)).isCloseTo(lon, within(2e-5));
        // Round trips within a metre round Lauterbrunnen
        for (double e = 2_630_000; e <= 2_640_000; e += 2_500) {
            for (double n = 1_155_000; n <= 1_165_000; n += 2_500) {
                double la = SwissGrid.latitude(e, n);
                double lo = SwissGrid.longitude(e, n);
                assertThat(SwissGrid.easting(la, lo)).isCloseTo(e, within(1.0));
                assertThat(SwissGrid.northing(la, lo)).isCloseTo(n, within(1.0));
            }
        }
    }
}
