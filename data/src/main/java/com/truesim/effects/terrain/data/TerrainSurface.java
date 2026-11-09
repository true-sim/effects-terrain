package com.truesim.effects.terrain.data;

import com.truesim.common.math.Maths;
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
 *   <li><b>Modifiers</b> ({@link TerrainModifiers}): river beds, roads' cuts and fills, levelled squares, in order,
 *       each given the height the ones before left (without the curvature's fall); and the surface materials they lay
 *       ({@link #materials}).</li>
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
    private final TerrainModifiers modifiers;

    public TerrainSurface(TerrainSite site, SurfaceParameters parameters) {
        this(site, parameters, TerrainModifiers.NONE);
    }

    /** The surface of {@code site} changed by {@code modifiers}. */
    public TerrainSurface(TerrainSite site, SurfaceParameters parameters, TerrainModifiers modifiers) {
        this.site = Objects.requireNonNull(site, "site");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.modifiers = Objects.requireNonNull(modifiers, "modifiers");
    }

    /** This surface with {@code newModifiers} in place of its own (the data and detail as they are). */
    @com.truesim.annotations.AllowAllocation("a new surface, rarely")
    public TerrainSurface withModifiers(TerrainModifiers newModifiers) {
        return new TerrainSurface(site, parameters, newModifiers);
    }

    /** Its modifiers. */
    public TerrainModifiers modifiers() {
        return modifiers;
    }

    /** This surface without modifiers: the data and detail alone (what modifiers are worked out from). */
    @com.truesim.annotations.AllowAllocation("a new surface, rarely")
    public TerrainSurface unmodified() {
        return modifiers.isEmpty() ? this : new TerrainSurface(site, parameters, TerrainModifiers.NONE);
    }

    /**
     * The surface materials the modifiers lay at ({@code x}, {@code z}) sampled at {@code footprint}, into
     * {@code cover} ({@link TerrainModifier#MATERIALS} entries: gravel, cobbles, dirt, sand); returns their total
     * cover, 0 to 1 (0 where none: the terrain's own layers).
     */
    public double materials(double x, double z, double footprint, double[] cover) {
        return modifiers.materials(x, z, footprint, cover);
    }

    /** {@code altitude} at ({@code x}, {@code z}) through the modifiers (which work on the height). */
    private double modified(double x, double z, double footprint, double altitude) {
        if (modifiers.isEmpty()) {
            return altitude;
        }
        double fall = fall(x, z);
        return modifiers.height(x, z, footprint, altitude - fall) + fall;
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
        if (!detailed() && !parameters.cliffs().any()) {
            return modified(x, z, footprint, site.heightAtLatitude(x, frame.latitude(z), footprint));
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
        boolean relief = parameters.cliffs().any();
        if (!detailed() && !relief) {
            return modified(x, z, footprint, base);
        }
        double step = Math.max(SLOPE_STEP, footprint);
        double gx = (site.heightAtLatitude(x + step, lat, footprint) - base) / step;
        double gz = (site.heightAtLatitude(x, latNext, footprint) - base) / step;
        double h = relief ? cliff(x, z, footprint, base, gx, gz, lat, latNext, step) : base;
        if (!detailed()) {
            return modified(x, z, footprint, h);
        }
        return modified(x, z, footprint, h + detail(x, z, footprint, gx, gz));
    }

    private boolean detailed() {
        return parameters.amplitude() > 0 && parameters.octaves() > 0;
    }

    /**
     * The data's height at ({@code x}, {@code z}) with the cliff relief ({@link CliffParameters}) carved in, given the
     * data's height {@code base} there, its slope ({@code gx}, {@code gz}) over {@code step} metres and the latitudes
     * of {@code z} and {@code z + step}: the face shifted horizontally, out of the wall or into it, by reading the
     * data a little up or down its slope.
     */
    double cliff(double x, double z, double footprint, double base, double gx, double gz, double lat, double latNext,
        double step) {
        CliffParameters c = parameters.cliffs();
        double g = Math.sqrt(gx * gx + gz * gz);
        double weight = c.weight(Maths.toDegrees(Maths.atan(g)));
        if (weight <= 0) {
            return base;
        }
        double shift = c.relief() == CliffParameters.Relief.HEIGHT ? cliffShift(x, z, footprint, base, gx, gz) : 0;
        double sx = shift * gx / g;
        double sz = shift * gz / g;
        // latitudes linear over the slope's step (metres north-south are near linear in latitude)
        double perMetre = (latNext - lat) / step;
        double lat0 = lat + perMetre * sz;
        double r = 0.5 * c.smoothing();
        if (r <= 0) {
            return shift == 0 ? base : site.heightAtLatitude(x + sx, lat0, footprint);
        }
        // a box over the bilinear samples: a quadratic B-spline over the data's grid, no creases
        double south = lat0 + perMetre * r;
        double north = lat0 - perMetre * r;
        double smooth = 0.25 * (site.heightAtLatitude(x + sx - r, north, footprint)
            + site.heightAtLatitude(x + sx + r, north, footprint) + site.heightAtLatitude(x + sx - r, south, footprint)
            + site.heightAtLatitude(x + sx + r, south, footprint));
        return base + weight * (smooth - base);
    }

    /**
     * How far the cliff relief shifts the face at ({@code x}, {@code z}) horizontally, metres up its slope (out of the
     * wall: positive) or down it (into the wall: negative), for a grid {@code footprint} apart; 0 where the slope
     * ({@code gx}, {@code gz}) is below the relief's.
     */
    public double cliffShift(double x, double z, double footprint, double base, double gx, double gz) {
        CliffParameters c = parameters.cliffs();
        double g = Math.sqrt(gx * gx + gz * gz);
        double weight = c.weight(Maths.toDegrees(Maths.atan(g)));
        if (weight <= 0 || g <= 0) {
            return 0;
        }
        double shift = 0;
        // Bedding: terraces of the beds, each bed's top a ledge `ledge` wide (as far as its thickness allows), its
        // face below steeper than the wall: the bed leans out over the one below
        double t = c.bedThickness();
        double tread = Math.min(c.ledge() * g / t, 0.75); // the share of each bed's height the ledge takes
        // a bed shows where the grid has vertices enough up the wall: a bed's height over the rise between two
        double fade = fade(t, footprint * Math.max(g, 1));
        if (tread > 0 && fade > 0) {
            double phase = bedPhase(x, z, base, c);
            double riser = 1 - tread;
            // the staircase: up the riser to the bed's top, then level along the ledge, its corners rounded
            double stair = softMin(phase / riser, 1, 0.12);
            shift += weight * fade * t * (stair - phase) / g;
        }
        // Joints: two vertical sets at right angles, clefts into the wall, some missing
        double width = c.jointWidth();
        fade = fade(width, footprint);
        if (c.jointDepth() > 0 && fade > 0) {
            double a = Maths.toRadians(c.jointAzimuth());
            double cleft = Math.max(joint(x * Maths.cos(a) + z * Maths.sin(a), x, z, c, 0),
                joint(-x * Maths.sin(a) + z * Maths.cos(a), x, z, c, 1));
            shift -= weight * fade * c.jointDepth() * cleft;
        }
        // Buttresses: ribs out of the wall and bays into it
        fade = fade(c.buttressLength() / 4, footprint);
        if (c.buttressDepth() > 0 && fade > 0) {
            double l = c.buttressLength();
            double n = Noise.noise(x / l, z / l, SEED + 103, null) + 0.5 * Noise.noise(2 * x / l, 2 * z / l,
                SEED + 104, null);
            shift += weight * fade * c.buttressDepth() * n;
        }
        return shift;
    }

    /**
     * Where ({@code x}, {@code z}) at {@code altitude} is in its bed: 0 at the bed's foot to 1 at its top (the beds
     * dipping, waving and varying in thickness as {@code c} says).
     */
    private static double bedPhase(double x, double z, double altitude, CliffParameters c) {
        double q = bedCoordinate(x, z, altitude, c);
        return q - Math.floor(q);
    }

    /** Which bed ({@code x}, {@code z}) at {@code altitude} is in, and how far up it: the integer part, the fraction. */
    private static double bedCoordinate(double x, double z, double altitude, CliffParameters c) {
        double q = bedHeight(x, z, altitude, c) / c.bedThickness();
        // beds of uneven thickness: two octaves of noise along the bed count (the coordinate still rising with the
        // altitude at the default variation, 0.6: the noise's slope stays under 1 / 0.6 of it)
        return q + c.bedVariation() * (0.9 * Noise.noise(q / 2.3, 0.5, SEED + 102, null)
            + 0.35 * Noise.noise(q / 0.9, 0.5, SEED + 107, null));
    }

    /**
     * The height of the bedding plane through ({@code x}, {@code z}) at {@code altitude}, metres: the altitude with the
     * beds' dip and waviness taken out, the same along a plane. A mesh whose rows run at levels of it samples every bed
     * alike along a wall ({@code CliffMesh}); rows level in altitude cross the dipping beds and sample them in dashes.
     */
    public double bedHeight(double x, double z, double altitude) {
        return bedHeight(x, z, altitude, parameters.cliffs());
    }

    private static double bedHeight(double x, double z, double altitude, CliffParameters c) {
        double a = Maths.toRadians(c.dipAzimuth());
        double along = x * Maths.sin(a) - z * Maths.cos(a); // towards the dip
        return altitude + Maths.tan(Maths.toRadians(c.dip())) * along
            + c.waviness() * 1.4 * Noise.noise(x / c.wavinessLength(), z / c.wavinessLength(), SEED + 101, null);
    }

    /**
     * The surface's point at ({@code x}, {@code z}) for a grid {@code footprint} apart with the cliff relief in the
     * mesh ({@link CliffParameters.Relief#MESH}): its height (as {@link #height}) into {@code out[1]}, and the
     * horizontal shift of the wall there into {@code out[0]} (x) and {@code out[2]} (z): out of the wall where
     * positive along its outward normal; the data's slope there (its rise per metre) into {@code out[3]}; on a cliff,
     * the moved wall's unit normal into {@code out[4]} to {@code out[6]} (seven entries).
     * {@code vertical} is the mesh's vertices' spacing up the wall there, metres (0 or less: the footprint times the
     * slope's rise, a grid's): beds closer than it can hold fade out. Returns how much of a cliff the point is (0:
     * none, no shift).
     */
    public double cliffPoint(double x, double z, double footprint, double vertical, double[] out) {
        LocalFrame frame = site.frame();
        double step = Math.max(SLOPE_STEP, footprint);
        double lat = frame.latitude(z);
        double latNext = frame.latitude(z + step);
        double base = site.heightAtLatitude(x, lat, footprint);
        double gx = (site.heightAtLatitude(x + step, lat, footprint) - base) / step;
        double gz = (site.heightAtLatitude(x, latNext, footprint) - base) / step;
        CliffParameters c = parameters.cliffs();
        double h = c.any() ? cliff(x, z, footprint, base, gx, gz, lat, latNext, step) : base;
        if (detailed()) {
            h += detail(x, z, footprint, gx, gz);
        }
        double altitude = modified(x, z, footprint, h);
        out[0] = 0;
        out[1] = altitude - fall(x, z);
        out[2] = 0;
        double g = Math.sqrt(gx * gx + gz * gz);
        out[3] = g;
        if (c.relief() != CliffParameters.Relief.MESH || g <= 0) {
            return 0;
        }
        double weight = c.weight(Maths.toDegrees(Maths.atan(g)));
        if (weight <= 0) {
            return 0;
        }
        double spacing = vertical > 0 ? vertical : footprint * Math.max(g, 1);
        double d = weight * meshShift(x, z, footprint, spacing, altitude, c);
        double ux = gx / g; // up the slope
        double uz = gz / g;
        out[0] = -d * ux;
        out[2] = -d * uz;
        // The moved wall's normal: its tangents up the slope and along it, each with the shift's change that way
        double da = 0.1;
        double up = weight * (meshShift(x, z, footprint, spacing, altitude + da, c)
            - meshShift(x, z, footprint, spacing, altitude - da, c)) / (2 * da) * g; // per metre in, up the slope
        double ds = 0.25;
        double along = weight * (meshShift(x - uz * ds, z + ux * ds, footprint, spacing, altitude, c)
            - meshShift(x + uz * ds, z - ux * ds, footprint, spacing, altitude, c)) / (2 * ds);
        double upX = ux * (1 - up);
        double upY = g;
        double upZ = uz * (1 - up);
        double alX = -uz - ux * along;
        double alZ = ux - uz * along;
        // along x up
        double nx = -alZ * upY;
        double ny = alZ * upX - alX * upZ;
        double nz = alX * upY;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        out[4] = nx / l;
        out[5] = ny / l;
        out[6] = nz / l;
        return weight;
    }

    /**
     * How much of a cliff ({@link CliffParameters#weight}) the data's slope over {@code max(16, footprint)} metres makes
     * at ({@code x}, {@code z}): where the mesh's relief goes (0: none).
     */
    public double cliffWeight(double x, double z, double footprint) {
        CliffParameters c = parameters.cliffs();
        if (!c.any()) {
            return 0;
        }
        LocalFrame frame = site.frame();
        double step = Math.max(SLOPE_STEP, footprint);
        double lat = frame.latitude(z);
        double base = site.heightAtLatitude(x, lat, footprint);
        double gx = (site.heightAtLatitude(x + step, lat, footprint) - base) / step;
        double gz = (site.heightAtLatitude(x, frame.latitude(z + step), footprint) - base) / step;
        return c.weight(Maths.toDegrees(Maths.atan(Math.sqrt(gx * gx + gz * gz))));
    }

    /**
     * The mesh relief's shift out of the wall at ({@code x}, {@code z}) and {@code altitude}, metres: the beds (a soft
     * band at each one's foot weathered back, so the hard bed above overhangs it and the one below ends in a ledge;
     * each hard bed leaning back a little as it rises), the joints' clefts, the buttresses.
     */
    static double meshShift(double x, double z, double footprint, double vertical, double altitude,
        CliffParameters c) {
        double shift = 0;
        double fade = fade(0.3 * c.bedThickness(), vertical);
        if (c.ledge() > 0 && fade > 0) {
            double q = bedCoordinate(x, z, altitude, c);
            double bed = Math.floor(q);
            double phase = q - bed;
            // each bed its own: how deep its soft band is weathered (some not at all), how thick that band is,
            // how much it leans; and along the wall the notches deepen and fade
            long h = Noise.hash((long) bed, 7, SEED + 105);
            double depth = (h >>> 11) * 0x1.0p-53;
            depth = depth < 0.35 ? 0 : 0.2 + 0.8 * (depth - 0.35) / 0.65;
            double band = 0.18 + 0.2 * ((h >>> 3) & 0xff) / 255.0;
            double lean = 0.15 + 0.4 * ((h >>> 20) & 0xff) / 255.0;
            // along the wall each bed fades in and out at places of its own
            double lateral = Math.clamp(0.25 + 1.9 * Noise.noise(x / 45 + bed * 3.7, z / 45 - bed * 2.3, SEED + 106,
                null), 0, 1);
            // the notch's edges at least two and a half vertices' spacing up the wall: every column samples them
            // alike, the lip continuous (sharper, it falls between rows here and on them there: dashes); a band
            // the vertices cannot hold fades out
            // and along the wall the lip's depth and the notch's height wander (a few metres to tens), so a ledge
            // reads as an irregular lip, not a ruled line
            double wander = Noise.noise(x / 13 + bed * 1.7, z / 13 - bed * 0.9, SEED + 108, null);
            band = Math.max(0.08, band + 0.2 * wander);
            lateral *= Math.clamp(0.7 + 1.4 * Noise.noise(x / 9 - bed * 2.1, z / 9 + bed * 1.3, SEED + 109, null),
                0, 1);
            // the lip's underside: from a sharp step to a sloping roof (its shadow thin here, broad there)
            double roof = 1 + 2.5 * Math.abs(Noise.noise(x / 7 + bed * 0.7, z / 7 + bed * 2.9, SEED + 110, null));
            double edge = Math.max(0.1, 2.5 * vertical / c.bedThickness()) * roof;
            double held = Math.clamp((band * roof / edge - 1) / 1.5, 0, 1);
            double base = Math.max(0.1, 2.5 * vertical / c.bedThickness());
            double notch = smooth(0, base, phase) * (1 - smooth(band, band + edge, phase)) * depth * lateral * held;
            // each hard bed leaning back as it rises, then stepping out again to the bed above: the step as smooth as
            // the notch's edges (a sudden step fell between rows here and on them there), its size wandering along
            // the wall like the notches'
            double rise = 1 - base;
            double saw = phase < rise ? phase / rise : 1 - smooth(rise, 1, phase);
            double leaning = lean * Math.clamp(0.3 + 1.4 * Noise.noise(x / 11 + bed * 1.1, z / 11 - bed * 1.9,
                SEED + 111, null), 0, 1);
            shift += fade * c.ledge() * (0.5 + leaning * (0.5 - saw) - notch);
        }
        fade = fade(c.jointWidth(), footprint);
        if (c.jointDepth() > 0 && fade > 0) {
            double a = Maths.toRadians(c.jointAzimuth());
            double cleft = Math.max(joint(x * Maths.cos(a) + z * Maths.sin(a), x, z, c, 0),
                joint(-x * Maths.sin(a) + z * Maths.cos(a), x, z, c, 1));
            shift -= fade * c.jointDepth() * cleft;
        }
        fade = fade(c.buttressLength() / 4, footprint);
        if (c.buttressDepth() > 0 && fade > 0) {
            double l = c.buttressLength();
            double n = Noise.noise(x / l, z / l, SEED + 103, null) + 0.5 * Noise.noise(2 * x / l, 2 * z / l,
                SEED + 104, null);
            shift += fade * c.buttressDepth() * n;
        }
        return shift;
    }

    private static double smooth(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * How much of a joint of {@code c} at {@code across} metres across its set ({@code set}) the point ({@code x},
     * {@code z}) is in: 1 at its middle, 0 outside.
     */
    private static double joint(double across, double x, double z, CliffParameters c, int set) {
        double spacing = c.jointSpacing();
        double u = across / spacing + 0.35 * Noise.noise(x / (3 * spacing), z / (3 * spacing), SEED + 110 + set,
            null);
        double nearest = Math.rint(u);
        long h = Noise.hash((long) nearest, set, SEED + 120);
        if ((h >>> 11) * 0x1.0p-53 > 0.6) {
            return 0;
        }
        double d = Math.abs(u - nearest) * spacing / (0.5 * c.jointWidth());
        if (d >= 1) {
            return 0;
        }
        double k = 1 - d * d;
        return k * k;
    }

    /** How much of a feature {@code size} metres across a grid {@code footprint} apart shows: none below 1.5 grid steps, all from 3. */
    static double fade(double size, double footprint) {
        return Math.clamp((size / footprint - 1.5) / 1.5, 0, 1);
    }

    /** The smaller of {@code a} and {@code b}, rounded over {@code k} (polynomial smooth minimum). */
    private static double softMin(double a, double b, double k) {
        double h = Math.clamp(0.5 + 0.5 * (b - a) / k, 0, 1);
        return b + (a - b) * h - k * h * (1 - h);
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
