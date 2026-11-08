package com.truesim.effects.terrain.data;

import com.truesim.common.math.Maths;

/**
 * The terrain's local frame round a site: x east, y up, z south, metres, from the site's origin on the WGS 84
 * ellipsoid. North-south distances are the meridian's arc from the origin's latitude and east-west distances are along
 * each point's own parallel (a sinusoidal projection centred on the origin's meridian): exact along both, within a
 * few metres of a conformal map across the 120 km a site reaches, invisible at that distance.
 */
public final class LocalFrame {

    /** WGS 84's semi-major axis, metres. */
    public static final double A = 6_378_137;
    /** WGS 84's flattening. */
    public static final double F = 1 / 298.257223563;
    /** The mean Earth radius the terrain's curvature falls away with, metres. */
    public static final double EARTH_RADIUS = 6_371_000;
    private static final double E2 = F * (2 - F);

    private final double latitude;
    private final double longitude;
    private final double originArc;

    /** The frame whose origin is at {@code latitude}, {@code longitude}, degrees. */
    public LocalFrame(double latitude, double longitude) {
        if (!(Math.abs(latitude) < 85) || !(Math.abs(longitude) <= 180)) {
            throw new IllegalArgumentException("Origin out of range: " + latitude + ", " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.originArc = meridianArc(Maths.toRadians(latitude));
    }

    /** The origin's latitude, degrees. */
    public double latitude() {
        return latitude;
    }

    /** The origin's longitude, degrees. */
    public double longitude() {
        return longitude;
    }

    /** The x (east) of {@code lat}, {@code lon}, degrees. */
    public double x(double lat, double lon) {
        double phi = Maths.toRadians(lat);
        double sin = Maths.sin(phi);
        double n = A / Math.sqrt(1 - E2 * sin * sin);
        return n * Maths.cos(phi) * Maths.toRadians(lon - longitude);
    }

    /** The z (south) of {@code lat}, degrees. */
    public double z(double lat) {
        return originArc - meridianArc(Maths.toRadians(lat));
    }

    /** The latitude, degrees, of a point {@code z} south of the origin. */
    public double latitude(double z) {
        double arc = originArc - z;
        // Newton on the meridian arc, from the sphere's guess
        double phi = arc / A;
        for (int i = 0; i < 6; i++) {
            double sin = Maths.sin(phi);
            double w = 1 - E2 * sin * sin;
            double radius = A * (1 - E2) / (w * Math.sqrt(w)); // the meridian's radius of curvature
            phi -= (meridianArc(phi) - arc) / radius;
        }
        return Maths.toDegrees(phi);
    }

    /** The longitude, degrees, of a point {@code x} east of the origin's meridian at latitude {@code lat}. */
    public double longitude(double x, double lat) {
        double phi = Maths.toRadians(lat);
        double sin = Maths.sin(phi);
        double n = A / Math.sqrt(1 - E2 * sin * sin);
        return longitude + Maths.toDegrees(x / (n * Maths.cos(phi)));
    }

    /** How far below the origin's tangent plane the Earth's surface lies at ({@code x}, {@code z}), metres. */
    public static double drop(double x, double z) {
        double d2 = x * x + z * z;
        return d2 / (2 * EARTH_RADIUS);
    }

    /** The meridian's arc from the equator to latitude {@code phi}, radians, on WGS 84. */
    static double meridianArc(double phi) {
        double e4 = E2 * E2;
        double e6 = e4 * E2;
        return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
            - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Maths.sin(2 * phi)
            + (15 * e4 / 256 + 45 * e6 / 1024) * Maths.sin(4 * phi)
            - (35 * e6 / 3072) * Maths.sin(6 * phi));
    }
}
