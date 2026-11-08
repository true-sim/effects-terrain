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
public final class TileMeshes implements Generator<Long, MeshData> {

    private static final VertexFormat FORMAT = VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.UV0,
        VertexAttribute.UV1, VertexAttribute.COLOR);

    private final TerrainSurface surface;
    private final TerrainGrid grid;
    private final double skirtMargin;
    private final double curvatureScale;
    private volatile MeshPool pool;
    /** What each tile built so far is: its error against the finer level, and its height range. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Info> infos = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean skirts = true;

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

    public TerrainSurface surface() {
        return surface;
    }

    public TerrainGrid grid() {
        return grid;
    }

    @Override
    public MeshData generate(Long key, long seed) {
        return build(key);
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
                double laplacian = (height[c + 1] + height[c - 1] + height[c + a] + height[c - a] - 4 * h) / (s * s);
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
        return new MeshBuilder(FORMAT, vertices).pool(pool).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).attribute(VertexAttribute.UV0, uv0)
            .attribute(VertexAttribute.UV1, uv1).attribute(VertexAttribute.COLOR, colors).submesh(indices)
            .buildFlat();
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
}
