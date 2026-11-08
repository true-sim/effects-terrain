package com.truesim.effects.terrain.data;

import com.truesim.common.math.Maths;

/**
 * Gradient noise in the plane with its derivatives, in doubles: the same value for the same point and seed on every
 * machine, so neighbouring tiles agree where they meet. Values lie within about [-0.7, 0.7].
 */
final class Noise {

    private Noise() {
    }

    /**
     * The noise at ({@code x}, {@code y}) for {@code seed}, with its derivatives written into {@code d} (d/dx, d/dy)
     * unless it is null; returns the value.
     */
    static double noise(double x, double y, long seed, double[] d) {
        double fx = Math.floor(x);
        double fy = Math.floor(y);
        long ix = (long) fx;
        long iy = (long) fy;
        double u = x - fx;
        double v = y - fy;
        // quintic fade and its derivative
        double su = u * u * u * (u * (u * 6 - 15) + 10);
        double sv = v * v * v * (v * (v * 6 - 15) + 10);
        double dsu = 30 * u * u * (u * (u - 2) + 1);
        double dsv = 30 * v * v * (v * (v - 2) + 1);
        long h00 = hash(ix, iy, seed);
        long h10 = hash(ix + 1, iy, seed);
        long h01 = hash(ix, iy + 1, seed);
        long h11 = hash(ix + 1, iy + 1, seed);
        double g00x = gx(h00);
        double g00y = gy(h00);
        double g10x = gx(h10);
        double g10y = gy(h10);
        double g01x = gx(h01);
        double g01y = gy(h01);
        double g11x = gx(h11);
        double g11y = gy(h11);
        double a = g00x * u + g00y * v;
        double b = g10x * (u - 1) + g10y * v;
        double c = g01x * u + g01y * (v - 1);
        double e = g11x * (u - 1) + g11y * (v - 1);
        double k1 = b - a;
        double k2 = c - a;
        double k3 = a - b - c + e;
        double value = a + k1 * su + k2 * sv + k3 * su * sv;
        if (d != null) {
            // the corners' gradients interpolated, plus the fade's derivative times the corner differences
            d[0] = g00x + (g10x - g00x) * su + (g01x - g00x) * sv + (g00x - g10x - g01x + g11x) * su * sv
                + dsu * (k1 + k3 * sv);
            d[1] = g00y + (g10y - g00y) * su + (g01y - g00y) * sv + (g00y - g10y - g01y + g11y) * su * sv
                + dsv * (k2 + k3 * su);
        }
        return value;
    }

    private static double gx(long h) {
        return Maths.cos(angle(h));
    }

    private static double gy(long h) {
        return Maths.sin(angle(h));
    }

    private static double angle(long h) {
        return (h >>> 11) * (2 * Math.PI / (1L << 53));
    }

    /** A 64-bit hash of a lattice point and seed (SplitMix64's finalizer over a combination). */
    static long hash(long x, long y, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
