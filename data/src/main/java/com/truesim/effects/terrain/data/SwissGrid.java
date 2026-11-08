package com.truesim.effects.terrain.data;

/**
 * Swiss LV95 grid coordinates (CH1903+, EPSG:2056) and WGS 84 latitude and longitude, by swisstopo's approximate
 * formulas ("Approximate formulas for the transformation between Swiss projection coordinates and WGS84"): within
 * about a metre across Switzerland, far below the 2 m of the swissALTI3D samples they place.
 */
public final class SwissGrid {

    private SwissGrid() {
    }

    /** The LV95 easting of latitude {@code latitude} and longitude {@code longitude}, degrees. */
    public static double easting(double latitude, double longitude) {
        double phi = (latitude * 3600 - 169_028.66) / 10_000;
        double lambda = (longitude * 3600 - 26_782.5) / 10_000;
        return 2_600_072.37 + 211_455.93 * lambda - 10_938.51 * lambda * phi - 0.36 * lambda * phi * phi
            - 44.54 * lambda * lambda * lambda;
    }

    /** The LV95 northing of latitude {@code latitude} and longitude {@code longitude}, degrees. */
    public static double northing(double latitude, double longitude) {
        double phi = (latitude * 3600 - 169_028.66) / 10_000;
        double lambda = (longitude * 3600 - 26_782.5) / 10_000;
        return 1_200_147.07 + 308_807.95 * phi + 3_745.25 * lambda * lambda + 76.63 * phi * phi
            - 194.56 * lambda * lambda * phi + 119.79 * phi * phi * phi;
    }

    /** The latitude, degrees, of LV95 ({@code easting}, {@code northing}). */
    public static double latitude(double easting, double northing) {
        double y = (easting - 2_600_000) / 1_000_000;
        double x = (northing - 1_200_000) / 1_000_000;
        double phi = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x
            - 0.0140 * x * x * x;
        return phi * 100 / 36;
    }

    /** The longitude, degrees, of LV95 ({@code easting}, {@code northing}). */
    public static double longitude(double easting, double northing) {
        double y = (easting - 2_600_000) / 1_000_000;
        double x = (northing - 1_200_000) / 1_000_000;
        double lambda = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y * y * y;
        return lambda * 100 / 36;
    }
}
