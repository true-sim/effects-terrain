package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.math.color.Color;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.terrain.data.SurfaceParameters;
import com.truesim.effects.terrain.data.TerrainModifier;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.render.MaterialData;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A film of terrain modifiers on Lauterbrunnen's valley floor (effects-terrain's cache): the same orbit first without
 * them, then with a brook's bed cut in (gravel, sand at its margins) and a cobbled road graded across the floor (cut
 * and filled to a smooth profile, dirt shoulders), into {@code -Pscreenshots=<dir>} as
 * {@code terrain-modifiers.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class TerrainModifierFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FPS = 30;
    private static final double CX = 150;
    private static final double CZ = 300;

    /** A brook meandering south across the floor: a bed 8 m wide, 1.5 m deep, gravel; sand on 3 m beside it. */
    static TerrainModifier brook() {
        return new TerrainModifier() {
            private double distance(double x, double z) {
                return Math.abs(x - (CX + 30 * Math.sin(z / 80)));
            }

            @Override
            public double height(double x, double z, double footprint, double height) {
                double d = distance(x, z);
                double u = d / 4;
                return u < 1 ? height - 1.5 * (1 - u * u) : height;
            }

            @Override
            public double materials(double x, double z, double footprint, double[] cover) {
                double d = distance(x, z);
                double gravel = 1 - TerrainLayers.smoothstep(3.5, 5, d);
                double sand = (1 - gravel) * 0.8 * (1 - TerrainLayers.smoothstep(5, 8, d));
                cover[GRAVEL] = gravel;
                cover[SAND] = sand;
                return gravel + sand;
            }

            @Override
            public Bounds bounds() {
                return new Bounds(CX - 60, -600, CX + 60, 1200);
            }
        };
    }

    /**
     * A cobbled road across the floor along z = 300, 6 m wide: its height a smooth profile of the ground along it
     * (a running mean over 60 m), cut and filled, blending back over 8 m beside it; dirt shoulders 2 m wide.
     */
    static TerrainModifier road(TerrainSurface ground) {
        double x0 = -400;
        int n = 1000;
        double[] raw = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            raw[i] = ground.height(x0 + i, CZ, 1);
        }
        double[] profile = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double sum = 0;
            int count = 0;
            for (int k = Math.max(0, i - 30); k <= Math.min(n, i + 30); k++) {
                sum += raw[k];
                count++;
            }
            profile[i] = sum / count;
        }
        return new TerrainModifier() {
            private double profile(double x) {
                double f = Math.clamp(x - x0, 0, n);
                int i = Math.min((int) f, n - 1);
                double t = f - i;
                return profile[i] * (1 - t) + profile[i + 1] * t;
            }

            @Override
            public double height(double x, double z, double footprint, double height) {
                double d = Math.abs(z - CZ);
                double keep = TerrainLayers.smoothstep(3, 11, d);
                return profile(x) + keep * (height - profile(x));
            }

            @Override
            public double materials(double x, double z, double footprint, double[] cover) {
                double d = Math.abs(z - CZ);
                double cobbles = 1 - TerrainLayers.smoothstep(2.5, 3.5, d);
                double dirt = (1 - cobbles) * (1 - TerrainLayers.smoothstep(4, 6, d));
                cover[COBBLES] = cobbles;
                cover[DIRT] = dirt;
                return cobbles + dirt;
            }

            @Override
            public Bounds bounds() {
                return new Bounds(x0, CZ - 12, x0 + n, CZ + 12);
            }
        };
    }

    static MaterialData material() {
        TerrainMaterial.Layer[] layers = TestTextures.alpine();
        Color white = new Color(1, 1, 1, 1);
        TerrainParameters rules = TerrainParameters.DEFAULTS.withRockColor(white).withGrassColor(white)
            .withScreeColor(white).withSnowColor(white);
        TerrainMaterial.Cover cover = new TerrainMaterial.Cover(
            TestTextures.layer("gravel", 0.30f, 0.29f, 0.27f, 0.5f, 3, 0.9f, 48, 2),
            TestTextures.layer("cobbles", 0.24f, 0.23f, 0.22f, 0.6f, 4, 0.8f, 24, 2),
            TestTextures.layer("dirt", 0.26f, 0.19f, 0.13f, 0.35f, 2, 0.95f, 12, 4),
            TestTextures.layer("sand", 0.45f, 0.41f, 0.33f, 0.25f, 1, 0.9f, 32, 4));
        rules = rules.withGravelColor(white).withCobblesColor(white).withDirtColor(white).withSandColor(white);
        return TerrainMaterial.data(rules, layers[0], layers[1], layers[2], layers[3], cover);
    }

    @Test
    void modifiers() throws Exception {
        TerrainSite lauterbrunnen = TestSites.lauterbrunnen();
        assumeThat(lauterbrunnen).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path dir = Path.of(System.getProperty("truesim.screenshots", "build/films"));
        Files.createDirectories(dir);
        Process ffmpeg = TerrainFilms.ffmpeg(dir.resolve("terrain-modifiers.mp4"), WIDTH, HEIGHT, 20);
        byte[] rgb = new byte[3 * WIDTH * HEIGHT];
        try (TestGpu gpu = TestGpu.open(); OutputStream out = ffmpeg.getOutputStream()) {
            for (int pass = 0; pass < 2; pass++) {
                TerrainFilms.Sky sky = TerrainFilms.Sky.of(gpu, WIDTH, HEIGHT);
                try (TerrainRig rig = sky.rig()) {
                    TerrainSite site = TerrainFilms.load(rig);
                    TerrainSurface ground = new TerrainSurface(site,
                        TerrainSettings.surface(TerrainFilms.settings()));
                    TerrainModifiers modifiers = pass == 0 ? TerrainModifiers.NONE
                        : TerrainModifiers.of(brook(), road(ground));
                    Terrain terrain = rig.terrain(site, material().ref(), modifiers);
                    rig.fov(Math.toRadians(50));
                    double seconds = pass == 0 ? 4 : 9;
                    double start = pass == 0 ? 0 : 0.25;
                    place(rig, terrain, start);
                    rig.settle(400);
                    double ev = 12;
                    int frames = (int) (seconds * FPS);
                    for (int f = 0; f < frames; f++) {
                        place(rig, terrain, start + f / FPS / 12);
                        rig.graph.settings().set(TerrainFilms.EV100, (float) ev);
                        TerrainFilms.step(rig, terrain);
                        float[] color = rig.rgb();
                        double logSum = 0;
                        double units = 1.2 * Math.pow(2, ev);
                        for (int i = 0; i < WIDTH * HEIGHT; i++) {
                            double luminance = 0.2126 * color[3 * i] + 0.7152 * color[3 * i + 1]
                                + 0.0722 * color[3 * i + 2];
                            logSum += Math.log(1e-4 + luminance * units);
                            rgb[3 * i] = (byte) TerrainFilms.tone(color[3 * i]);
                            rgb[3 * i + 1] = (byte) TerrainFilms.tone(color[3 * i + 1]);
                            rgb[3 * i + 2] = (byte) TerrainFilms.tone(color[3 * i + 2]);
                        }
                        double target = AtmosphereExposure.ev100(Math.exp(logSum / (WIDTH * HEIGHT))) + 1.3;
                        ev = f == 0 ? target : AtmosphereExposure.adapt(ev, target, 1 / FPS, 0.6);
                        out.write(rgb);
                    }
                    System.out.printf(Locale.ROOT, "modifiers pass %d: %d frames, %d tiles%n", pass, frames,
                        terrain.shownTiles());
                }
            }
        }
        assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
    }

    /** The camera at turn {@code t} (0 to 1) of an orbit 110 m round the crossing, 35 m above the ground there. */
    private static void place(TerrainRig rig, Terrain terrain, double t) {
        double angle = Math.toRadians(200) + t * Math.toRadians(140);
        double x = CX + 110 * Math.cos(angle);
        double z = CZ + 110 * Math.sin(angle);
        double centre = terrain.heightAt(CX, CZ);
        double y = Math.max(terrain.heightAt(x, z), centre) + 35;
        rig.look(x, y, z, CX - x, centre - y, CZ - z);
    }

    /** Smoke check of the film's modifiers on synthetic ground: the road is level across, the bed below. */
    @Test
    void theFilmsModifiersDoWhatTheySay() {
        TerrainSurface ground = new TerrainSurface(TestSites.rugged(64), SurfaceParameters.DATA);
        TerrainModifier road = road(ground);
        double a = road.height(100, CZ, 1, 0);
        double b = road.height(100, CZ + 2, 1, 50);
        assertThat(a).isEqualTo(b);
        assertThat(brook().height(CX, 0, 1, 10)).isEqualTo(8.5);
    }
}
