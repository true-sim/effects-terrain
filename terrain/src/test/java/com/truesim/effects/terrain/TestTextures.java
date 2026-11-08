package com.truesim.effects.terrain;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Placeholder texture sets for the tests and films, generated assets (the showcase gives Poly Haven's): per layer a
 * tileable albedo of value noise round its color, a tangent-space normal map from a tileable height, and a roughness
 * in green. Tileable on their own period, so they repeat without seams.
 */
final class TestTextures {

    /** What a texture shows: a layer's look, and which of its maps. */
    record Look(String layer, int kind, float r, float g, float b, float contrast, float bumps, float roughness,
        int cells) {
    }

    static final int ALBEDO = 0;
    static final int NORMAL = 1;
    static final int ROUGHNESS = 2;
    private static final int SIZE = 256;

    static final Generator<Look, TextureData> TEXTURES = (look, seed) -> make(look, seed);

    private TestTextures() {
    }

    /** The layer's texture set, its textures repeating every {@code size} metres. */
    static TerrainMaterial.Layer layer(String name, float r, float g, float b, float contrast, float bumps,
        float roughness, int cells, double size) {
        return new TerrainMaterial.Layer(ref(new Look(name, ALBEDO, r, g, b, contrast, bumps, roughness, cells)),
            ref(new Look(name, NORMAL, r, g, b, contrast, bumps, roughness, cells)),
            ref(new Look(name, ROUGHNESS, r, g, b, contrast, bumps, roughness, cells)), size);
    }

    private static AssetRef<TextureData> ref(Look look) {
        return AssetRef.generated(RenderAssets.TEXTURE, TEXTURES, look, look.layer().hashCode());
    }

    /** Tileable fractal value noise, 0 to 1, at texel (x, y) of a SIZE texture, {@code cells} cells a side. */
    private static double fractal(int x, int y, int cells, long seed) {
        double sum = 0;
        double amplitude = 0.5;
        int c = cells;
        for (int octave = 0; octave < 5; octave++) {
            sum += amplitude * value(x * c / (double) SIZE, y * c / (double) SIZE, c, seed + octave);
            amplitude *= 0.5;
            c *= 2;
        }
        return sum / 0.97;
    }

    private static double value(double x, double y, int period, long seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double u = x - ix;
        double v = y - iy;
        u = u * u * (3 - 2 * u);
        v = v * v * (3 - 2 * v);
        double a = lattice(ix, iy, period, seed);
        double b = lattice(ix + 1, iy, period, seed);
        double c = lattice(ix, iy + 1, period, seed);
        double d = lattice(ix + 1, iy + 1, period, seed);
        return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
    }

    private static double lattice(int x, int y, int period, long seed) {
        long h = Math.floorMod(x, period) * 0x9E3779B97F4A7C15L + Math.floorMod(y, period) * 0xC2B2AE3D27D4EB4FL
            + seed * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static TextureData make(Look look, long seed) {
        MemorySegment texels = Arena.ofAuto().allocate(4L * SIZE * SIZE);
        double[] height = new double[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                height[y * SIZE + x] = fractal(x, y, look.cells(), seed);
            }
        }
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double h = height[y * SIZE + x];
                int r;
                int g;
                int b;
                if (look.kind() == ALBEDO) {
                    double shade = 1 + (h - 0.5) * 2 * look.contrast();
                    r = srgb(look.r() * shade);
                    g = srgb(look.g() * shade);
                    b = srgb(look.b() * shade);
                } else if (look.kind() == NORMAL) {
                    double dx = height[y * SIZE + (x + 1) % SIZE] - height[y * SIZE + (x + SIZE - 1) % SIZE];
                    double dy = height[((y + 1) % SIZE) * SIZE + x] - height[((y + SIZE - 1) % SIZE) * SIZE + x];
                    double nx = -dx * look.bumps();
                    double ny = -dy * look.bumps();
                    double length = Math.sqrt(nx * nx + ny * ny + 1);
                    r = (int) Math.round((nx / length * 0.5 + 0.5) * 255);
                    g = (int) Math.round((ny / length * 0.5 + 0.5) * 255);
                    b = (int) Math.round((1 / length * 0.5 + 0.5) * 255);
                } else {
                    r = 255;
                    g = (int) Math.round(Math.clamp(look.roughness() + (0.5 - h) * 0.2, 0, 1) * 255);
                    b = 0;
                }
                texels.setAtIndex(ValueLayout.JAVA_INT, (long) y * SIZE + x, 0xff000000 | b << 16 | g << 8 | r);
            }
        }
        return new TextureData(SIZE, SIZE, look.kind() == ALBEDO ? TextureData.ColorSpace.SRGB
            : TextureData.ColorSpace.LINEAR, texels);
    }

    private static int srgb(double linear) {
        double c = Math.clamp(linear, 0, 1);
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    /** The layers of the films: grey limestone, alpine meadow, pale scree, snow; the rules' colors take them as white. */
    static TerrainMaterial.Layer[] alpine() {
        return new TerrainMaterial.Layer[] {
            layer("rock", 0.33f, 0.31f, 0.29f, 0.45f, 6, 0.85f, 6, 16),
            layer("grass", 0.11f, 0.17f, 0.05f, 0.35f, 2, 0.95f, 12, 8),
            layer("scree", 0.38f, 0.36f, 0.33f, 0.4f, 4, 0.9f, 16, 8),
            layer("snow", 0.82f, 0.85f, 0.9f, 0.08f, 1, 0.5f, 4, 16)};
    }
}
