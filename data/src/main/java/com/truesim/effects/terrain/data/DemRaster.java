package com.truesim.effects.terrain.data;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An imported elevation raster: one source's tiles merged and cropped, heights in metres on a regular grid of its
 * crs (geographic degrees, or LV95 metres), with a pyramid of coarser levels (2 x 2 averages) for sampling at a
 * footprint wider than its samples. Sample (i, j) of level 0 is centred at ({@code x + i dx}, {@code y - j dy}); NaN
 * marks no data. Stored as a {@code .dem} file: a header, then the heights as little-endian floats.
 */
public final class DemRaster {

    /** The crs of geographic rasters: x longitude, y latitude, degrees. */
    public static final int GEOGRAPHIC = 0;
    /** The crs of Swiss LV95 rasters: x easting, y northing, metres. */
    public static final int LV95 = 1;

    private static final byte[] MAGIC = "TSDEM1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = MAGIC.length + 4 + 4 * 8 + 2 * 4;
    private static final double METRES_PER_DEGREE = Math.PI * LocalFrame.A / 180;

    private final int crs;
    private final Level[] levels;
    private final double metres;

    /** One level of the pyramid. */
    private record Level(double x, double y, double dx, double dy, int width, int height, float[] heights) {
    }

    /**
     * A raster of {@code crs} whose sample (0, 0) is centred at ({@code x}, {@code y}), {@code dx} apart along rows
     * and {@code dy} down them, {@code width} x {@code height} samples.
     */
    @AllowAllocation("made on import or load")
    public DemRaster(int crs, double x, double y, double dx, double dy, int width, int height, float[] heights) {
        if (crs != GEOGRAPHIC && crs != LV95) {
            throw new IllegalArgumentException("Unknown crs " + crs);
        }
        if (width < 2 || height < 2 || heights.length != width * height || !(dx > 0) || !(dy > 0)) {
            throw new IllegalArgumentException("A raster of " + width + " x " + height + " samples, " + dx + " by "
                + dy + " apart, with " + heights.length + " heights");
        }
        this.crs = crs;
        this.metres = crs == GEOGRAPHIC
            ? Math.max(dy, dx * Maths.cos(Maths.toRadians(y - dy * height / 2.0))) * METRES_PER_DEGREE
            : Math.max(dx, dy);
        int count = 1;
        while ((width >> count) >= 2 && (height >> count) >= 2 && count < 16) {
            count++;
        }
        levels = new Level[count];
        levels[0] = new Level(x, y, dx, dy, width, height, heights);
        for (int l = 1; l < count; l++) {
            levels[l] = halve(levels[l - 1]);
        }
    }

