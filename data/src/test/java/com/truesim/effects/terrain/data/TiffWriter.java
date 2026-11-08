package com.truesim.effects.terrain.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/** A minimal GeoTIFF encoder for the tests: an image of {@code width} x {@code height} samples, as the sources write. */
final class TiffWriter {
    private final int W;
    private final int H;
    private final ByteOrder order;
    private final int bits;
    private final int format;
    private int blockWidth;
    private int blockHeight;
    private boolean tiled;
    private int compression = 1;
    private int predictor = 1;
    private double[] tie;
    private double[] scale;
    private boolean point;

    TiffWriter(ByteOrder order, int bits, int format) {
        this(order, bits, format, 37, 23);
    }

    TiffWriter(ByteOrder order, int bits, int format, int width, int height) {
        W = width;
        H = height;
        blockWidth = width;
        blockHeight = height;
        this.order = order;
        this.bits = bits;
        this.format = format;
    }

    TiffWriter tiled(int width, int height) {
        tiled = true;
        blockWidth = width;
        blockHeight = height;
        return this;
    }

    TiffWriter strips(int rows) {
        blockHeight = rows;
        return this;
    }

    TiffWriter compression(int c) {
        compression = c;
        return this;
    }

    TiffWriter predictor(int p) {
        predictor = p;
        return this;
    }

    TiffWriter georeference(double x, double y, double dx, double dy, boolean isPoint) {
        tie = new double[] {0, 0, 0, x, y, 0};
        scale = new double[] {dx, dy, 0};
        point = isPoint;
        return this;
    }

