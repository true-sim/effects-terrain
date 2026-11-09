package com.truesim.effects.terrain.data;

/**
 * The relief the surface carves into its cliffs ({@link TerrainSurface}), where the data's slope passes
 * {@code slopeStart} degrees, all of it from {@code slopeEnd}: what elevation data of 2 m cannot hold of a limestone
 * wall. Each feature is a horizontal shift of the face, out of the wall or into it, made by reading the data's height a
 * little up or down the slope (so a face never rises above the rim it hangs from, nor sinks below the floor at its
 * foot):
 *
 * <ul>
 *   <li><b>Smoothing:</b> the data's height on the walls averaged over {@code smoothing} metres (a box over its
 *       bilinear samples: no creases at the data's samples, which on a near-vertical face run down it as streaks).</li>
 *   <li><b>Bedding:</b> near-horizontal beds {@code bedThickness} metres thick (varying by {@code bedVariation} of
 *       that), dipping {@code dip} degrees towards azimuth {@code dipAzimuth} (clockwise from north) and waving by
 *       {@code waviness} metres over {@code wavinessLength}: each bed's top a ledge {@code ledge} metres wide (at most
 *       three quarters of its height on the wall), its face below steeper than the wall, leaning out over the bed below:
 *       an overhang-like step. (A heightfield holds no true overhang; its steps are as steep as its grid allows.)</li>
 *   <li><b>Joints:</b> two vertical sets at right angles, the first striking {@code jointAzimuth} degrees, about
 *       {@code jointSpacing} metres apart: clefts {@code jointWidth} wide, {@code jointDepth} deep, some missing.</li>
 *   <li><b>Buttresses:</b> ribs and bays {@code buttressDepth} metres out and in, about {@code buttressLength} apart
 *       along the wall.</li>
 * </ul>
 *
 * <p>Each feature fades out on grids too coarse to show it (as the detail's octaves do), so every level of tiles holds
 * what it can draw, and tiles morph between them.
 *
 * @param slopeStart the slope the relief starts at, degrees
 * @param slopeEnd the slope it is whole at, degrees
 * @param smoothing the width the data is averaged over on the walls, metres (0: none; the data's spacing: no creases)
 * @param bedThickness the beds' thickness, metres
 * @param bedVariation how much it varies from bed to bed, 0 to 1
 * @param ledge the ledges' width, metres
 * @param dip the beds' dip, degrees
 * @param dipAzimuth the direction they dip to, degrees clockwise from north
 * @param waviness how far the beds wave up and down, metres
 * @param wavinessLength over what length, metres
 * @param jointSpacing the joints' spacing, metres
 * @param jointWidth their width, metres
 * @param jointDepth their depth, metres
 * @param jointAzimuth the first set's strike, degrees clockwise from north
 * @param buttressDepth how far ribs stand out and bays go in, metres
 * @param buttressLength their spacing along the wall, metres
 * @param relief where the relief goes: into the heightfield, or into the tiles' meshes as true displacement
 */
