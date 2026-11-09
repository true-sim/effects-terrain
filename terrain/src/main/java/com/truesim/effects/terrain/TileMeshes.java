package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.terrain.data.CliffParameters;
import com.truesim.effects.terrain.data.LocalFrame;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshPool;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;

/**
 * Builds a terrain tile's mesh from the surface, as a generated asset ({@link Terrain#mesh}): a grid of the tile's
 * quads at its level's spacing (two triangles a quad, split from the (+x, -z) corner to the (-x, +z) one, as the CPU queries
 * interpolate), its vertices sampled at that footprint, normals and curvature from the neighbouring samples (an apron
 * one sample wide, so a tile needs nothing of its neighbours), and skirts hanging from its four edges, double-sided,
 * as deep as the widest gap the edge can have against a tile up to two levels finer or coarser plus a margin: the
 * gaps between levels close, wherever the camera is. Flat meshlets (no cluster hierarchy): the tile pyramid is the
 * level of detail, and an exact surface keeps the skirts' depth exact.
 *
 * <p>Vertices carry what the terrain material reads (terrain.slang): the planar position in UV0 + UV1, the altitude in
 * the color's red and green, the curvature in its blue; the modifiers' surface materials ({@link
 * com.truesim.effects.terrain.data.TerrainModifier}) as their total cover in one less the color's alpha and their mix
 * in the tangent ({@link #mix}).
 */
@AllowAllocation("builds tiles in the background")
public final class TileMeshes implements Generator<TileMeshes.TileMesh, MeshData> {

    /**
     * A tile's mesh: tile {@code key}'s own, or ({@code morphTo} above its level) with a morph target onto that coarser
     * level's surface (weight 1: exactly the coarser tile's triangles and normals over its area).
     */
    public record TileMesh(long key, int morphTo) {
    }

