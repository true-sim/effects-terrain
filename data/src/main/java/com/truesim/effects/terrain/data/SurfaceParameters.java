package com.truesim.effects.terrain.data;

/**
 * How the terrain's surface is made from its elevation data: the procedural detail added below the data's resolution
 * (fractal noise whose finer octaves slopes damp, as erosion carves gullies and leaves ridges) and the Earth's
 * curvature.
 *
 * @param amplitude the detail's height, metres (0: none)
 * @param wavelength its longest wavelength, metres
 * @param octaves how many octaves, each half the wavelength and amplitude of the one before
 * @param erosion how strongly slopes damp the finer octaves
 * @param curvature whether the terrain falls away with the Earth's curvature from the site's origin
 * @param cliffs the relief carved into cliffs ({@link CliffParameters#NONE}: none)
 */
public record SurfaceParameters(double amplitude, double wavelength, int octaves, double erosion, boolean curvature,
    CliffParameters cliffs) {

    /** The data alone: no detail, no curvature, no cliff relief. */
    public static final SurfaceParameters DATA = new SurfaceParameters(0, 64, 0, 0, false);

    public SurfaceParameters {
        if (!(amplitude >= 0) || !(wavelength > 0) || octaves < 0 || octaves > 16 || !(erosion >= 0)) {
            throw new IllegalArgumentException("Surface parameters out of range: amplitude " + amplitude
                + ", wavelength " + wavelength + ", octaves " + octaves + ", erosion " + erosion);
        }
        java.util.Objects.requireNonNull(cliffs, "cliffs");
    }

    /** Detail and curvature without cliff relief. */
    public SurfaceParameters(double amplitude, double wavelength, int octaves, double erosion, boolean curvature) {
        this(amplitude, wavelength, octaves, erosion, curvature, CliffParameters.NONE);
    }

    /** These with the cliff relief {@code relief}. */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public SurfaceParameters withCliffs(CliffParameters relief) {
        return new SurfaceParameters(amplitude, wavelength, octaves, erosion, curvature, relief);
    }
}
