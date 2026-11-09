package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.terrain.data.TerrainSurface;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A tile's cliffs with their relief in the mesh ({@code CliffParameters.Relief.MESH}): a heightfield's grid holds a wall
 * of 300 m on a few vertices up it (its footprint times the slope's rise apart), so the tile's steep triangles are
 * bisected until no edge rises more than {@link Settings#step} (every vertex sampled from the surface where it lies),
 * and every vertex on a wall is moved out of it or into it horizontally by the surface's relief
 * ({@link TerrainSurface#cliffPoint}: ledges, overhangs, joints, buttresses), its normal taken from the moved surface.
 *
 * <p><b>Refining.</b> Each edge of the grid's triangles is cut into as many equal pieces as it rises steps (on a
 * cliff), its points sampled from the surface. A triangle with cut edges is triangulated between its edge that rises
 * most and the other two (they rise as much together): a zipper up the two sides, each triangle a row of the wall,
 * so a wall of {@code H} metres takes about {@code 2 H / step} triangles a grid triangle, its vertices every step up it
 * (halving edges recursively instead grows as {@code (H / step)^1.6}: a 128 m tile filled 150,000 vertices).
 *
 * <p><b>No cracks.</b> How an edge is cut is a function of its two ends alone (how far it rises, whether its middle is
 * on a cliff), and its points functions of where they lie, so two triangles sharing an edge cut it alike: in a tile,
 * and between tiles of a level (their borders are the same edges). Between levels the borders' vertices differ as the
 * grids do; the skirts hang from every vertex of a border, refined ones too, and close what is left as they close the
 * levels' gaps.
 */
@AllowAllocation("builds tiles in the background")
public final class CliffMesh {

    /**
     * How the cliffs' meshes refine.
     *
     * @param step how far up a wall an edge of the finest level may rise at most, metres; each coarser level's
     *     edges twice as far (its vertices as far apart up the walls as along them, relatively)
     * @param levels how many of the finest levels refine (coarser tiles keep their grid, the relief moving its
     *     vertices)
     * @param rounds how many pieces an edge may be cut into: 2 to this power
     * @param maxVertices the most vertices a tile may have: refining stops there
     */
    public record Settings(double step, int levels, int rounds, int maxVertices) {

        /** The settings' defaults. */
        public static final Settings DEFAULTS = new Settings(0.4, 3, 9, 150_000);

        /** The step of {@code level}'s edges, metres. */
        public double step(int level) {
            return step * (1 << level);
        }

        public Settings {
            if (!(step > 0) || levels < 0 || rounds < 0 || maxVertices < 1) {
                throw new IllegalArgumentException("Cliff mesh settings out of range: step " + step + ", levels "
                    + levels + ", rounds " + rounds + ", vertices " + maxVertices);
            }
        }
    }

    /** A tile's vertices, growing as its cliffs refine: attributes as the mesh has them, and where each lies. */
    static final class Vertices {
        int count;
        float[] positions;
        float[] normals;
        float[] uv0;
        float[] uv1;
        float[] colors;
        float[] tangents;
        /** Where each lies on the tile, metres from its origin (before the relief moves it). */
        double[] px;
        double[] pz;
        /** How much of a cliff each is (0: not moved). */
        double[] weight;
        /** Where refining put each up its edge: the height between the edge's ends it was cut at (NaN: a grid vertex). */
        double[] cut;
        /** Each vertex's bedding-plane height once worked out (NaN before). */
        double[] bed;

        Vertices(int capacity) {
            positions = new float[3 * capacity];
            normals = new float[3 * capacity];
            uv0 = new float[2 * capacity];
            uv1 = new float[2 * capacity];
            colors = new float[4 * capacity];
            tangents = new float[4 * capacity];
            px = new double[capacity];
            pz = new double[capacity];
            weight = new double[capacity];
            cut = new double[capacity];
            Arrays.fill(cut, Double.NaN);
            bed = new double[capacity];
            Arrays.fill(bed, Double.NaN);
        }

        void ensure(int capacity) {
            if (px.length >= capacity) {
                return;
            }
            int c = Math.max(capacity, px.length * 3 / 2);
            positions = Arrays.copyOf(positions, 3 * c);
            normals = Arrays.copyOf(normals, 3 * c);
            uv0 = Arrays.copyOf(uv0, 2 * c);
            uv1 = Arrays.copyOf(uv1, 2 * c);
            colors = Arrays.copyOf(colors, 4 * c);
            tangents = Arrays.copyOf(tangents, 4 * c);
            px = Arrays.copyOf(px, c);
            pz = Arrays.copyOf(pz, c);
            weight = Arrays.copyOf(weight, c);
            int old = cut.length;
            cut = Arrays.copyOf(cut, c);
            Arrays.fill(cut, old, c, Double.NaN);
            bed = Arrays.copyOf(bed, c);
            Arrays.fill(bed, old, c, Double.NaN);
        }
    }

    private final TerrainSurface surface;
    private final Settings settings;
    private final double step;
    private final double x0;
    private final double z0;
    private final double spacing;
    private final double px0;
    private final double pz0;
    private final Vertices verts;
    /** Each edge (its ends, the smaller first) to its points from the smaller end to the larger, both ends included. */
    private final Map<Long, int[]> edges = new HashMap<>();
    private final double[] point = new double[7];

    /**
     * The cliffs of the tile at ({@code x0}, {@code z0}) whose grid is {@code spacing} apart, its planar position's
     * origin ({@code px0}, {@code pz0}), its vertices {@code verts} (the grid's, which this adds to).
     */
    CliffMesh(TerrainSurface surface, Settings settings, int level, double x0, double z0, double spacing, double px0,
        double pz0, Vertices verts) {
        this.surface = surface;
        this.settings = settings;
        this.step = settings.step(level);
        this.x0 = x0;
        this.z0 = z0;
        this.spacing = spacing;
        this.px0 = px0;
        this.pz0 = pz0;
        this.verts = verts;
    }

    /** Whether the apron grid {@code height} ({@code a} a side, {@code s} apart) has a slope past {@code degrees}. */
    static boolean steep(double[] height, int a, double s, double degrees) {
        double rise = Maths.tan(Maths.toRadians(Math.clamp(degrees, 0, 89)));
        double limit = rise * rise * 4 * s * s;
        for (int j = 1; j < a - 1; j++) {
            for (int i = 1; i < a - 1; i++) {
                int c = j * a + i;
                double dx = height[c + 1] - height[c - 1];
                double dz = height[c + a] - height[c - a];
                if (dx * dx + dz * dz > limit) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The triangles {@code triangles} (three indices each) refined: each edge cut where it rises too far. */
    int[] refine(int[] triangles) {
        int[] out = new int[triangles.length * 2];
        int t = 0;
        int[] chain = new int[64];
        double[] progressA = new double[64];
        double[] progressB = new double[64];
        for (int k = 0; k < triangles.length; k += 3) {
            int v0 = triangles[k];
            int v1 = triangles[k + 1];
            int v2 = triangles[k + 2];
            int[] e0 = edge(v0, v1);
            int[] e1 = edge(v1, v2);
            int[] e2 = edge(v2, v0);
            if (e0.length == 2 && e1.length == 2 && e2.length == 2) {
                if (t + 3 > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                t = put(out, t, v0, v1, v2);
                continue;
            }
            double h0 = height(v0);
            double h1 = height(v1);
            double h2 = height(v2);
            double r0 = Math.abs(h1 - h0);
            double r1 = Math.abs(h2 - h1);
            double r2 = Math.abs(h0 - h2);
            // the edge rising most, p to q, and the apex between them on the other two
            int p;
            int q;
            int apex;
            if (r0 >= r1 && r0 >= r2) {
                p = v0;
                q = v1;
                apex = v2;
            } else if (r1 >= r2) {
                p = v1;
                q = v2;
                apex = v0;
            } else {
                p = v2;
                q = v0;
                apex = v1;
            }
            int[] a = edge(p, q);
            int[] first = edge(p, apex);
            int[] second = edge(apex, q);
            int nb = first.length + second.length - 1;
            if (nb > chain.length) {
                chain = new int[2 * nb];
                progressB = new double[2 * nb];
            }
            if (a.length > progressA.length) {
                progressA = new double[2 * a.length];
            }
            System.arraycopy(first, 0, chain, 0, first.length);
            System.arraycopy(second, 1, chain, first.length, second.length - 1);
            // how far up from p to q each point is, by the ends' heights (the cuts are even)
            double hp = bed(p);
            double hq = bed(q);
            double ha = bed(apex);
            double span = hq - hp;
            double apexAt = Math.abs(span) > 1e-9 ? Math.clamp((ha - hp) / span, 0, 1) : 0.5;
            // by the heights the cuts were made at (the edges are cut at the same altitudes, so the rows zip level)
            double run = 0;
            for (int i = 0; i < a.length; i++) {
                run = Math.max(run, progress(a[i], hp, span, (double) i / (a.length - 1)));
                progressA[i] = run;
            }
            run = 0;
            for (int j = 0; j < nb; j++) {
                double even = j < first.length ? apexAt * j / (first.length - 1)
                    : apexAt + (1 - apexAt) * (j - first.length + 1) / (second.length - 1);
                run = Math.max(run, progress(chain[j], hp, span, even));
                progressB[j] = run;
            }
            progressA[a.length - 1] = 1;
            progressB[nb - 1] = 1;
            double orientation = cross(v0, v1, v2);
            int i = 0;
            int j = 0;
            if (t + 3 * (a.length + nb) > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, t + 3 * (a.length + nb)));
            }
            while (i < a.length - 1 || j < nb - 1) {
                boolean alongA = j == nb - 1 || (i < a.length - 1 && progressA[i + 1] <= progressB[j + 1]);
                int x;
                int y;
                int z;
                if (alongA) {
                    x = a[i];
                    y = a[i + 1];
                    z = chain[j];
                    i++;
                } else {
                    x = a[i];
                    y = chain[j + 1];
                    z = chain[j];
                    j++;
                }
                if (x == y || y == z || x == z) {
                    continue; // at p and q, where the two sides meet
                }
                if (cross(x, y, z) * orientation < 0) {
                    t = put(out, t, x, z, y);
                } else {
                    t = put(out, t, x, y, z);
                }
            }
        }
        return Arrays.copyOf(out, t);
    }

    /**
     * Where along edge ({@code lo}, {@code hi}) (heights {@code h0}, {@code h1}) the surface crosses {@code level}:
     * the fraction from {@code lo}, at least {@code from}; by bisection between the linear guess's bracket, else the
     * linear guess.
     */
    private double crossing(int lo, int hi, double h0, double h1, double level, double from) {
        // regula falsi (Illinois) from the bracket [from, 1], two steps: the surface is near linear along an edge
        double a = from;
        double b = 1;
        double ga = (from > 0 ? heightAlong(lo, hi, a) : h0) - level;
        double gb = h1 - level;
        if (ga * gb > 0) {
            return Math.clamp((level - h0) / (h1 - h0), from, 1);
        }
        double f = a;
        int side = 0;
        for (int i = 0; i < 2; i++) {
            f = (a * gb - b * ga) / (gb - ga);
            double g = heightAlong(lo, hi, f) - level;
            if (g * gb > 0) {
                b = f;
                gb = g;
                if (side == -1) {
                    ga *= 0.5;
                }
                side = -1;
            } else {
                a = f;
                ga = g;
                if (side == 1) {
                    gb *= 0.5;
                }
                side = 1;
            }
        }
        return f;
    }

    /** The surface's bedding-plane height {@code f} of the way along edge ({@code lo}, {@code hi}). */
    private double heightAlong(int lo, int hi, double f) {
        if (f <= 0) {
            return bed(lo);
        }
        if (f >= 1) {
            return bed(hi);
        }
        double x = x0 + verts.px[lo] + (verts.px[hi] - verts.px[lo]) * f;
        double z = z0 + verts.pz[lo] + (verts.pz[hi] - verts.pz[lo]) * f;
        return surface.bedHeight(x, z, surface.altitude(x, z, spacing));
    }

    /** Vertex {@code v}'s bedding-plane height: the plane it was cut on, or its own (kept once worked out). */
    private double bed(int v) {
        if (Double.isNaN(verts.cut[v])) {
            double x = x0 + verts.px[v];
            double z = z0 + verts.pz[v];
            verts.bed[v] = Double.isNaN(verts.bed[v]) ? surface.bedHeight(x, z, height(v) + surface.fall(x, z))
                : verts.bed[v];
            return verts.bed[v];
        }
        return verts.cut[v];
    }

    /** How far up from {@code hp} over {@code span} vertex {@code v} was cut ({@code fallback} where unknown). */
    private double progress(int v, double hp, double span, double fallback) {
        return Math.abs(span) > 1e-9 ? Math.clamp((bed(v) - hp) / span, 0, 1) : fallback;
    }

    private double height(int v) {
        return verts.positions[3 * v + 1];
    }

    /** The winding of ({@code a}, {@code b}, {@code c}) on the tile's plane, as twice its signed area. */
    private double cross(int a, int b, int c) {
        double ux = verts.px[b] - verts.px[a];
        double uz = verts.pz[b] - verts.pz[a];
        double vx = verts.px[c] - verts.px[a];
        double vz = verts.pz[c] - verts.pz[a];
        return ux * vz - uz * vx;
    }

    private static int put(int[] to, int t, int a, int b, int c) {
        to[t] = a;
        to[t + 1] = b;
        to[t + 2] = c;
        return t + 3;
    }

    /**
     * The points of edge ({@code a}, {@code b}) from {@code a} to {@code b}, made the first time it is asked for: cut
     * into as many pieces as it rises steps where its middle is on a cliff (at most 2 to the rounds' power, none once
     * the tile is full), else just its ends.
     */
    private int[] edge(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        long key = (long) lo << 32 | hi;
        int[] points = edges.get(key);
        if (points == null) {
            double rise = Math.abs(height(lo) - height(hi));
            double x = 0.5 * (verts.px[lo] + verts.px[hi]);
            double z = 0.5 * (verts.pz[lo] + verts.pz[hi]);
            // cut where the edge crosses a level of a grid of bedding-plane heights `step` apart (the altitude with the
            // beds' dip and waviness taken out, TerrainSurface.bedHeight): every edge's cuts lie on the same planes,
            // so the rows up a wall run along the beds from column to column and sample every bed alike (cut evenly
            // along each edge, or at levels of the altitude the dipping beds cross, a ledge was sampled in dashes)
            double h0 = bed(lo);
            double h1 = bed(hi);
            int first = 0;
            int pieces = 1;
            if (rise > step && verts.count < settings.maxVertices()
                && surface.cliffWeight(x0 + x, z0 + z, spacing) > 0) {
                double low = Math.min(h0, h1) + 0.2 * step;
                double high = Math.max(h0, h1) - 0.2 * step;
                first = (int) Math.ceil(low / step);
                int last = (int) Math.floor(high / step);
                pieces = (int) Math.max(1, Math.min(last - first + 2, 1L << Math.min(settings.rounds(), 20)));
            }
            points = new int[pieces + 1];
            points[0] = lo;
            points[pieces] = hi;
            double from = 0;
            for (int k = 1; k < pieces; k++) {
                double level = (h1 > h0 ? first + k - 1 : first + pieces - 1 - k) * step;
                // where the surface itself crosses the plane (bisection from the last cut on): a row's vertices all
                // on it, whatever the data does between the edge's ends
                double f = crossing(lo, hi, h0, h1, level, from);
                from = f;
                points[k] = add(lo, hi, f, verts.px[lo] + (verts.px[hi] - verts.px[lo]) * f,
                    verts.pz[lo] + (verts.pz[hi] - verts.pz[lo]) * f);
                verts.cut[points[k]] = level;
            }
            edges.put(key, points);
        }
        if (a == lo) {
            return points;
        }
        int[] reversed = new int[points.length];
        for (int k = 0; k < points.length; k++) {
            reversed[k] = points[points.length - 1 - k];
        }
        return reversed;
    }

    /** A vertex at ({@code x}, {@code z}) of the tile, {@code f} of the way from {@code a} to {@code b}: sampled, the rest between. */
    private int add(int a, int b, double f, double x, double z) {
        verts.ensure(verts.count + 1);
        int m = verts.count++;
        double wx = x0 + x;
        double wz = z0 + z;
        double altitude = surface.altitude(wx, wz, spacing);
        verts.px[m] = x;
        verts.pz[m] = z;
        float[] p = verts.positions;
        p[3 * m] = (float) x;
        p[3 * m + 1] = (float) (altitude - surface.fall(wx, wz));
        p[3 * m + 2] = (float) z;
        TileMeshes.split(px0 + x, verts.uv0, verts.uv1, 2 * m);
        TileMeshes.split(pz0 + z, verts.uv0, verts.uv1, 2 * m + 1);
        TileMeshes.encodeAltitude(altitude, verts.colors, 4 * m);
        float g = (float) f;
        // the curvature, the cover and its mix between the ends
        verts.colors[4 * m + 2] = verts.colors[4 * a + 2] + (verts.colors[4 * b + 2] - verts.colors[4 * a + 2]) * g;
        verts.colors[4 * m + 3] = verts.colors[4 * a + 3] + (verts.colors[4 * b + 3] - verts.colors[4 * a + 3]) * g;
        float tx = verts.tangents[4 * a] * (1 - g) + verts.tangents[4 * b] * g;
        float ty = verts.tangents[4 * a + 1] * (1 - g) + verts.tangents[4 * b + 1] * g;
        float tz = verts.tangents[4 * a + 2] * (1 - g) + verts.tangents[4 * b + 2] * g;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl > 1e-6f) {
            verts.tangents[4 * m] = tx / tl;
            verts.tangents[4 * m + 1] = ty / tl;
            verts.tangents[4 * m + 2] = tz / tl;
        } else {
            verts.tangents[4 * m + 2] = 1;
        }
        verts.tangents[4 * m + 3] = 1;
        // a normal for now: the ends' (every vertex on a cliff gets the moved wall's in displace)
        for (int k = 0; k < 3; k++) {
            verts.normals[3 * m + k] = verts.normals[3 * a + k] * (1 - g) + verts.normals[3 * b + k] * g;
        }
        return m;
    }

    /** A border's vertices {@code chain} with the points refining cut its edges into, in order. */
    int[] chain(int[] chain) {
        int count = 1;
        for (int k = 0; k + 1 < chain.length; k++) {
            count += edge(chain[k], chain[k + 1]).length - 1;
        }
        int[] out = new int[count];
        int at = 0;
        for (int k = 0; k + 1 < chain.length; k++) {
            int[] points = edge(chain[k], chain[k + 1]);
            System.arraycopy(points, 0, out, at, points.length - 1);
            at += points.length - 1;
        }
        out[at] = chain[chain.length - 1];
        return out;
    }

    /**
     * Moves every vertex on a cliff by the relief and gives it the moved wall's normal ({@link TerrainSurface#cliffPoint});
     * {@code refined}: the tile's walls hold vertices every step up them (else the grid's rise apart).
     */
    void displace(boolean refined) {
        double vertical = refined ? step : -1;
        double[] p = point;
        for (int v = 0; v < verts.count; v++) {
            double x = x0 + verts.px[v];
            double z = z0 + verts.pz[v];
            double w = surface.cliffPoint(x, z, spacing, vertical, p);
            verts.weight[v] = w;
            if (w <= 0) {
                continue;
            }
            // the wall flagged for the material by its tangent (which elsewhere carries the cover's mix, always with
            // y + z >= 0; a wall's (0, -0.6, -0.8) keeps y + z below 0 through the deformation pass that morphs tiles,
            // which turns the tangent square to the normal). Not by the tangent's sign: tiles with negative signs
            // crashed lavapipe on CI's EPYC 7763 (SIGSEGV in its shader code, three runs; docs/pitfalls.md)
            if (w >= 0.5) {
                verts.tangents[4 * v] = 0;
                verts.tangents[4 * v + 1] = -0.6f;
                verts.tangents[4 * v + 2] = -0.8f;
            }
            double ox = p[0];
            double oy = p[1];
            double oz = p[2];
            verts.positions[3 * v] = (float) (verts.px[v] + ox);
            verts.positions[3 * v + 1] = (float) oy;
            verts.positions[3 * v + 2] = (float) (verts.pz[v] + oz);
            TileMeshes.split(px0 + verts.px[v] + ox, verts.uv0, verts.uv1, 2 * v);
            TileMeshes.split(pz0 + verts.pz[v] + oz, verts.uv0, verts.uv1, 2 * v + 1);
            // the moved wall's normal, from the grid's where the cliff fades out
            double nx = w * p[4] + (1 - w) * verts.normals[3 * v];
            double ny = w * p[5] + (1 - w) * verts.normals[3 * v + 1];
            double nz = w * p[6] + (1 - w) * verts.normals[3 * v + 2];
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 1e-9) {
                verts.normals[3 * v] = (float) (nx / l);
                verts.normals[3 * v + 1] = (float) (ny / l);
                verts.normals[3 * v + 2] = (float) (nz / l);
            }
        }
    }
}
