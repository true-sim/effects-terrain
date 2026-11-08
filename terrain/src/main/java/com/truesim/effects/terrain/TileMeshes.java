package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
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
 * the color's red and green, the curvature in its blue.
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
        VertexAttribute.UV0,
        VertexAttribute.UV1, VertexAttribute.COLOR);

    private final TerrainSurface surface;
    private final TerrainGrid grid;
    private final double skirtMargin;
    private final double curvatureScale;
    private volatile MeshPool pool;
    /** What each tile built so far is: its error against the finer level, and its height range. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Info> infos = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean skirts = true;
    private volatile double layerScale = 16;

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
     * Measures the slope and curvature the material's layers follow over {@code metres} at every level finer than
     * that (carried in the tangent and the colour's blue), so the layers do not change with the level.
     */
    public TileMeshes layerScale(double metres) {
        this.layerScale = metres;
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
     * finer level's surface ({@link TerrainError#tile}); its heights from {@code low} to {@code high}.
     */
    public record Info(double error, double low, double high) {
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
        int vertices = top + (skirts ? 4 * (n + 1) : 0);
        float[] positions = new float[3 * vertices];
        float[] normals = new float[3 * vertices];
        float[] uv0 = new float[2 * vertices];
        float[] uv1 = new float[2 * vertices];
        float[] colors = new float[4 * vertices];
        float[] tangents = new float[4 * vertices];
        double[] layer = layerField(level, x0, z0, height, a);
        double px0 = Math.floorMod((long) x0, (long) TerrainGrid.PLANAR_PERIOD) + (x0 - Math.floor(x0));
        double pz0 = Math.floorMod((long) z0, (long) TerrainGrid.PLANAR_PERIOD) + (z0 - Math.floor(z0));
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int v = j * (n + 1) + i;
                int c = (j + 1) * a + i + 1;
                double h = height[c];
                positions[3 * v] = (float) (i * s);
                positions[3 * v + 1] = (float) h;
                positions[3 * v + 2] = (float) (j * s);
                double dx = (height[c + 1] - height[c - 1]) / (2 * s);
                double dz = (height[c + a] - height[c - a]) / (2 * s);
                double length = Math.sqrt(dx * dx + 1 + dz * dz);
                normals[3 * v] = (float) (-dx / length);
                normals[3 * v + 1] = (float) (1 / length);
                normals[3 * v + 2] = (float) (-dz / length);
                split(px0 + i * s, uv0, uv1, 2 * v);
                split(pz0 + j * s, uv0, uv1, 2 * v + 1);
                double laplacian = layer[4 * v + 3];
                tangents[4 * v] = (float) layer[4 * v];
                tangents[4 * v + 1] = (float) layer[4 * v + 1];
                tangents[4 * v + 2] = (float) layer[4 * v + 2];
                tangents[4 * v + 3] = 1;
                int alt = Math.clamp(Math.round((altitude[c] + 1024) * 8), 0, 65535);
                colors[4 * v] = (alt >>> 8) / 255f;
                colors[4 * v + 1] = (alt & 0xff) / 255f;
                colors[4 * v + 2] = Math.round(255 * (0.5 + 0.5 * Math.clamp(laplacian / curvatureScale, -1, 1)))
                    / 255f;
                colors[4 * v + 3] = 1;
            }
        }
        // Skirts: a copy of each edge's vertices, `depth` below
        int[] edge = new int[n + 1];
        int skirt = top;
        int[] indices = new int[6 * n * n + (skirts ? 4 * 12 * n : 0)];
        int t = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int v00 = j * (n + 1) + i;
                int v10 = v00 + 1;
                int v01 = v00 + n + 1;
                int v11 = v01 + 1;
                indices[t++] = v00;
                indices[t++] = v01;
                indices[t++] = v10;
                indices[t++] = v10;
                indices[t++] = v01;
                indices[t++] = v11;
            }
        }
        for (int side = 0; side < (skirts ? 4 : 0); side++) {
            for (int k = 0; k <= n; k++) {
                edge[k] = switch (side) {
                    case 0 -> k;                       // z = 0
                    case 1 -> n * (n + 1) + k;         // z = size
                    case 2 -> k * (n + 1);             // x = 0
                    default -> k * (n + 1) + n;        // x = size
                };
                int v = skirt + k;
                int from = edge[k];
                positions[3 * v] = positions[3 * from];
                positions[3 * v + 1] = (float) (positions[3 * from + 1] - depth);
                positions[3 * v + 2] = positions[3 * from + 2];
                System.arraycopy(normals, 3 * from, normals, 3 * v, 3);
                System.arraycopy(uv0, 2 * from, uv0, 2 * v, 2);
                System.arraycopy(uv1, 2 * from, uv1, 2 * v, 2);
                System.arraycopy(colors, 4 * from, colors, 4 * v, 4);
                System.arraycopy(tangents, 4 * from, tangents, 4 * v, 4);
            }
            for (int k = 0; k < n; k++) {
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
            skirt += n + 1;
        }
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int v = 0; v < top; v++) {
            low = Math.min(low, positions[3 * v + 1]);
            high = Math.max(high, positions[3 * v + 1]);
        }
        infos.put(key, new Info(TerrainError.tile(surface, grid, key), low, high));
        MeshBuilder builder = new MeshBuilder(FORMAT, vertices).pool(pool).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.TANGENT, tangents)
            .attribute(VertexAttribute.UV0, uv0).attribute(VertexAttribute.UV1, uv1)
            .attribute(VertexAttribute.COLOR, colors).submesh(indices);
        if (morphTo > level) {
            float[][] deltas = morphDeltas(level, morphTo, x0, z0, positions, normals, top, vertices);
            builder.morphTarget("coarser", deltas[0], deltas[1], null);
        }
        return builder.buildFlat();
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
     * The morph target onto level {@code coarse}'s surface: for each vertex, how far it moves (in height) and how its
     * normal turns to lie on the coarser tile's triangles with their normals (heights and normals of the coarser
     * grid, from its own apron, as that tile has them; between its vertices on its two triangles a quad). Skirt
     * vertices move with the edge vertex they hang from.
     */
    float[][] morphDeltas(int level, int coarse, double x0, double z0, float[] positions, float[] normals, int top,
        int vertices) {
        int n = grid.quads();
        double s = grid.spacing(level);
        double sc = grid.spacing(coarse);
        int r = 1 << (coarse - level);
        int m = Math.max(1, n / r); // coarse quads across this tile
        int a = m + 3;
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
        float[] dp = new float[3 * vertices];
        float[] dn = new float[3 * vertices];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                double fu = (double) i * s / sc;
                double fv = (double) j * s / sc;
                int ci = Math.min((int) Math.floor(fu), m - 1);
                int cj = Math.min((int) Math.floor(fv), m - 1);
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
                int v = j * (n + 1) + i;
                dp[3 * v + 1] = (float) (hc - positions[3 * v + 1]);
                dn[3 * v] = (float) (nx / nl - normals[3 * v]);
                dn[3 * v + 1] = (float) (ny / nl - normals[3 * v + 1]);
                dn[3 * v + 2] = (float) (nz / nl - normals[3 * v + 2]);
            }
        }
        // the skirts' vertices, after the top ones in the order build() lays them: each side's edge in turn
        int skirt = top;
        for (int side = 0; skirt < vertices && side < 4; side++) {
            for (int k = 0; k <= n; k++) {
                int from = switch (side) {
                    case 0 -> k;
                    case 1 -> n * (n + 1) + k;
                    case 2 -> k * (n + 1);
                    default -> k * (n + 1) + n;
                };
                System.arraycopy(dp, 3 * from, dp, 3 * (skirt + k), 3);
                System.arraycopy(dn, 3 * from, dn, 3 * (skirt + k), 3);
            }
            skirt += n + 1;
        }
        return new float[][] {dp, dn};
    }
}
