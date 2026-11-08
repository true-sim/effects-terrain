package com.truesim.effects.terrain.data;

import java.util.Objects;

/**
 * The terrain's surface: a site's elevation data, procedural detail below its resolution, and the Earth's curvature,
 * as one function of a point of the local frame and the footprint it is sampled at (the spacing of the grid sampling
 * it). Tiles take their vertices' heights from it and the CPU queries read the same function, so what is placed on the
 * terrain stands where the terrain is drawn.
 *
 * <ul>
 *   <li><b>Data:</b> {@link TerrainSite#height}, each source's pyramid at the footprint's level.</li>
 *   <li><b>Detail:</b> octaves of gradient noise from the parameters' wavelength down to twice the footprint (each
 *       faded in between four and two footprints, so coarser grids leave out what they cannot show), each damped by
 *       the slope the octaves before and the data made, as erosion carves gullies and smooths cliffs, and quieter on
 *       level ground (meadows, the valley floor).</li>
 *   <li><b>Curvature:</b> the surface falls away by d^2 / 2R from the site's origin, so the far mountains sink below
 *       the horizon as they do.</li>
 * </ul>
 *
 * <p>Thread-safe: tiles build on several threads at once.
 */
public final class TerrainSurface {

    /** The step of the data's slope for the detail, metres. */
    private static final double SLOPE_STEP = 16;
    private static final long SEED = 0x7e77a1L;

    private final TerrainSite site;
    private final SurfaceParameters parameters;

    public TerrainSurface(TerrainSite site, SurfaceParameters parameters) {
        this.site = Objects.requireNonNull(site, "site");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public TerrainSite site() {
        return site;
    }

    public SurfaceParameters parameters() {
        return parameters;
    }

    /**
     * The altitude above sea level at ({@code x}, {@code z}) sampled at {@code footprint}: data and detail, without
     * the curvature's fall.
     */
    public double altitude(double x, double z, double footprint) {
        LocalFrame frame = site.frame();
        if (parameters.amplitude() <= 0 || parameters.octaves() == 0) {
            return site.heightAtLatitude(x, frame.latitude(z), footprint);
        }
        double step = Math.max(SLOPE_STEP, footprint);
        return altitude(x, z, footprint, frame.latitude(z), frame.latitude(z + step));
    }

    /**
     * {@link #altitude(double, double, double)} with the latitudes of {@code z} and of {@code z} plus the slope's step
     * ({@code max(16, footprint)} metres) given: for rows of points, whose latitudes are worked out once.
     */
    public double altitude(double x, double z, double footprint, double lat, double latNext) {
        double base = site.heightAtLatitude(x, lat, footprint);
        if (parameters.amplitude() <= 0 || parameters.octaves() == 0) {
            return base;
        }
        double step = Math.max(SLOPE_STEP, footprint);
        double gx = (site.heightAtLatitude(x + step, lat, footprint) - base) / step;
        double gz = (site.heightAtLatitude(x, latNext, footprint) - base) / step;
        return base + detail(x, z, footprint, gx, gz);
    }

    /** The step the detail's slope is taken over for samples {@code footprint} apart, metres. */
    public static double slopeStep(double footprint) {
        return Math.max(SLOPE_STEP, footprint);
    }

    /** The surface's height at ({@code x}, {@code z}) sampled at {@code footprint}: the altitude less the curvature. */
    public double height(double x, double z, double footprint) {
        return altitude(x, z, footprint) - fall(x, z);
    }

    /** How far the curvature lowers the surface at ({@code x}, {@code z}), metres (0 without curvature). */
    public double fall(double x, double z) {
        return parameters.curvature() ? LocalFrame.drop(x, z) : 0;
    }

    /** The procedural detail at ({@code x}, {@code z}) for footprint {@code footprint}, where the data slopes so. */
    double detail(double x, double z, double footprint, double gx, double gz) {
        double baseSlope = Math.sqrt(gx * gx + gz * gz);
        double dx = gx;
        double dz = gz;
        double sum = 0;
        double amplitude = parameters.amplitude();
        double wavelength = parameters.wavelength();
        double erosion = parameters.erosion();
        double[] d = SCRATCH.get();
        for (int k = 0; k < parameters.octaves(); k++) {
            double fade = Math.clamp((wavelength / footprint - 2) / 2, 0, 1);
            if (fade == 0) {
                break;
            }
            double fx = x / wavelength;
            double fz = z / wavelength;
            // value and derivatives of the octave's noise, inlined so nothing is allocated
            double n = Noise.noise(fx, fz, SEED + k, d);
            double ddx = d[0];
            double ddz = d[1];
            double damp = 1 / (1 + erosion * (dx * dx + dz * dz));
            sum += fade * amplitude * n * damp;
            dx += fade * ddx * amplitude / wavelength * damp;
            dz += fade * ddz * amplitude / wavelength * damp;
            amplitude *= 0.5;
            wavelength *= 0.5;
        }
        double t = Math.clamp((baseSlope - 0.03) / 0.27, 0, 1);
        return sum * (0.2 + 0.8 * t * t * (3 - 2 * t));
    }

    // The noise's derivatives, per thread: tiles build on several.
    private static final ThreadLocal<double[]> SCRATCH = ThreadLocal.withInitial(() -> new double[2]);

    /**
     * The height at ({@code x}, {@code z}) of the surface as a grid {@code spacing} apart draws it (the finest tiles'
     * spacing for what the camera sees nearby): between the grid's points on the two triangles of each quad, split
     * from its (+x, -z) corner to its (-x, +z) corner as the tiles are.
     */
    public double heightAt(double x, double z, double spacing) {
        double fi = Math.floor(x / spacing);
        double fj = Math.floor(z / spacing);
        double u = x / spacing - fi;
        double v = z / spacing - fj;
        double x0 = fi * spacing;
        double z0 = fj * spacing;
        double h10 = height(x0 + spacing, z0, spacing);
        double h01 = height(x0, z0 + spacing, spacing);
        if (u + v <= 1) {
            double h00 = height(x0, z0, spacing);
            return h00 + u * (h10 - h00) + v * (h01 - h00);
        }
        double h11 = height(x0 + spacing, z0 + spacing, spacing);
        return h11 + (1 - u) * (h01 - h11) + (1 - v) * (h10 - h11);
    }

    /**
     * The unit normal at ({@code x}, {@code z}) of the triangle of the grid {@code spacing} apart under it, written
     * into {@code normal} (x, y, z).
     */
    public void normalAt(double x, double z, double spacing, double[] normal) {
        double fi = Math.floor(x / spacing);
        double fj = Math.floor(z / spacing);
        double u = x / spacing - fi;
        double v = z / spacing - fj;
        double x0 = fi * spacing;
        double z0 = fj * spacing;
        double h10 = height(x0 + spacing, z0, spacing);
        double h01 = height(x0, z0 + spacing, spacing);
        double sx;
        double sz;
        if (u + v <= 1) {
            double h00 = height(x0, z0, spacing);
            sx = (h10 - h00) / spacing;
            sz = (h01 - h00) / spacing;
        } else {
            double h11 = height(x0 + spacing, z0 + spacing, spacing);
            sx = (h11 - h01) / spacing;
            sz = (h11 - h10) / spacing;
        }
        double length = Math.sqrt(sx * sx + 1 + sz * sz);
        normal[0] = -sx / length;
        normal[1] = 1 / length;
        normal[2] = -sz / length;
    }
}