public record CliffParameters(double slopeStart, double slopeEnd, double smoothing, double bedThickness,
    double bedVariation, double ledge, double dip, double dipAzimuth, double waviness, double wavinessLength,
    double jointSpacing, double jointWidth, double jointDepth, double jointAzimuth, double buttressDepth,
    double buttressLength, Relief relief) {

    /** Where the relief goes. */
    public enum Relief {
        /**
         * Into the surface's height (the face shifted by reading the data up or down its slope): a heightfield still, so
         * the CPU queries meet it exactly and it costs no vertices; but a wall near vertical holds a bed only where the
         * grid has vertices enough up it (the footprint times the slope's rise), so its beds show on slopes, not walls.
         */
        HEIGHT,
        /**
         * Into the tiles' meshes: their steep triangles bisected until no edge rises more than a step, every vertex
         * on a wall moved out of it or into it horizontally ({@link TerrainSurface#cliffPoint}): beds with ledges and
         * real overhangs at any steepness. The surface's height keeps the data (smoothed on the walls); the CPU
         * queries are the heightfield's, within the relief's few metres of the drawn wall.
         */
        MESH
    }

    /** No relief: the data's faces as they are. */
    public static final CliffParameters NONE = new CliffParameters(55, 60, 0, 6, 0, 0, 0, 0, 0, 100, 12, 1, 0, 0, 0,
        60, Relief.HEIGHT);

    /** Limestone walls as Lauterbrunnen's: beds of about 9 m, ledges 1.5 m wide, joints every 24 m, ribs of 4 m. */
    public static final CliffParameters LIMESTONE = new CliffParameters(55, 60, 2, 9, 0.45, 1.5, 4, 150, 2.5, 140, 24,
        2.5, 2, 25, 4, 90, Relief.MESH);

    public CliffParameters {
        java.util.Objects.requireNonNull(relief, "relief");
        if (!(slopeStart >= 0) || !(slopeEnd > slopeStart) || slopeEnd > 90 || !(smoothing >= 0) || !(bedThickness > 0)
            || !(bedVariation >= 0 && bedVariation <= 1) || !(ledge >= 0)
            || !(Math.abs(dip) < 45) || !Double.isFinite(dipAzimuth) || !(waviness >= 0) || !(wavinessLength > 0)
            || !(jointSpacing > 0) || !(jointWidth > 0) || !(jointDepth >= 0) || !Double.isFinite(jointAzimuth)
            || !(buttressDepth >= 0) || !(buttressLength > 0)) {
            throw new IllegalArgumentException("Cliff parameters out of range: " + slopeStart + " to " + slopeEnd
                + " degrees, smoothing " + smoothing + " m, beds " + bedThickness + " m (" + bedVariation + "), ledge " + ledge
                + " m, dip " + dip + ", waviness " + waviness + " over " + wavinessLength + ", joints " + jointSpacing
                + " m (" + jointWidth + " wide, " + jointDepth + " deep), buttresses " + buttressDepth + " over "
                + buttressLength);
        }
    }

    /** Whether there is any relief. */
    public boolean any() {
        return smoothing > 0 || ledge > 0 || jointDepth > 0 || buttressDepth > 0;
    }

    /** These with the relief in {@code where}. */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public CliffParameters withRelief(Relief where) {
        return new CliffParameters(slopeStart, slopeEnd, smoothing, bedThickness, bedVariation, ledge, dip, dipAzimuth,
            waviness, wavinessLength, jointSpacing, jointWidth, jointDepth, jointAzimuth, buttressDepth, buttressLength,
            where);
    }

    /** These with the data averaged over {@code metres} on the walls. */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public CliffParameters withSmoothing(double metres) {
        return new CliffParameters(slopeStart, slopeEnd, metres, bedThickness, bedVariation, ledge, dip, dipAzimuth,
            waviness, wavinessLength, jointSpacing, jointWidth, jointDepth, jointAzimuth, buttressDepth, buttressLength,
            relief);
    }

    /** These with ledges {@code metres} wide (0: no bedding). */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public CliffParameters withLedge(double metres) {
        return new CliffParameters(slopeStart, slopeEnd, smoothing, bedThickness, bedVariation, metres, dip, dipAzimuth,
            waviness, wavinessLength, jointSpacing, jointWidth, jointDepth, jointAzimuth, buttressDepth, buttressLength,
            relief);
    }

    /** These with joints {@code metres} deep (0: none). */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public CliffParameters withJointDepth(double metres) {
        return new CliffParameters(slopeStart, slopeEnd, smoothing, bedThickness, bedVariation, ledge, dip, dipAzimuth,
            waviness, wavinessLength, jointSpacing, jointWidth, metres, jointAzimuth, buttressDepth, buttressLength,
            relief);
    }

    /** These with buttresses {@code metres} deep (0: none). */
    @com.truesim.annotations.AllowAllocation("parameters, made rarely")
    public CliffParameters withButtressDepth(double metres) {
        return new CliffParameters(slopeStart, slopeEnd, smoothing, bedThickness, bedVariation, ledge, dip, dipAzimuth,
            waviness, wavinessLength, jointSpacing, jointWidth, jointDepth, jointAzimuth, metres, buttressLength,
            relief);
    }

    /** How much of the relief a face of {@code slope} degrees takes: 0 below the start, 1 from the end, smooth between. */
    public double weight(double slope) {
        double t = Math.clamp((slope - slopeStart) / (slopeEnd - slopeStart), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
