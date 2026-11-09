package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.math.color.Color;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The cliffs' film ({@code TRUESIM_SCREENSHOTS=true}, {@code -Pscreenshots=<directory>}): Lauterbrunnen's walls at
 * morning sun from the valley floor, then a slow flight along the Staubbach wall, each as the terrain was (before: the
 * data's faces, rock projected by the rules alone) and as it is (after: the cliff relief on tiles of 0.5 m, the cliff
 * layer). With Poly Haven's texture sets where the showcase's cache holds them ({@code ~/.truesim/polyhaven}, or
 * {@code -Dtruesim.polyhaven}), else the tests' generated ones. {@code cliffs.mp4}, 1280 x 720, and stills of the
 * shots ({@code cliffs-*.png}).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class CliffFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FPS = 30;
    private static final double FLOOR = 800;

    /** A shot: the camera's path from {@code from} to {@code to} (position, look-at) over {@code seconds}. */
    record Shot(String name, double seconds, double[] from, double[] to) {
        double[] at(double u) {
            double s = u * u * (3 - 2 * u);
            double[] out = new double[6];
            for (int k = 0; k < 6; k++) {
                out[k] = from[k] + (to[k] - from[k]) * s;
            }
            return out;
        }
    }

    /** The walls from the valley floor south of the village, looking west at the Staubbach wall, panning slowly. */
    static final Shot FLOOR_VIEW = new Shot("floor", 7, new double[] {60, FLOOR + 3, 520, -380, FLOOR + 240, 180},
        new double[] {40, FLOOR + 3, 470, -360, FLOOR + 230, -40});
    /** Along the Staubbach wall, 170 m out from it, half way up. */
    static final Shot FLIGHT = new Shot("staubbach", 11, new double[] {-170, FLOOR + 150, 700, -420, FLOOR + 130, 380},
        new double[] {-160, FLOOR + 170, -120, -400, FLOOR + 150, -380});

    static final List<Shot> SHOTS = List.of(FLOOR_VIEW, FLIGHT);

    /** The Poly Haven cache, or null. */
    static Path polyHaven() {
        Path folder = Path.of(System.getProperty("truesim.polyhaven",
            Path.of(System.getProperty("user.home"), ".truesim", "polyhaven").toString()));
        return Files.isDirectory(folder.resolve("rock_face_03")) ? folder : null;
    }

    /** The texture sets: rock, grass, scree, snow, cliff (Poly Haven's as the showcase takes them, or generated). */
    static TerrainMaterial.Layer[] layers(PathAssets paths) {
        Path folder = polyHaven();
        if (folder == null) {
            TerrainMaterial.Layer[] alpine = TestTextures.alpine();
            return new TerrainMaterial.Layer[] {alpine[0], alpine[1], alpine[2], alpine[3], alpine[0]};
        }
        paths.mounts().add("ph", PathSource.folder(folder));
        paths.importers().add(RenderAssets.TEXTURE, "jpg", ImageImporter.INSTANCE);
        return new TerrainMaterial.Layer[] {scan(paths, "rock_face_03", 9), scan(paths, "sparse_grass", 2),
            scan(paths, "gray_rocks", 3), scan(paths, "snow_02", 4), scan(paths, "rock_face_03", 12)};
    }

    private static TerrainMaterial.Layer scan(PathAssets paths, String name, double size) {
        String base = "ph://" + name + "/" + name + "_";
        AssetRef<TextureData> albedo = paths.ref(RenderAssets.TEXTURE, base + "diff_2k.jpg");
        AssetRef<TextureData> normal = paths.ref(RenderAssets.TEXTURE, base + "nor_gl_2k.jpg#linear");
        AssetRef<TextureData> orm = paths.ref(RenderAssets.TEXTURE, base + "arm_2k.jpg#linear");
        return new TerrainMaterial.Layer(albedo, normal, orm, TerrainMaterial.snap(size));
    }

    /** The showcase's rules (rock from 28 degrees, sharp projections), its tints with Poly Haven's sets. */
    static TerrainParameters showcaseRules() {
        Color white = new Color(1, 1, 1, 1);
        TerrainParameters rules = TerrainParameters.DEFAULTS.withRockSlopeStart(28).withRockSlopeEnd(37)
            .withScreeSlopeStart(20).withScreeSlopeEnd(28).withTriplanarSharpness(12);
        if (polyHaven() != null) {
            return rules.withRockColor(new Color(0.82f, 0.86f, 0.95f, 1)).withGrassColor(new Color(0.82f, 1.15f, 1, 1))
                .withScreeColor(white).withSnowColor(white);
        }
        return rules.withRockColor(white).withGrassColor(white).withScreeColor(white).withSnowColor(white);
    }

    /** Before: the data's faces, the rules' rock (no cliff layer: it starts past 90 degrees). */
    static EngineSettings beforeSettings() {
        EngineSettings settings = TerrainFilms.settings();
        settings.set(TerrainSettings.CLIFFS, false);
        return settings;
    }

    static MaterialData beforeMaterial(TerrainMaterial.Layer[] l) {
        TerrainParameters rules = showcaseRules().withCliffSlopeStart(90).withCliffSlopeEnd(90);
        return TerrainMaterial.data(rules, l[0], l[1], l[2], l[3], TerrainMaterial.Cover.PLAIN, l[4]);
    }

    /** After: the relief on tiles of 32 m (0.5 m apart), the cliff layer. */
    static EngineSettings afterSettings() {
        EngineSettings settings = TerrainFilms.settings();
        if (System.getProperty("truesim.cliffs.debug", "").contains("norelief")) {
            settings.set(TerrainSettings.CLIFFS, false);
        }
        float tile = Float.parseFloat(System.getProperty("truesim.cliffs.tile", "32"));
        settings.set(TerrainSettings.TILE_SIZE, tile);
        settings.set(TerrainSettings.TILE_QUADS, Integer.parseInt(System.getProperty("truesim.cliffs.quads", "64")));
        settings.set(TerrainSettings.LEVELS, 9 + (int) Math.round(Math.log(128 / tile) / Math.log(2)));
        return settings;
    }

    static MaterialData afterMaterial(TerrainMaterial.Layer[] l) {
        TerrainParameters rules = showcaseRules();
        if (polyHaven() != null) {
            // the scan's beige greyed to the limestone's (the scan is about 0.5 bright: 0.8 of it is 0.4)
            rules = rules.withCliffColor(new Color(0.8f, 0.8f, 0.8f, 1)).withCliffSaturation(0.2f);
        }
        String debug = System.getProperty("truesim.cliffs.debug", "");
        if (debug.contains("plain")) { // the shapes alone: no textures, no variation, no detail normal
            rules = rules.withStreaks(0).withScars(0).withLichen(0).withBands(0).withWetFoot(0).withBeddingStrength(0)
                .withJointStrength(0).withMacro(0).withCliffColor(new Color(0.4f, 0.4f, 0.4f, 1));
            return TerrainMaterial.data(rules, l[0], l[1], l[2], l[3], TerrainMaterial.Cover.PLAIN,
                TerrainMaterial.Layer.PLAIN);
        }
        return TerrainMaterial.data(rules, l[0], l[1], l[2], l[3], TerrainMaterial.Cover.PLAIN, l[4]);
    }

    /** Draws {@code shot} at fractions {@code us} with {@code settings} and the material {@code after} or not. */
    static List<byte[]> render(TestGpu gpu, boolean after, Shot shot, double[] us) throws Exception {
        List<byte[]> frames = new ArrayList<>();
        EngineSettings settings = after ? afterSettings() : beforeSettings();
        TerrainFilms.Sky sky = TerrainFilms.Sky.of(gpu, WIDTH, HEIGHT, settings);
        try (TerrainRig rig = sky.rig()) {
            if (System.getProperty("truesim.cliffs.debug", "").contains("noshadow")) {
                rig.lights.shadow(0, com.truesim.scene.core.ShadowSpec.NONE);
            }
            PathAssets paths = new PathAssets(rig.assets);
            TerrainMaterial.Layer[] layers = layers(paths);
            MaterialData material = after ? afterMaterial(layers) : beforeMaterial(layers);
            Terrain terrain = rig.terrain(TerrainFilms.load(rig, paths), material.ref());
            rig.fov(Math.toRadians(50));
            place(rig, shot.at(us[0]));
            rig.settle(600);
            double ev = 12;
            // the exposure settles on the first view, then follows
            for (int k = 0; k < 12; k++) {
                rig.graph.settings().set(TerrainFilms.EV100, (float) ev);
                rig.frame();
                ev = exposure(rig.rgb(), ev, 1);
            }
            long start = System.nanoTime();
            for (int f = 0; f < us.length; f++) {
                place(rig, shot.at(us[f]));
                rig.graph.settings().set(TerrainFilms.EV100, (float) ev);
                TerrainFilms.step(rig, terrain);
                float[] color = rig.rgb();
                ev = exposure(color, ev, 0.05);
                byte[] rgb = new byte[3 * WIDTH * HEIGHT];
                for (int i = 0; i < WIDTH * HEIGHT; i++) {
                    rgb[3 * i] = (byte) TerrainFilms.tone(color[3 * i]);
                    rgb[3 * i + 1] = (byte) TerrainFilms.tone(color[3 * i + 1]);
                    rgb[3 * i + 2] = (byte) TerrainFilms.tone(color[3 * i + 2]);
                }
                frames.add(rgb);
                if (f % 60 == 0) {
                    long[] total = new long[1];
                    long[] perLevel = new long[terrain.grid().levels()];
                    int[] refinedPerLevel = new int[terrain.grid().levels()];
                    int base = (terrain.grid().quads() + 1) * (terrain.grid().quads() + 1);
                    terrain.forEachShown(key -> {
                        TileMeshes.Info info = terrain.meshes().info(key);
                        int v = info == null ? 0 : info.vertices();
                        total[0] += v;
                        perLevel[TerrainGrid.level(key)] += v;
                        refinedPerLevel[TerrainGrid.level(key)] += v > base ? 1 : 0;
                    });
                    System.out.println("vertices per level " + java.util.Arrays.toString(perLevel) + ", refined tiles "
                        + java.util.Arrays.toString(refinedPerLevel));
                    System.out.printf(Locale.ROOT, "%s %s %d/%d: %d tiles shown (%d at level 0), %d vertices, %.1f s%n",
                        shot.name(), after ? "after" : "before", f, us.length, terrain.shownTiles(),
                        terrain.shownTiles(0), total[0], (System.nanoTime() - start) / 1e9);
                }
            }
        }
        return frames;
    }

    private static double exposure(float[] color, double ev, double rate) {
        double logSum = 0;
        double units = 1.2 * Math.pow(2, ev);
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            double luminance = 0.2126 * color[3 * i] + 0.7152 * color[3 * i + 1] + 0.0722 * color[3 * i + 2];
            logSum += Math.log(1e-4 + luminance * units);
        }
        double target = AtmosphereExposure.ev100(Math.exp(logSum / (WIDTH * HEIGHT))) + 1.5;
        return ev + (target - ev) * rate;
    }

    private static void place(TerrainRig rig, double[] p) {
        rig.look(p[0], p[1], p[2], p[3] - p[0], p[4] - p[1], p[5] - p[2]);
    }

    /** The label over a frame. */
    private static void label(byte[] rgb, String text, String detail) {
        BufferedImage image = image(rgb);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0, 0, 0, 130));
        g.fillRect(0, 0, WIDTH, 36);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        g.drawString(text, 14, 25);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.drawString(detail, 260, 25);
        g.dispose();
        int[] argb = image.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH);
        for (int i = 0; i < argb.length; i++) {
            rgb[3 * i] = (byte) (argb[i] >> 16);
            rgb[3 * i + 1] = (byte) (argb[i] >> 8);
            rgb[3 * i + 2] = (byte) argb[i];
        }
    }

    private static BufferedImage image(byte[] rgb) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] argb = new int[WIDTH * HEIGHT];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = (rgb[3 * i] & 0xff) << 16 | (rgb[3 * i + 1] & 0xff) << 8 | (rgb[3 * i + 2] & 0xff);
        }
        image.setRGB(0, 0, WIDTH, HEIGHT, argb, 0, WIDTH);
        return image;
    }

    private static Path dir() throws Exception {
        Path dir = Path.of(System.getProperty("truesim.screenshots", "build/films"));
        Files.createDirectories(dir);
        return dir;
    }

    /** Stills of each shot's start, middle and end, before and after ({@code cliffs-<shot>-<when>-<u>.png}). */
    @Test
    void stills() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path dir = dir();
        String only = System.getProperty("truesim.cliffs.shot", "");
        String when = System.getProperty("truesim.cliffs.when", "before,after");
        try (TestGpu gpu = TestGpu.open()) {
            for (Shot shot : SHOTS) {
                if (!only.isEmpty() && !only.equals(shot.name())) {
                    continue;
                }
                for (boolean after : new boolean[] {false, true}) {
                    if (!when.contains(after ? "after" : "before")) {
                        continue;
                    }
                    double[] us = {0, 0.5, 1};
                    List<byte[]> frames = render(gpu, after, shot, us);
                    for (int k = 0; k < us.length; k++) {
                        ImageIO.write(image(frames.get(k)), "png", dir.resolve(String.format(Locale.ROOT,
                            "cliffs-%s-%s-%.1f.png", shot.name(), after ? "after" : "before", us[k])).toFile());
                    }
                }
            }
        }
    }

    /** {@code cliffs.mp4}: each shot before, then after. */
    @Test
    void film() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path file = dir().resolve("cliffs.mp4");
        Process ffmpeg = TerrainFilms.ffmpeg(file, WIDTH, HEIGHT, 23);
        try (TestGpu gpu = TestGpu.open(); OutputStream out = ffmpeg.getOutputStream()) {
            for (Shot shot : SHOTS) {
                int count = (int) Math.round(shot.seconds() * FPS);
                double[] us = new double[count];
                for (int f = 0; f < count; f++) {
                    us[f] = f / (count - 1.0);
                }
                for (boolean after : new boolean[] {false, true}) {
                    List<byte[]> frames = render(gpu, after, shot, us);
                    String title = after ? "after" : "before";
                    String detail = after
                        ? "cliff relief (ledges, joints, buttresses) on 0.5 m tiles; limestone layer, bedding, streaks"
                        : "the 2 m data's faces, rock by the rules";
                    String where = shot == FLOOR_VIEW ? "Lauterbrunnen's walls from the valley floor, morning sun"
                        : "along the Staubbach wall, morning sun";
                    for (byte[] frame : frames) {
                        label(frame, title, where + " - " + detail);
                        out.write(frame);
                    }
                }
            }
        }
        assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
    }
}