    private static final VertexFormat FORMAT = VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT,
        VertexAttribute.UV0, VertexAttribute.UV1, VertexAttribute.COLOR);

    private final TerrainSurface surface;
    private final TerrainGrid grid;
    private final double skirtMargin;
    private final double curvatureScale;
    private volatile MeshPool pool;
    /** What each tile built so far is: its error against the finer level, and its height range. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Info> infos = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean skirts = true;
    private volatile double layerScale = 16;
    private volatile CliffMesh.Settings cliff = CliffMesh.Settings.DEFAULTS;

    /**
     * Tiles of {@code grid} on {@code surface}, skirts {@code skirtMargin} metres past the gaps they close, curvature
     * {@code curvatureScale} (1/m) counting as fully concave or convex.
     */
    public TileMeshes(TerrainSurface surface, TerrainGrid grid, double skirtMargin, double curvatureScale) {
        this.surface = surface;
        this.grid = grid;
        this.skirtMargin = skirtMargin;
        this.curvatureScale = curvatureScale;
    }

    /** Builds into the memory of meshes of the same sizes {@code pool} keeps (null: allocates). */
    public TileMeshes pool(MeshPool meshes) {
        this.pool = meshes;
        return this;
    }

    /** Whether tiles have skirts (true unless set off, to see the gaps they close). */
    public TileMeshes skirts(boolean on) {
        this.skirts = on;
        return this;
    }

    /**
     * Measures the curvature the material's layers follow over {@code metres} at every level finer than that
     * (carried in the colour's blue), so the layers do not change with the level.
     */
    public TileMeshes layerScale(double metres) {
        this.layerScale = metres;
        return this;
    }

    /** How the cliffs' meshes refine where their relief is in the mesh ({@code CliffParameters.Relief.MESH}). */
    public TileMeshes cliffs(CliffMesh.Settings settings) {
        this.cliff = settings;
        return this;
    }

    public TerrainSurface surface() {
        return surface;
    }

    public TerrainGrid grid() {
        return grid;
    }

    @Override
    public MeshData generate(TileMesh mesh, long seed) {
        return build(mesh.key(), mesh.morphTo());
    }

    /**
     * What a tile is, once built: {@code error}, the largest distance along the normal between its triangles and the
     * finer level's surface ({@link TerrainError#tile}); its heights from {@code low} to {@code high}; its
     * {@code vertices} on top (without skirts: more than the grid's where its cliffs were refined).
     */
    public record Info(double error, double low, double high, int vertices) {
    }

    /** What tile {@code key} is, once its mesh was built (null before). */
    public Info info(long key) {
        return infos.get(key);
    }

    /** The mesh of tile {@code key}. */
    public MeshData build(long key) {
        return build(key, -1);
    }

    /** The mesh of tile {@code key}, with a morph target onto level {@code morphTo}'s surface if it is coarser. */
    public MeshData build(long key, int morphTo) {
        Geometry g = geometry(key, morphTo);
        CliffMesh.Vertices verts = g.vertices();
        int vertices = g.count();
        MeshBuilder builder = new MeshBuilder(FORMAT, vertices).pool(pool)
            .positions(java.util.Arrays.copyOf(verts.positions, 3 * vertices))
            .attribute(VertexAttribute.NORMAL, java.util.Arrays.copyOf(verts.normals, 3 * vertices))
            .attribute(VertexAttribute.TANGENT, java.util.Arrays.copyOf(verts.tangents, 4 * vertices))
            .attribute(VertexAttribute.UV0, java.util.Arrays.copyOf(verts.uv0, 2 * vertices))
            .attribute(VertexAttribute.UV1, java.util.Arrays.copyOf(verts.uv1, 2 * vertices))
            .attribute(VertexAttribute.COLOR, java.util.Arrays.copyOf(verts.colors, 4 * vertices))
            .submesh(g.indices());
        if (g.morph() != null) {
            builder.morphTarget("coarser", g.morph()[0], g.morph()[1], null);
        }
        return builder.buildFlat();
    }

    /**
     * A tile's geometry before it is a mesh: its {@code vertices} ({@code count} of them, the first {@code top} the
     * surface's, the rest its skirts'), its triangles' {@code indices} (the surface's first, {@code surface} of them),
     * its borders' vertices in order ({@code sides}: z = 0, z = size, x = 0, x = size), how deep its skirts hang, and
     * its morph target's position and normal deltas (null without).
     */
    record Geometry(CliffMesh.Vertices vertices, int count, int top, int[] indices, int surface, int[][] sides,
        double skirtDepth, float[][] morph) {
    }

    /** {@link #build(long, int)}'s geometry. */
    Geometry geometry(long key, int morphTo) {
        int level = TerrainGrid.level(key);
        int n = grid.quads();
        double size = grid.size(level);
        double s = grid.spacing(level);
        double x0 = TerrainGrid.x(key) * size;
        double z0 = TerrainGrid.z(key) * size;
        int a = n + 3; // the apron grid: indices -1 to n + 1
        double[] altitude = new double[a * a];
        double[] height = new double[a * a];
        LocalFrame frame = surface.site().frame();
        double step = TerrainSurface.slopeStep(s);
        for (int j = 0; j < a; j++) {
            double z = z0 + (j - 1) * s;
            double lat = frame.latitude(z);
            double latNext = frame.latitude(z + step);
            for (int i = 0; i < a; i++) {
                double x = x0 + (i - 1) * s;
                double alt = surface.altitude(x, z, s, lat, latNext);
                altitude[j * a + i] = alt;
                height[j * a + i] = alt - surface.fall(x, z);
            }
        }
        double depth = skirts ? skirtDepth(level, x0, z0, height, a) : 0;
        int top = (n + 1) * (n + 1);
        CliffMesh.Vertices verts = new CliffMesh.Vertices(top + (skirts ? 4 * (n + 1) : 0));
        verts.count = top;
        float[] positions = verts.positions;
        float[] normals = verts.normals;
        float[] colors = verts.colors;
        double[] layer = layerField(level, x0, z0, height, a);
        double px0 = Math.floorMod((long) x0, (long) TerrainGrid.PLANAR_PERIOD) + (x0 - Math.floor(x0));
        double pz0 = Math.floorMod((long) z0, (long) TerrainGrid.PLANAR_PERIOD) + (z0 - Math.floor(z0));
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int v = j * (n + 1) + i;
                int c = (j + 1) * a + i + 1;
                double h = height[c];
                verts.px[v] = i * s;
                verts.pz[v] = j * s;
                positions[3 * v] = (float) (i * s);
                positions[3 * v + 1] = (float) h;
                positions[3 * v + 2] = (float) (j * s);
                double dx = (height[c + 1] - height[c - 1]) / (2 * s);
                double dz = (height[c + a] - height[c - a]) / (2 * s);
                double length = Math.sqrt(dx * dx + 1 + dz * dz);
                normals[3 * v] = (float) (-dx / length);
                normals[3 * v + 1] = (float) (1 / length);
                normals[3 * v + 2] = (float) (-dz / length);
                split(px0 + i * s, verts.uv0, verts.uv1, 2 * v);
                split(pz0 + j * s, verts.uv0, verts.uv1, 2 * v + 1);
                double laplacian = layer[4 * v + 3];
                encodeAltitude(altitude[c], colors, 4 * v);
                colors[4 * v + 2] = Math.round(255 * (0.5 + 0.5 * Math.clamp(laplacian / curvatureScale, -1, 1)))
                    / 255f;
                colors[4 * v + 3] = 1;
            }
        }
        cover(x0, z0, s, colors, verts.tangents);
        int[] triangles = new int[6 * n * n];
        int t = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int v00 = j * (n + 1) + i;
                int v10 = v00 + 1;
                int v01 = v00 + n + 1;
                int v11 = v01 + 1;
                triangles[t++] = v00;
                triangles[t++] = v01;
                triangles[t++] = v10;
                triangles[t++] = v10;
                triangles[t++] = v01;
                triangles[t++] = v11;
            }
        }
        int[][] sides = new int[4][n + 1];
        for (int side = 0; side < 4; side++) {
            for (int k = 0; k <= n; k++) {
                sides[side][k] = switch (side) {
                    case 0 -> k;                       // z = 0
                    case 1 -> n * (n + 1) + k;         // z = size
                    case 2 -> k * (n + 1);             // x = 0
                    default -> k * (n + 1) + n;        // x = size
                };
            }
        }
        // Cliffs with their relief in the mesh: steep triangles refined, wall vertices moved out and in
        CliffParameters cliffs = surface.parameters().cliffs();
        boolean meshRelief = cliffs.relief() == CliffParameters.Relief.MESH && cliffs.any()
            && CliffMesh.steep(height, a, s, cliffs.slopeStart() - 10);
        boolean refined = meshRelief && level < cliff.levels() && cliff.rounds() > 0;
        if (meshRelief) {
            CliffMesh mesh = new CliffMesh(surface, cliff, level, x0, z0, s, px0, pz0, verts);
            if (refined) {
                triangles = mesh.refine(triangles);
                for (int side = 0; side < 4; side++) {
                    sides[side] = mesh.chain(sides[side]);
                }
            }
            mesh.displace(refined);
            positions = verts.positions;
            normals = verts.normals;
            colors = verts.colors;
        }
        top = verts.count;
        // Skirts: a copy of each edge's vertices, `depth` below
        int skirtCount = 0;
        for (int side = 0; skirts && side < 4; side++) {
            skirtCount += sides[side].length;
        }
        verts.ensure(top + skirtCount);
        positions = verts.positions;
        normals = verts.normals;
        colors = verts.colors;
        int vertices = top + skirtCount;
        int skirtEdges = skirtCount - (skirts ? 4 : 0);
        int[] indices = new int[triangles.length + 12 * skirtEdges];
        System.arraycopy(triangles, 0, indices, 0, triangles.length);
        t = triangles.length;
        int[] skirtSource = new int[skirtCount];
        double reach = cliffs.ledge() + cliffs.jointDepth() + 1.5 * cliffs.buttressDepth();
        int skirt = top;
        for (int side = 0; skirts && side < 4; side++) {
            int[] edge = sides[side];
            for (int k = 0; k < edge.length; k++) {
                int v = skirt + k;
                int from = edge[k];
                skirtSource[v - top] = from;
                positions[3 * v] = positions[3 * from];
                positions[3 * v + 1] = (float) (positions[3 * from + 1] - depth);
                positions[3 * v + 2] = positions[3 * from + 2];
                if (meshRelief && verts.weight[from] > 0) {
                    // on a wall the relief moves the borders of neighbouring levels apart sideways (each level holds
                    // the beds its rows can): the skirt reaches into the rock as far as the relief does, so it closes
                    // that slit too, seen from outside the wall
                    double nx = normals[3 * from];
                    double nz = normals[3 * from + 2];
                    double l = Math.sqrt(nx * nx + nz * nz);
                    if (l > 0.2) {
                        double in = reach * verts.weight[from] / l;
                        positions[3 * v] -= (float) (nx * in);
                        positions[3 * v + 2] -= (float) (nz * in);
                    }
                }
                verts.px[v] = verts.px[from];
                verts.pz[v] = verts.pz[from];
                System.arraycopy(normals, 3 * from, normals, 3 * v, 3);
                System.arraycopy(verts.uv0, 2 * from, verts.uv0, 2 * v, 2);
                System.arraycopy(verts.uv1, 2 * from, verts.uv1, 2 * v, 2);
                System.arraycopy(colors, 4 * from, colors, 4 * v, 4);
                System.arraycopy(verts.tangents, 4 * from, verts.tangents, 4 * v, 4);
            }
            for (int k = 0; k + 1 < edge.length; k++) {
                int a0 = edge[k];
                int a1 = edge[k + 1];
                int b0 = skirt + k;
                int b1 = skirt + k + 1;
                // both sides: a skirt closes the gap seen from either tile
                indices[t++] = a0;
                indices[t++] = b0;
                indices[t++] = a1;
                indices[t++] = a1;
                indices[t++] = b0;
                indices[t++] = b1;
                indices[t++] = a0;
                indices[t++] = a1;
                indices[t++] = b0;
                indices[t++] = a1;
                indices[t++] = b1;
                indices[t++] = b0;
            }
            skirt += edge.length;
        }
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int v = 0; v < top; v++) {
            low = Math.min(low, positions[3 * v + 1]);
            high = Math.max(high, positions[3 * v + 1]);
        }
        infos.put(key, new Info(TerrainError.tile(surface, grid, key, cliff), low, high, top));
        float[][] deltas = morphTo > level
            ? morphDeltas(level, morphTo, x0, z0, verts, top, skirtSource, meshRelief) : null;
        return new Geometry(verts, vertices, top, indices, triangles.length, sides, depth, deltas);
    }

    /** {@code altitude} (metres above sea level) into the color's red and green at {@code at}, eighths of a metre. */
    static void encodeAltitude(double altitude, float[] colors, int at) {
        int alt = Math.clamp(Math.round((altitude + 1024) * 8), 0, 65535);
        colors[at] = (alt >>> 8) / 255f;
        colors[at + 1] = (alt & 0xff) / 255f;
    }

    /**
     * The modifiers' surface materials at the top vertices: one less their total cover into the colors' alpha, their
     * mix into the tangents ({@link #mix}); a vertex without cover takes the mix of its covered neighbours, so a
     * material fades out at its shoulder as itself.
     */
    void cover(double x0, double z0, double s, float[] colors, float[] tangents) {
        int n = grid.quads();
        int count = (n + 1) * (n + 1);
        double[] mixes = new double[2 * count];
        boolean[] covered = new boolean[count];
        double[] c = new double[com.truesim.effects.terrain.data.TerrainModifier.MATERIALS];
        boolean any = false;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int v = j * (n + 1) + i;
                double total = surface.modifiers().isEmpty() ? 0 : surface.materials(x0 + i * s, z0 + j * s, s, c);
                colors[4 * v + 3] = Math.round(255 * (1 - Math.clamp(total, 0, 1))) / 255f;
                if (total > 0) {
                    mix(c, total, mixes, 2 * v);
                    covered[v] = true;
                    any = true;
                }
            }
        }
        for (int j = 0; any && j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int v = j * (n + 1) + i;
                if (covered[v]) {
                    continue;
                }
                double a = 0;
                double b = 0;
                int found = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = i + di;
                        int nj = j + dj;
                        int w = nj * (n + 1) + ni;
                        if (ni >= 0 && nj >= 0 && ni <= n && nj <= n && covered[w]) {
                            a += mixes[2 * w];
                            b += mixes[2 * w + 1];
                            found++;
                        }
                    }
                }
                if (found > 0) {
                    mixes[2 * v] = a / found;
                    mixes[2 * v + 1] = b / found;
                }
            }
        }
        for (int v = 0; v < count; v++) {
            encodeMix(mixes[2 * v], mixes[2 * v + 1], tangents, 4 * v);
        }
    }

    /**
     * The mix of the surface materials {@code cover} (total {@code total}) as two coordinates into {@code out} at
     * {@code at}: corners gravel (0, 0), sand (1, 0), cobbles (0, 1) and dirt (1, 1), bilinear between (the pairs met
     * along rivers and roads, gravel and sand, gravel and cobbles, sand and dirt, cobbles and dirt, blend exactly).
     */
    static void mix(double[] cover, double total, double[] out, int at) {
        double sand = cover[com.truesim.effects.terrain.data.TerrainModifier.SAND] / total;
        double cobbles = cover[com.truesim.effects.terrain.data.TerrainModifier.COBBLES] / total;
        double dirt = cover[com.truesim.effects.terrain.data.TerrainModifier.DIRT] / total;
        out[at] = Math.clamp(sand + dirt, 0, 1);
        out[at + 1] = Math.clamp(cobbles + dirt, 0, 1);
    }

    /**
     * Mix coordinates ({@code a}, {@code b}) as a unit tangent (the vertex format's octahedral direction, which the
     * terrain material reads back, terrain.slang's {@code coverMix}): the point ((a - b) / 2, (a + b) / 2 - 1/2) of the
     * octahedron's upper half, normalized; w 1.
     */
    static void encodeMix(double a, double b, float[] out, int at) {
        double u = (a - b) / 2;
        double v = (a + b) / 2 - 0.5;
        double w = 1 - Math.abs(u) - Math.abs(v);
        double length = Math.sqrt(u * u + v * v + w * w);
        out[at] = (float) (u / length);
        out[at + 1] = (float) (v / length);
        out[at + 2] = (float) (w / length);
        out[at + 3] = 1;
    }

    /** The mix coordinates a tangent written by {@link #encodeMix} carries: a and b into {@code out}. */
    static void decodeMix(double x, double y, double z, double[] out) {
        double l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
        double u = x / l1;
        double v = y / l1;
        out[0] = v + 0.5 + u;
        out[1] = v + 0.5 - u;
    }

    /** {@code value} as a high and a low float16 summing to it within a millimetre, at {@code at} of uv0 and uv1. */
    static void split(double value, float[] uv0, float[] uv1, int at) {
        float high = Float.float16ToFloat(Float.floatToFloat16((float) value));
        uv0[at] = high;
        uv1[at] = Float.float16ToFloat(Float.floatToFloat16((float) (value - high)));
    }

    /**
     * How deep the tile's skirts hang: the widest gap between its edges and those of a tile up to two levels finer or
     * coarser beside it (each edge a polyline between its own vertices, sampled at the finer spacing), plus the
     * margin and the height's quantization in the mesh (16 bits over its range, in both meshes).
     */
    double skirtDepth(int level, double x0, double z0, double[] height, int a) {
        int n = grid.quads();
        double size = grid.size(level);
        double s = grid.spacing(level);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double h : height) {
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        double gap = 0;
        for (int other = Math.max(0, level - 2); other <= Math.min(grid.levels() - 1, level + 2); other++) {
            if (other == level) {
                continue;
            }
            double so = grid.spacing(other);
            double fine = Math.min(s, so);
            int steps = (int) Math.round(size / fine);
            for (int side = 0; side < 4; side++) {
                for (int k = 0; k <= steps; k++) {
                    double along = k * fine;
                    double x = side < 2 ? x0 + along : x0 + (side == 2 ? 0 : size);
                    double z = side < 2 ? z0 + (side == 0 ? 0 : size) : z0 + along;
                    // ours: between our vertices on the edge
                    double f = along / s;
                    int i = Math.min((int) f, n - 1);
                    double u = f - i;
                    int c0;
                    int step;
                    if (side < 2) {
                        c0 = (side == 0 ? 1 : n + 1) * a + i + 1;
                        step = 1;
                    } else {
                        c0 = (i + 1) * a + (side == 2 ? 1 : n + 1);
                        step = a;
                    }
                    double ours = height[c0] * (1 - u) + height[c0 + step] * u;
                    // theirs: between their vertices, at their spacing
                    double t = (side < 2 ? x : z) / so;
                    double t0 = Math.floor(t);
                    double w = t - t0;
                    double theirs;
                    if (side < 2) {
                        theirs = surface.height(t0 * so, z, so) * (1 - w)
                            + (w > 0 ? surface.height((t0 + 1) * so, z, so) * w : 0);
                    } else {
                        theirs = surface.height(x, t0 * so, so) * (1 - w)
                            + (w > 0 ? surface.height(x, (t0 + 1) * so, so) * w : 0);
                    }
                    gap = Math.max(gap, Math.abs(ours - theirs));
                }
            }
        }
        double range = max - min + gap;
        return gap + skirtMargin + 2 * range / 65535;
    }

    /**
     * The slope's normal (x, y, z) and the Laplacian (w) the material's layers follow at each top vertex: measured on
     * a grid {@code layerScale} metres apart fixed in the world (the same samples for every level finer than that,
     * at that footprint), bilinear between its points; at coarser levels, the tile's own samples.
     */
    double[] layerField(int level, double x0, double z0, double[] height, int a) {
        int n = grid.quads();
        double s = grid.spacing(level);
        double scale = layerScale;
        double[] out = new double[4 * (n + 1) * (n + 1)];
        if (scale <= s) {
            for (int j = 0; j <= n; j++) {
                for (int i = 0; i <= n; i++) {
                    int c = (j + 1) * a + i + 1;
                    double dx = (height[c + 1] - height[c - 1]) / (2 * s);
                    double dz = (height[c + a] - height[c - a]) / (2 * s);
                    double length = Math.sqrt(dx * dx + 1 + dz * dz);
                    int v = 4 * (j * (n + 1) + i);
                    out[v] = -dx / length;
                    out[v + 1] = 1 / length;
                    out[v + 2] = -dz / length;
                    out[v + 3] = (height[c + 1] + height[c - 1] + height[c + a] + height[c - a] - 4 * height[c])
                        / (s * s);
                }
            }
            return out;
        }
        double size = grid.size(level);
        int gx0 = (int) Math.floor(x0 / scale) - 1;
        int gz0 = (int) Math.floor(z0 / scale) - 1;
        int count = (int) Math.ceil(size / scale) + 3;
        double[] g = new double[count * count];
        for (int j = 0; j < count; j++) {
            for (int i = 0; i < count; i++) {
                g[j * count + i] = surface.height((gx0 + i) * scale, (gz0 + j) * scale, scale);
            }
        }
        double[] field = new double[4 * count * count];
        for (int j = 1; j < count - 1; j++) {
            for (int i = 1; i < count - 1; i++) {
                int c = j * count + i;
                double dx = (g[c + 1] - g[c - 1]) / (2 * scale);
                double dz = (g[c + count] - g[c - count]) / (2 * scale);
                double length = Math.sqrt(dx * dx + 1 + dz * dz);
                field[4 * c] = -dx / length;
                field[4 * c + 1] = 1 / length;
                field[4 * c + 2] = -dz / length;
                field[4 * c + 3] = (g[c + 1] + g[c - 1] + g[c + count] + g[c - count] - 4 * g[c]) / (scale * scale);
            }
        }
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                double fx = (x0 + i * s) / scale - gx0;
                double fz = (z0 + j * s) / scale - gz0;
                int ix = Math.clamp((int) Math.floor(fx), 1, count - 3);
                int iz = Math.clamp((int) Math.floor(fz), 1, count - 3);
                double u = fx - ix;
                double w = fz - iz;
                int v = 4 * (j * (n + 1) + i);
                for (int k = 0; k < 4; k++) {
                    double f00 = field[4 * (iz * count + ix) + k];
                    double f10 = field[4 * (iz * count + ix + 1) + k];
                    double f01 = field[4 * ((iz + 1) * count + ix) + k];
                    double f11 = field[4 * ((iz + 1) * count + ix + 1) + k];
                    out[v + k] = (f00 * (1 - u) + f10 * u) * (1 - w) + (f01 * (1 - u) + f11 * u) * w;
                }
            }
        }
        return out;
    }

    /**
     * The morph target onto level {@code coarse}'s surface: for each vertex, how far it moves and how its normal turns
     * to lie on the coarser tile's triangles with their normals (heights and normals of the coarser grid, from its own
     * apron, as that tile has them; between its vertices on its two triangles a quad). Vertices on walls with their
     * relief in the mesh ({@code meshRelief}) move onto the coarser level's wall instead (its heights and relief at
     * that footprint), keeping their normals. Skirt vertices move with the edge vertex they hang from.
     */
    float[][] morphDeltas(int level, int coarse, double x0, double z0, CliffMesh.Vertices verts, int top,
        int[] skirtSource, boolean meshRelief) {
        int n = grid.quads();
        double sc = grid.spacing(coarse);
        int r = 1 << (coarse - level);
        int m = Math.max(1, n / r); // coarse quads across this tile
        int a = m + 3;
        float[] positions = verts.positions;
        float[] normals = verts.normals;
        double[] h = new double[a * a];
        for (int j = 0; j < a; j++) {
            for (int i = 0; i < a; i++) {
                h[j * a + i] = surface.height(x0 + (i - 1) * sc, z0 + (j - 1) * sc, sc);
            }
        }
        double[] cn = new double[3 * a * a];
        for (int j = 1; j < a - 1; j++) {
            for (int i = 1; i < a - 1; i++) {
                int c = j * a + i;
                double dx = (h[c + 1] - h[c - 1]) / (2 * sc);
                double dz = (h[c + a] - h[c - a]) / (2 * sc);
                double length = Math.sqrt(dx * dx + 1 + dz * dz);
                cn[3 * c] = -dx / length;
                cn[3 * c + 1] = 1 / length;
                cn[3 * c + 2] = -dz / length;
            }
        }
        int vertices = top + skirtSource.length;
        float[] dp = new float[3 * vertices];
        float[] dn = new float[3 * vertices];
        double[] point = new double[7];
        boolean coarseRefined = coarse < cliff.levels() && cliff.rounds() > 0;
        for (int v = 0; v < top; v++) {
            double px = verts.px[v];
            double pz = verts.pz[v];
            if (meshRelief && verts.weight[v] > 0) {
                surface.cliffPoint(x0 + px, z0 + pz, sc, coarseRefined ? cliff.step(coarse) : -1, point);
                dp[3 * v] = (float) (px + point[0] - positions[3 * v]);
                dp[3 * v + 1] = (float) (point[1] - positions[3 * v + 1]);
                dp[3 * v + 2] = (float) (pz + point[2] - positions[3 * v + 2]);
                continue;
            }
            double fu = px / sc;
            double fv = pz / sc;
            int ci = Math.clamp((int) Math.floor(fu), 0, m - 1);
            int cj = Math.clamp((int) Math.floor(fv), 0, m - 1);
            double u = fu - ci;
            double w = fv - cj;
            int c00 = (cj + 1) * a + ci + 1;
            int c10 = c00 + 1;
            int c01 = c00 + a;
            int c11 = c01 + 1;
            double b00;
            double b10;
            double b01;
            double b11;
            if (u + w <= 1) {
                b00 = 1 - u - w;
                b10 = u;
                b01 = w;
                b11 = 0;
            } else {
                b00 = 0;
                b10 = 1 - w;
                b01 = 1 - u;
                b11 = u + w - 1;
            }
            double hc = b00 * h[c00] + b10 * h[c10] + b01 * h[c01] + b11 * h[c11];
            double nx = b00 * cn[3 * c00] + b10 * cn[3 * c10] + b01 * cn[3 * c01] + b11 * cn[3 * c11];
            double ny = b00 * cn[3 * c00 + 1] + b10 * cn[3 * c10 + 1] + b01 * cn[3 * c01 + 1]
                + b11 * cn[3 * c11 + 1];
            double nz = b00 * cn[3 * c00 + 2] + b10 * cn[3 * c10 + 2] + b01 * cn[3 * c01 + 2]
                + b11 * cn[3 * c11 + 2];
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            dp[3 * v] = (float) (px - positions[3 * v]);
            dp[3 * v + 1] = (float) (hc - positions[3 * v + 1]);
            dp[3 * v + 2] = (float) (pz - positions[3 * v + 2]);
            dn[3 * v] = (float) (nx / nl - normals[3 * v]);
            dn[3 * v + 1] = (float) (ny / nl - normals[3 * v + 1]);
            dn[3 * v + 2] = (float) (nz / nl - normals[3 * v + 2]);
        }
        // the skirts' vertices move with the vertex each hangs from
        for (int k = 0; k < skirtSource.length; k++) {
            System.arraycopy(dp, 3 * skirtSource[k], dp, 3 * (top + k), 3);
            System.arraycopy(dn, 3 * skirtSource[k], dn, 3 * (top + k), 3);
        }
        return new float[][] {dp, dn};
    }
}