    byte[] write(float[] samples) throws IOException {
        int bytes = bits / 8;
        int across = tiled ? (W + blockWidth - 1) / blockWidth : 1;
        int down = (H + blockHeight - 1) / blockHeight;
        List<byte[]> blocks = new ArrayList<>();
        for (int by = 0; by < down; by++) {
            for (int bx = 0; bx < across; bx++) {
                int rows = tiled ? blockHeight : Math.min(blockHeight, H - by * blockHeight);
                ByteBuffer raw = ByteBuffer.allocate(blockWidth * rows * bytes)
                    .order(predictor == 3 ? ByteOrder.BIG_ENDIAN : order);
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < blockWidth; c++) {
                        int x = bx * blockWidth + c;
                        int y = by * blockHeight + r;
                        float v = x < W && y < H ? samples[y * W + x] : 0;
                        if (bits == 32) {
                            raw.putFloat(v);
                        } else {
                            raw.putShort((short) v);
                        }
                    }
                }
                byte[] data = raw.array();
                predict(data, blockWidth, rows, bytes);
                blocks.add(compress(data));
            }
        }
        ByteBuffer out = ByteBuffer.allocate(1 << 20).order(order);
        out.put(order == ByteOrder.LITTLE_ENDIAN ? new byte[] {'I', 'I'} : new byte[] {'M', 'M'});
        out.putShort((short) 42).putInt(0);
        int[] offsets = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            offsets[i] = out.position();
            out.put(blocks.get(i));
        }
        int tieAt = out.position();
        for (double d : tie) {
            out.putDouble(d);
        }
        int scaleAt = out.position();
        for (double d : scale) {
            out.putDouble(d);
        }
        int keysAt = out.position();
        short[] keys = {1, 1, 0, 1, 1025, 0, 1, (short) (point ? 2 : 1)};
        for (short k : keys) {
            out.putShort(k);
        }
        int offsetsAt = out.position();
        for (int o : offsets) {
            out.putInt(o);
        }
        int countsAt = out.position();
        for (byte[] b : blocks) {
            out.putInt(b.length);
        }
        int nodataAt = out.position();
        out.put("-9999\0".getBytes());
        if ((out.position() & 1) != 0) {
            out.put((byte) 0);
        }
        int ifd = out.position();
        out.putInt(4, ifd);
        List<int[]> entries = new ArrayList<>();
        entries.add(new int[] {256, 3, 1, W});
        entries.add(new int[] {257, 3, 1, H});
        entries.add(new int[] {258, 3, 1, bits});
        entries.add(new int[] {259, 3, 1, compression});
        entries.add(new int[] {277, 3, 1, 1});
        if (!tiled) {
            entries.add(new int[] {273, 4, blocks.size(), blocks.size() == 1 ? offsets[0] : offsetsAt});
            entries.add(new int[] {278, 3, 1, blockHeight});
            entries.add(new int[] {279, 4, blocks.size(), blocks.size() == 1 ? blocks.get(0).length : countsAt});
        }
        entries.add(new int[] {317, 3, 1, predictor});
        if (tiled) {
            entries.add(new int[] {322, 3, 1, blockWidth});
            entries.add(new int[] {323, 3, 1, blockHeight});
            entries.add(new int[] {324, 4, blocks.size(), blocks.size() == 1 ? offsets[0] : offsetsAt});
            entries.add(new int[] {325, 4, blocks.size(), blocks.size() == 1 ? blocks.get(0).length : countsAt});
        }
        entries.add(new int[] {339, 3, 1, format});
        entries.add(new int[] {33550, 12, 3, scaleAt});
        entries.add(new int[] {33922, 12, 6, tieAt});
        entries.add(new int[] {34735, 3, keys.length, keysAt});
        entries.add(new int[] {42113, 2, 6, nodataAt});
        out.putShort((short) entries.size());
        for (int[] e : entries) {
            out.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
            if (e[1] == 3 && e[2] == 1) {
                out.putShort((short) e[3]).putShort((short) 0); // left in the field, either order
            } else {
                out.putInt(e[3]);
            }
        }
        out.putInt(0);
        byte[] file = new byte[out.position()];
        out.flip();
        out.get(file);
        return file;
    }

    private void predict(byte[] data, int width, int rows, int bytes) {
        if (predictor == 2) {
            ByteBuffer d = ByteBuffer.wrap(data).order(order);
            for (int r = 0; r < rows; r++) {
                for (int c = width - 1; c >= 1; c--) {
                    int at = (r * width + c) * bytes;
                    if (bytes == 2) {
                        d.putShort(at, (short) (d.getShort(at) - d.getShort(at - 2)));
                    } else {
                        d.putInt(at, d.getInt(at) - d.getInt(at - 4));
                    }
                }
            }
        } else if (predictor == 3) {
            int rowBytes = width * bytes;
            byte[] planes = new byte[rowBytes];
            for (int r = 0; r < rows; r++) {
                int start = r * rowBytes;
                for (int c = 0; c < width; c++) {
                    for (int k = 0; k < bytes; k++) {
                        planes[k * width + c] = data[start + c * bytes + k];
                    }
                }
                for (int i = rowBytes - 1; i >= 1; i--) {
                    planes[i] -= planes[i - 1];
                }
                System.arraycopy(planes, 0, data, start, rowBytes);
            }
        }
    }

    private byte[] compress(byte[] data) {
        return switch (compression) {
            case 8 -> {
                Deflater deflater = new Deflater();
                deflater.setInput(data);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
                deflater.end();
                yield out.toByteArray();
            }
            case 5 -> lzw(data);
            default -> data;
        };
    }

    /** TIFF LZW, codes widening one early, a clear first and when the table fills. */
    private static byte[] lzw(byte[] data) {
        Bits bits = new Bits();
        Map<String, Integer> table = new HashMap<>();
        int width = 9;
        int next = 258;
        bits.put(256, width);
        String w = "";
        for (byte b : data) {
            String wc = w + (char) (b & 0xff);
            if (wc.length() == 1 || table.containsKey(wc)) {
                w = wc;
                continue;
            }
            bits.put(w.length() == 1 ? w.charAt(0) : table.get(w), width);
            table.put(wc, next++);
            // The decoder adds its entry a code later: it widens at 511, so the encoder at 512
            if (next >= 4094) {
                bits.put(256, width);
                table.clear();
                next = 258;
                width = 9;
            } else if (next >= (1 << width)) {
                width++;
            }
            w = String.valueOf((char) (b & 0xff));
        }
        if (!w.isEmpty()) {
            bits.put(w.length() == 1 ? w.charAt(0) : table.get(w), width);
            next++;
            if (next >= (1 << width) && width < 12) {
                width++;
            }
        }
        bits.put(257, width);
        return bits.bytes();
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void put(int code, int width) {
            buffer = (buffer << width) | code;
            count += width;
            while (count >= 8) {
                out.write((int) (buffer >>> (count - 8)) & 0xff);
                count -= 8;
            }
        }

        byte[] bytes() {
            if (count > 0) {
                out.write((int) (buffer << (8 - count)) & 0xff);
            }
            return out.toByteArray();
        }
    }
}