    @AllowAllocation("made on import or load")
    private static Level halve(Level fine) {
        int w = fine.width / 2;
        int h = fine.height / 2;
        float[] coarse = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double sum = 0;
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    float v = fine.heights[(2 * j + k / 2) * fine.width + 2 * i + k % 2];
                    if (!Float.isNaN(v)) {
                        sum += v;
                        n++;
                    }
                }
                coarse[j * w + i] = n == 4 ? (float) (sum / n) : Float.NaN;
            }
        }
        return new Level(fine.x + fine.dx / 2, fine.y - fine.dy / 2, fine.dx * 2, fine.dy * 2, w, h, coarse);
    }

    /** {@link #GEOGRAPHIC} or {@link #LV95}. */
    public int crs() {
        return crs;
    }

    /** About how many metres apart level 0's samples are (the wider of its two steps). */
    public double metres() {
        return metres;
    }

    public int width() {
        return levels[0].width;
    }

    public int height() {
        return levels[0].height;
    }

    /** Sample (0, 0)'s centre, x. */
    public double x() {
        return levels[0].x;
    }

    /** Sample (0, 0)'s centre, y. */
    public double y() {
        return levels[0].y;
    }

    public double dx() {
        return levels[0].dx;
    }

    public double dy() {
        return levels[0].dy;
    }

    /** Level 0's sample at ({@code i}, {@code j}). */
    public float at(int i, int j) {
        return levels[0].heights[j * levels[0].width + i];
    }

    /** How many levels its pyramid has. */
    public int levelCount() {
        return levels.length;
    }

    /** The pyramid's level for samples about {@code footprint} metres apart: the finest no finer than that. */
    public int levelFor(double footprint) {
        int level = 0;
        while (level + 1 < levels.length && metres * (1 << (level + 1)) <= footprint) {
            level++;
        }
        return level;
    }

    /**
     * The height at ({@code cx}, {@code cy}) of the crs, bilinear between level {@code level}'s samples; NaN outside
     * the raster or next to a sample without data. With {@code clamp}, points outside take the nearest edge's.
     */
    public double sample(double cx, double cy, int level, boolean clamp) {
        Level l = levels[level];
        double fx = (cx - l.x) / l.dx;
        double fy = (l.y - cy) / l.dy;
        if (clamp) {
            fx = Math.clamp(fx, 0, l.width - 1);
            fy = Math.clamp(fy, 0, l.height - 1);
        } else if (!(fx >= 0 && fy >= 0 && fx <= l.width - 1 && fy <= l.height - 1)) {
            return Double.NaN;
        }
        int i = Math.min((int) fx, l.width - 2);
        int j = Math.min((int) fy, l.height - 2);
        double u = fx - i;
        double v = fy - j;
        int at = j * l.width + i;
        float h00 = l.heights[at];
        float h10 = l.heights[at + 1];
        float h01 = l.heights[at + l.width];
        float h11 = l.heights[at + l.width + 1];
        // Exactly the sample on it, whatever its neighbours hold
        if (u == 0 && v == 0) {
            return h00;
        }
        return (h00 * (1 - u) + h10 * u) * (1 - v) + (h01 * (1 - u) + h11 * u) * v;
    }

    /** How far ({@code cx}, {@code cy}) is inside the raster's edge, metres (negative outside). */
    public double inside(double cx, double cy) {
        Level l = levels[0];
        double fx = (cx - l.x) / l.dx;
        double fy = (l.y - cy) / l.dy;
        double samples = Math.min(Math.min(fx, l.width - 1 - fx), Math.min(fy, l.height - 1 - fy));
        return samples * metres;
    }

    /** Bytes the raster takes, its pyramid included. */
    public long bytes() {
        long bytes = 0;
        for (Level l : levels) {
            bytes += 4L * l.heights.length;
        }
        return bytes;
    }

    /** Writes the raster (level 0) as a {@code .dem} file. */
    @AllowAllocation("import")
    public void write(OutputStream out) throws IOException {
        Level l = levels[0];
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC).putInt(crs).putDouble(l.x).putDouble(l.y).putDouble(l.dx).putDouble(l.dy)
            .putInt(l.width).putInt(l.height);
        out.write(header.array());
        ByteBuffer row = ByteBuffer.allocate(4 * l.width).order(ByteOrder.LITTLE_ENDIAN);
        for (int j = 0; j < l.height; j++) {
            row.clear();
            for (int i = 0; i < l.width; i++) {
                row.putFloat(l.heights[j * l.width + i]);
            }
            out.write(row.array());
        }
    }

    /** Reads a {@code .dem} file. */
    @AllowAllocation("load")
    public static DemRaster read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] head = new byte[HEADER];
        data.readFully(head);
        if (!Arrays.equals(Arrays.copyOf(head, MAGIC.length), MAGIC)) {
            throw new IOException("Not a .dem file");
        }
        ByteBuffer h = ByteBuffer.wrap(head, MAGIC.length, HEADER - MAGIC.length).order(ByteOrder.LITTLE_ENDIAN);
        int crs = h.getInt();
        double x = h.getDouble();
        double y = h.getDouble();
        double dx = h.getDouble();
        double dy = h.getDouble();
        int width = h.getInt();
        int height = h.getInt();
        if (width < 2 || height < 2 || (long) width * height > 1L << 28) {
            throw new IOException("A .dem of " + width + " x " + height + " samples");
        }
        byte[] body = new byte[4 * width * height];
        data.readFully(body);
        float[] heights = new float[width * height];
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(heights);
        return new DemRaster(crs, x, y, dx, dy, width, height, heights);
    }
}
