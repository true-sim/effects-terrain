package com.truesim.effects.terrain.data;

import com.truesim.annotations.AllowAllocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A single-band elevation GeoTIFF read with the JDK alone: the first image of the file (a cloud-optimized GeoTIFF's
 * full resolution; its overviews are skipped), in strips or tiles, uncompressed, LZW or Deflate, with no, horizontal or
 * floating-point prediction, of 32-bit floats or 16-bit integers, either byte order; its georeference from the model
 * tie point and pixel scale, and whether it sits on a sample (GeoTIFF's RasterPixelIsPoint) or on its corner.
 *
 * @param width samples per row
 * @param height rows
 * @param heights the samples, row by row from the top; NaN where the file has none (its no-data value)
 * @param x the georeference's x (longitude or easting) of sample (0, 0)'s corner or centre, as {@link #pixelIsPoint}
 * @param y the same's y (latitude or northing)
 * @param dx the step from sample to sample along a row
 * @param dy the step from row to row, down the image (positive: y decreases down the rows)
 * @param pixelIsPoint whether ({@code x}, {@code y}) is the centre of sample (0, 0) rather than its corner
 */
@AllowAllocation("read on import")
public record GeoTiff(int width, int height, float[] heights, double x, double y, double dx, double dy,
    boolean pixelIsPoint) {

    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_LENGTH = 257;
    private static final int BITS_PER_SAMPLE = 258;
    private static final int COMPRESSION = 259;
    private static final int STRIP_OFFSETS = 273;
    private static final int SAMPLES_PER_PIXEL = 277;
    private static final int ROWS_PER_STRIP = 278;
    private static final int STRIP_BYTE_COUNTS = 279;
    private static final int PREDICTOR = 317;
    private static final int TILE_WIDTH = 322;
    private static final int TILE_LENGTH = 323;
    private static final int TILE_OFFSETS = 324;
    private static final int TILE_BYTE_COUNTS = 325;
    private static final int SAMPLE_FORMAT = 339;
    private static final int MODEL_PIXEL_SCALE = 33550;
    private static final int MODEL_TIEPOINT = 33922;
    private static final int GEO_KEYS = 34735;
    private static final int GDAL_NODATA = 42113;
    private static final int RASTER_TYPE_KEY = 1025;

    /** The centre of sample ({@code column}, {@code row}) in the georeference's x. */
    public double centreX(int column) {
        return x + (column + (pixelIsPoint ? 0 : 0.5)) * dx;
    }

    /** The centre of row {@code row} in the georeference's y. */
    public double centreY(int row) {
        return y - (row + (pixelIsPoint ? 0 : 0.5)) * dy;
    }

    /** The sample at ({@code column}, {@code row}). */
    public float at(int column, int row) {
        return heights[row * width + column];
    }

    /** Reads the GeoTIFF at {@code path}. */
    public static GeoTiff read(Path path) throws IOException {
        return read(Files.readAllBytes(path));
    }

    /** Reads a GeoTIFF from its bytes. */
    public static GeoTiff read(byte[] file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(file);
        if (file.length < 8) {
            throw new IOException("Not a TIFF: " + file.length + " bytes");
        }
        if (file[0] == 'I' && file[1] == 'I') {
            b.order(ByteOrder.LITTLE_ENDIAN);
        } else if (file[0] == 'M' && file[1] == 'M') {
            b.order(ByteOrder.BIG_ENDIAN);
        } else {
            throw new IOException("Not a TIFF");
        }
        int magic = b.getShort(2) & 0xffff;
        if (magic != 42) {
            throw new IOException(magic == 43 ? "BigTIFF is not read" : "Not a TIFF: magic " + magic);
        }
        Ifd ifd = new Ifd(b, (int) (b.getInt(4) & 0xffffffffL));
        int width = (int) ifd.first(IMAGE_WIDTH);
        int height = (int) ifd.first(IMAGE_LENGTH);
        int bits = (int) ifd.first(BITS_PER_SAMPLE, 1);
        int format = (int) ifd.first(SAMPLE_FORMAT, 1); // 1 unsigned, 2 signed, 3 float
        int compression = (int) ifd.first(COMPRESSION, 1);
        int predictor = (int) ifd.first(PREDICTOR, 1);
        if ((int) ifd.first(SAMPLES_PER_PIXEL, 1) != 1) {
            throw new IOException("Elevation has one band");
        }
        if (!(bits == 32 && format == 3) && !(bits == 16 && (format == 1 || format == 2))) {
            throw new IOException("Samples of " + bits + " bits, format " + format + " are not read");
        }
        float nodata = Float.NaN;
        String nodataText = ifd.ascii(GDAL_NODATA);
        if (nodataText != null && !nodataText.isBlank()) {
            nodata = Float.parseFloat(nodataText.trim());
        }
        float[] heights = new float[width * height];
        boolean tiled = ifd.has(TILE_WIDTH);
        int blockWidth = tiled ? (int) ifd.first(TILE_WIDTH) : width;
        int blockHeight = tiled ? (int) ifd.first(TILE_LENGTH)
            : (int) Math.min(height, ifd.first(ROWS_PER_STRIP, height));
        long[] offsets = ifd.all(tiled ? TILE_OFFSETS : STRIP_OFFSETS);
        long[] counts = ifd.all(tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS);
        int across = tiled ? (width + blockWidth - 1) / blockWidth : 1;
        int bytesPerSample = bits / 8;
        for (int block = 0; block < offsets.length; block++) {
            byte[] raw = new byte[(int) counts[block]];
            System.arraycopy(file, (int) offsets[block], raw, 0, raw.length);
            int rows = blockHeight;
            if (!tiled) {
                rows = Math.min(blockHeight, height - block * blockHeight);
            }
            byte[] data = decompress(compression, raw, blockWidth * rows * bytesPerSample);
            unpredict(predictor, data, blockWidth, rows, bytesPerSample, b.order());
            ByteBuffer d = ByteBuffer.wrap(data).order(predictor == 3 ? ByteOrder.BIG_ENDIAN : b.order());
            int x0 = (block % across) * blockWidth;
            int y0 = (block / across) * blockHeight;
            for (int row = 0; row < rows && y0 + row < height; row++) {
                for (int column = 0; column < blockWidth && x0 + column < width; column++) {
                    int at = (row * blockWidth + column) * bytesPerSample;
                    float value = switch (bits) {
                        case 32 -> d.getFloat(at);
                        default -> format == 2 ? d.getShort(at) : d.getShort(at) & 0xffff;
                    };
                    heights[(y0 + row) * width + x0 + column] = value == nodata ? Float.NaN : value;
                }
            }
        }
        double[] scale = ifd.doubles(MODEL_PIXEL_SCALE);
        double[] tie = ifd.doubles(MODEL_TIEPOINT);
        if (scale == null || tie == null || tie.length < 6) {
            throw new IOException("No georeference (model tie point and pixel scale)");
        }
        boolean point = false;
        long[] keys = ifd.all(GEO_KEYS);
        if (keys != null) {
            for (int k = 4; k + 3 < keys.length; k += 4) {
                if (keys[k] == RASTER_TYPE_KEY && keys[k + 1] == 0) {
                    point = keys[k + 3] == 2;
                }
            }
        }
        // The tie point names raster (i, j) at model (x, y): moved to raster (0, 0)
        double x = tie[3] - tie[0] * scale[0];
        double y = tie[4] + tie[1] * scale[1];
        return new GeoTiff(width, height, heights, x, y, scale[0], scale[1], point);
    }

    private static byte[] decompress(int compression, byte[] raw, int expected) throws IOException {
        return switch (compression) {
            case 1 -> raw;
            case 8, 32946 -> inflate(raw, expected);
            case 5 -> lzw(raw, expected);
            default -> throw new IOException("Compression " + compression + " is not read");
        };
    }

    private static byte[] inflate(byte[] raw, int expected) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(raw);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int got = inflater.inflate(out, n, expected - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n != expected) {
                throw new IOException("A block inflated to " + n + " bytes, not " + expected);
            }
            return out;
        } catch (DataFormatException e) {
            throw new IOException("Bad Deflate data", e);
        } finally {
            inflater.end();
        }
    }

    /** TIFF's LZW: codes of 9 to 12 bits, most significant first, widening one code early. */
    private static byte[] lzw(byte[] raw, int expected) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) {
            table[i] = new byte[] {(byte) i};
        }
        int next = 258;
        int width = 9;
        long bitBuffer = 0;
        int bitCount = 0;
        int position = 0;
        byte[] previous = null;
        while (true) {
            while (bitCount < width && position < raw.length) {
                bitBuffer = (bitBuffer << 8) | (raw[position++] & 0xff);
                bitCount += 8;
            }
            if (bitCount < width) {
                break;
            }
            int code = (int) ((bitBuffer >>> (bitCount - width)) & ((1 << width) - 1));
            bitCount -= width;
            if (code == 257) { // end of information
                break;
            }
            if (code == 256) { // clear
                next = 258;
                width = 9;
                previous = null;
                continue;
            }
            byte[] entry;
            if (code < next && table[code] != null) {
                entry = table[code];
                if (previous != null) {
                    table[next++] = concat(previous, entry[0]);
                }
            } else if (code == next && previous != null) {
                entry = concat(previous, previous[0]);
                table[next++] = entry;
            } else {
                throw new IOException("Bad LZW code " + code);
            }
            out.write(entry, 0, entry.length);
            previous = entry;
            if (next + 1 >= (1 << width) && width < 12) {
                width++;
            }
        }
        byte[] data = out.toByteArray();
        if (data.length < expected) {
            throw new IOException("A block decoded to " + data.length + " bytes, not " + expected);
        }
        return data;
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] c = new byte[a.length + 1];
        System.arraycopy(a, 0, c, 0, a.length);
        c[a.length] = b;
        return c;
    }

    /**
     * Undoes prediction in place: horizontal (2) differences sample by sample in the file's order; floating point (3)
     * differences byte by byte along the row, whose bytes are then each sample's, most significant first, in planes.
     */
    private static void unpredict(int predictor, byte[] data, int width, int rows, int bytes, ByteOrder order)
        throws IOException {
        switch (predictor) {
            case 1 -> { }
            case 2 -> {
                ByteBuffer d = ByteBuffer.wrap(data).order(order);
                for (int row = 0; row < rows; row++) {
                    for (int column = 1; column < width; column++) {
                        int at = (row * width + column) * bytes;
                        if (bytes == 2) {
                            d.putShort(at, (short) (d.getShort(at) + d.getShort(at - 2)));
                        } else {
                            d.putInt(at, d.getInt(at) + d.getInt(at - 4));
                        }
                    }
                }
            }
            case 3 -> {
                int rowBytes = width * bytes;
                byte[] planes = new byte[rowBytes];
                for (int row = 0; row < rows; row++) {
                    int start = row * rowBytes;
                    for (int i = 1; i < rowBytes; i++) {
                        data[start + i] += data[start + i - 1];
                    }
                    System.arraycopy(data, start, planes, 0, rowBytes);
                    for (int column = 0; column < width; column++) {
                        for (int k = 0; k < bytes; k++) {
                            data[start + column * bytes + k] = planes[k * width + column];
                        }
                    }
                }
            }
            default -> throw new IOException("Predictor " + predictor + " is not read");
        }
    }

    /** One image file directory: its entries by tag, read lazily from the file. */
    private static final class Ifd {
        private final ByteBuffer b;
        private final int[] tags;
        private final int[] types;
        private final long[] counts;
        private final int[] values; // where each entry's value lies: in the entry itself or at its offset

        Ifd(ByteBuffer b, int offset) {
            this.b = b;
            int n = b.getShort(offset) & 0xffff;
            tags = new int[n];
            types = new int[n];
            counts = new long[n];
            values = new int[n];
            for (int i = 0; i < n; i++) {
                int entry = offset + 2 + 12 * i;
                tags[i] = b.getShort(entry) & 0xffff;
                types[i] = b.getShort(entry + 2) & 0xffff;
                counts[i] = b.getInt(entry + 4) & 0xffffffffL;
                long size = counts[i] * size(types[i]);
                values[i] = size <= 4 ? entry + 8 : (int) (b.getInt(entry + 8) & 0xffffffffL);
            }
        }

        private static int size(int type) {
            return switch (type) {
                case 3, 8 -> 2;
                case 4, 9, 11 -> 4;
                case 5, 10, 12, 16, 17 -> 8;
                default -> 1;
            };
        }

        private int find(int tag) {
            for (int i = 0; i < tags.length; i++) {
                if (tags[i] == tag) {
                    return i;
                }
            }
            return -1;
        }

        boolean has(int tag) {
            return find(tag) >= 0;
        }

        long first(int tag) throws IOException {
            long[] all = all(tag);
            if (all == null) {
                throw new IOException("No TIFF tag " + tag);
            }
            return all[0];
        }

        long first(int tag, long fallback) {
            long[] all = all(tag);
            return all == null ? fallback : all[0];
        }

        long[] all(int tag) {
            int i = find(tag);
            if (i < 0) {
                return null;
            }
            long[] out = new long[(int) counts[i]];
            for (int k = 0; k < out.length; k++) {
                out[k] = switch (types[i]) {
                    case 3 -> b.getShort(values[i] + 2 * k) & 0xffff;
                    case 4 -> b.getInt(values[i] + 4 * k) & 0xffffffffL;
                    case 16 -> b.getLong(values[i] + 8 * k);
                    default -> b.get(values[i] + k) & 0xff;
                };
            }
            return out;
        }

        double[] doubles(int tag) {
            int i = find(tag);
            if (i < 0 || types[i] != 12) {
                return null;
            }
            double[] out = new double[(int) counts[i]];
            for (int k = 0; k < out.length; k++) {
                out[k] = b.getDouble(values[i] + 8 * k);
            }
            return out;
        }

        String ascii(int tag) {
            int i = find(tag);
            if (i < 0) {
                return null;
            }
            byte[] text = new byte[(int) counts[i]];
            for (int k = 0; k < text.length; k++) {
                text[k] = b.get(values[i] + k);
            }
            int end = text.length;
            while (end > 0 && text[end - 1] == 0) {
                end--;
            }
            return new String(text, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
