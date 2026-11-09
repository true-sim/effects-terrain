package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.math.color.Color;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.render.SkyCapture;
import com.truesim.effects.terrain.data.TerrainAssets;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.render.MaterialData;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The films ({@code TRUESIM_SCREENSHOTS=true}, {@code -Pscreenshots=<directory>}), of the real Lauterbrunnen site
 * loaded through the asset manager from the terrain cache, encoded with ffmpeg (H.264, 1280 x 720, 30 fps):
 *
 * <ul>
 *   <li>{@code terrain-flight.mp4}: from the valley floor at the village, up the valley under its cliffs, over the
 *       ridge to the east and out to the horizon, in the morning sun, under effects-atmosphere's sky (its aerial
 *       perspective, sky light and sun) with cascaded shadows;</li>
 *   <li>{@code terrain-levels.mp4}: the same flight's tiles coloured by level beside a map of the shown tiles round
 *       the camera, as they stream.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class TerrainFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FPS = 30;
    static final Setting<Float> EV100 = AtmosphereSky.ATMOSPHERE.setting("ev100");
    /** Stops darker than the automatic exposure: snow, scree and the sky fill these views, which it would make grey. */
    private static final double COMPENSATION = 1.3;

    /** The colours of the levels, finest first. */
    static final Color[] LEVELS = {new Color(0.85, 0.15, 0.15, 1), new Color(0.95, 0.55, 0.1, 1),
        new Color(0.95, 0.9, 0.15, 1), new Color(0.35, 0.8, 0.2, 1), new Color(0.15, 0.75, 0.75, 1),
        new Color(0.2, 0.4, 0.95, 1), new Color(0.55, 0.25, 0.9, 1), new Color(0.9, 0.3, 0.75, 1),
        new Color(0.6, 0.6, 0.6, 1)};

    /** A key of the flight: when, where the camera is and what it looks at (local frame, metres). */
    record Key(double t, double x, double y, double z, double lx, double ly, double lz) {
    }

    /**
     * The flight: the village (the origin) on the valley floor, up the valley south towards the Jungfrau, climbing
     * over the eastern ridge (Männlichen), then out to the north-east and the horizon. Heights count from the valley
     * floor at the village (800 m).
     */
    static final List<Key> FLIGHT = List.of(
        new Key(0, -120, 25, 500, 150, 120, 3500),
        new Key(7, -60, 60, 1700, 600, 400, 5200),
        new Key(12, 300, 420, 2900, 4100, 2600, 6300),
        new Key(17, 1700, 1500, 2600, 6000, 1500, -1500),
        new Key(22, 3600, 2500, 700, 9000, 1200, -9000),
        new Key(28, 5200, 3600, -2400, 30000, 600, -40000));

    private static final double DURATION = 28;

    /** Catmull-Rom between the keys at time {@code t}: {position, look-at}. */
    static double[] at(double t) {
        int i = 0;
        while (i < FLIGHT.size() - 2 && FLIGHT.get(i + 1).t() <= t) {
            i++;
        }
        Key p1 = FLIGHT.get(i);
        Key p2 = FLIGHT.get(i + 1);
        Key p0 = FLIGHT.get(Math.max(0, i - 1));
        Key p3 = FLIGHT.get(Math.min(FLIGHT.size() - 1, i + 2));
        double u = Math.clamp((t - p1.t()) / (p2.t() - p1.t()), 0, 1);
        double[] out = new double[6];
        double[][] values = {
            {p0.x(), p0.y(), p0.z(), p0.lx(), p0.ly(), p0.lz()}, {p1.x(), p1.y(), p1.z(), p1.lx(), p1.ly(), p1.lz()},
            {p2.x(), p2.y(), p2.z(), p2.lx(), p2.ly(), p2.lz()}, {p3.x(), p3.y(), p3.z(), p3.lx(), p3.ly(), p3.lz()}};
        for (int k = 0; k < 6; k++) {
            double a = values[0][k];
            double b = values[1][k];
            double c = values[2][k];
            double d = values[3][k];
            out[k] = 0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u * u
                + (-a + 3 * b - 3 * c + d) * u * u * u);
        }
        return out;
    }

    /** The site, through the asset manager: the terrain cache mounted, the .terrain importer, loaded in the background. */
    static TerrainSite load(TerrainRig rig) throws InterruptedException {
        return load(rig, new PathAssets(rig.assets));
    }

    /** {@link #load(TerrainRig)} through {@code paths} (the rig's one set of paths: other mounts beside it). */
    static TerrainSite load(TerrainRig rig, PathAssets paths) throws InterruptedException {
        TerrainAssets.register(paths);
        TerrainAssets.mount(paths, TestSites.cache());
        AssetRef<TerrainSite> ref = TerrainAssets.site(paths, "lauterbrunnen");
        rig.assets.acquire(ref);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (rig.assets.get(ref) == null) {
            assertThat(rig.assets.failure(ref)).as("the site loading").isNull();
            assertThat(System.nanoTime()).as("the site loading").isLessThan(deadline);
            Thread.sleep(5);
            rig.tasks.drainCompleted();
        }
        return rig.assets.get(ref);
    }

    static MaterialData alpine() {
        TerrainMaterial.Layer[] layers = TestTextures.alpine();
        Color white = new Color(1, 1, 1, 1);
        TerrainParameters rules = TerrainParameters.DEFAULTS.withRockColor(white).withGrassColor(white)
            .withScreeColor(white).withSnowColor(white);
        return TerrainMaterial.data(rules, layers[0], layers[1], layers[2], layers[3]);
    }

    static EngineSettings settings() {
        EngineSettings settings = new EngineSettings();
        settings.set(TerrainSettings.BUILD_BUDGET, 64);
        return settings;
    }

    /** ACES (Narkowicz's fit) and sRGB, of scene colour {@code c}. */
    static int tone(double c) {
        double x = Math.max(0, c);
        double mapped = Math.clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0, 1);
        double s = mapped <= 0.0031308 ? 12.92 * mapped : 1.055 * Math.pow(mapped, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    /** ffmpeg reading raw RGB frames of {@code width} x {@code height} from its input, into {@code file}. */
    static Process ffmpeg(Path file, int width, int height, int crf) throws IOException {
        return new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
            width + "x" + height, "-r", String.valueOf((int) FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow",
            "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }

    /** A rig drawing the site under the atmosphere's sky with the sun's cascaded shadows, half-float colour. */
    record Sky(TerrainRig rig, Atmosphere atmosphere) {
        static Sky of(TestGpu gpu, int width, int height) {
            return of(gpu, width, height, settings());
        }

        static Sky of(TestGpu gpu, int width, int height, EngineSettings settings) {
            TerrainRig rig = new TerrainRig(gpu, width, height, true, true, settings);
            Atmosphere atmosphere = rig.closing(new Atmosphere(rig.device, rig.slots, rig.graph.settings()));
            Environment environment = Environment.uniform("atmosphere", 0, 0, 0);
            SkyCapture capture = rig.closing(new SkyCapture(atmosphere, environment, rig.textures));
            rig.lights.directional(1, 0, -1, 0, 0, 0, 0);
            rig.lights.setCount(2);
            rig.lights.shadow(0, ShadowSpec.cascades(4, 2048, 20_000));
            Shadows shadows = rig.closing(new Shadows(rig.geometry, rig.lights));
            // morning: the sun in the east-south-east, 24 degrees up (towards it: +x east, +y up, -z north)
            double azimuth = Math.toRadians(118);
            double elevation = Math.toRadians(24);
            atmosphere.fixedSun(Math.sin(azimuth) * Math.cos(elevation), Math.sin(elevation),
                -Math.cos(azimuth) * Math.cos(elevation), 100_000);
            rig.port.add(new EnvironmentLighting(environment, rig.view)).add(capture).add(shadows.view(rig.view))
                .add(new AtmosphereSky(atmosphere, rig.view)).add(new AerialPerspective(atmosphere, rig.view));
            rig.output();
            rig.beforeScene = r -> {
                atmosphere.update(null, r.camera.position(0), r.camera.position(1), r.camera.position(2));
                atmosphere.light(r.lights, 0, 1);
            };
            return new Sky(rig, atmosphere);
        }
    }

    /** The valley floor's altitude at the village, metres: the keys' heights count from it. */
    private static final double FLOOR = 800;

    /** Places the camera at time {@code t} of the flight, no lower than 25 m above the ground. */
    static void place(TerrainRig rig, Terrain terrain, double t) {
        double[] p = at(t);
        double ground = terrain.heightAt(p[0], p[2]);
        double y = Math.max(FLOOR + p[1], ground + 25);
        rig.look(p[0], y, p[2], p[3] - p[0], FLOOR + p[4] - y, p[5] - p[2]);
    }

    /** A frame of the flight: extra frames while tiles are still on their way (at most a few), then the one filmed. */
    static void step(TerrainRig rig, Terrain terrain) {
        for (int extra = 0; extra < 3 && terrain.pendingTiles() > 0; extra++) {
            rig.frame();
        }
        rig.frame();
    }

    @Test
    void flight() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path dir = Path.of(System.getProperty("truesim.screenshots", "build/films"));
        Files.createDirectories(dir);
        try (TestGpu gpu = TestGpu.open()) {
            Sky sky = Sky.of(gpu, WIDTH, HEIGHT);
            try (TerrainRig rig = sky.rig()) {
                Terrain terrain = rig.terrain(load(rig), alpine().ref());
                rig.fov(Math.toRadians(55));
                place(rig, terrain, 0);
                rig.settle(400);
                double ev = 12;
                Process ffmpeg = ffmpeg(dir.resolve("terrain-flight.mp4"), WIDTH, HEIGHT, 20);
                byte[] rgb = new byte[3 * WIDTH * HEIGHT];
                long start = System.nanoTime();
                int frames = (int) (DURATION * FPS);
                try (OutputStream out = ffmpeg.getOutputStream()) {
                    for (int f = 0; f <= frames; f++) {
                        place(rig, terrain, f / FPS);
                        rig.graph.settings().set(EV100, (float) ev);
                        step(rig, terrain);
                        float[] color = rig.rgb();
                        double logSum = 0;
                        double units = 1.2 * Math.pow(2, ev); // scene colour to cd/m^2
                        for (int i = 0; i < WIDTH * HEIGHT; i++) {
                            double luminance = 0.2126 * color[3 * i] + 0.7152 * color[3 * i + 1]
                                + 0.0722 * color[3 * i + 2];
                            logSum += Math.log(1e-4 + luminance * units);
                            rgb[3 * i] = (byte) tone(color[3 * i]);
                            rgb[3 * i + 1] = (byte) tone(color[3 * i + 1]);
                            rgb[3 * i + 2] = (byte) tone(color[3 * i + 2]);
                        }
                        double target = AtmosphereExposure.ev100(Math.exp(logSum / (WIDTH * HEIGHT))) + COMPENSATION;
                        ev = f == 0 ? target : AtmosphereExposure.adapt(ev, target, 1 / FPS, 0.6);
                        out.write(rgb);
                        if (f % 60 == 0) {
                            System.out.printf(Locale.ROOT, "flight %.1f s: %d tiles shown (%d at level 0), %d held,"
                                + " EV %.2f, %.1f s%n", f / FPS, terrain.shownTiles(), terrain.shownTiles(0),
                                terrain.heldTiles(), ev, (System.nanoTime() - start) / 1e9);
                        }
                    }
                }
                assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
            }
        }
    }

    @Test
    void levels() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path dir = Path.of(System.getProperty("truesim.screenshots", "build/films"));
        Files.createDirectories(dir);
        int half = WIDTH / 2;
        try (TestGpu gpu = TestGpu.open()) {
            Sky sky = Sky.of(gpu, half, HEIGHT);
            try (TerrainRig rig = sky.rig()) {
                Terrain terrain = rig.terrain(load(rig), alpine().ref());
                List<AssetRef<MaterialData>> levels = new ArrayList<>();
                for (Color c : LEVELS) {
                    TerrainParameters plain = TerrainParameters.DEFAULTS.withRockColor(c).withGrassColor(c)
                        .withScreeColor(c).withSnowColor(c).withMacro(0).withJitter(0);
                    levels.add(TerrainMaterial.data(plain, TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN,
                        TerrainMaterial.Layer.PLAIN, TerrainMaterial.Layer.PLAIN).ref());
                }
                terrain.levelMaterials(levels);
                rig.fov(Math.toRadians(60));
                place(rig, terrain, 0);
                rig.settle(400);
                double ev = 12;
                Process ffmpeg = ffmpeg(dir.resolve("terrain-levels.mp4"), WIDTH, HEIGHT, 22);
                BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
                byte[] rgb = new byte[3 * WIDTH * HEIGHT];
                int frames = (int) (DURATION * FPS);
                try (OutputStream out = ffmpeg.getOutputStream()) {
                    for (int f = 0; f <= frames; f++) {
                        place(rig, terrain, f / FPS);
                        rig.graph.settings().set(EV100, (float) ev);
                        step(rig, terrain);
                        float[] color = rig.rgb();
                        double logSum = 0;
                        double units = 1.2 * Math.pow(2, ev);
                        for (int y = 0; y < HEIGHT; y++) {
                            for (int x = 0; x < half; x++) {
                                int i = y * half + x;
                                double luminance = 0.2126 * color[3 * i] + 0.7152 * color[3 * i + 1]
                                    + 0.0722 * color[3 * i + 2];
                                logSum += Math.log(1e-4 + luminance * units);
                                int o = 3 * (y * WIDTH + x);
                                rgb[o] = (byte) tone(color[3 * i]);
                                rgb[o + 1] = (byte) tone(color[3 * i + 1]);
                                rgb[o + 2] = (byte) tone(color[3 * i + 2]);
                            }
                        }
                        double target = AtmosphereExposure.ev100(Math.exp(logSum / (half * HEIGHT))) + COMPENSATION;
                        ev = f == 0 ? target : AtmosphereExposure.adapt(ev, target, 1 / FPS, 0.6);
                        map(frame, rig, terrain, half, f / FPS);
                        for (int y = 0; y < HEIGHT; y++) {
                            for (int x = half; x < WIDTH; x++) {
                                int p = frame.getRGB(x - half, y);
                                int o = 3 * (y * WIDTH + x);
                                rgb[o] = (byte) (p >> 16);
                                rgb[o + 1] = (byte) (p >> 8);
                                rgb[o + 2] = (byte) p;
                            }
                        }
                        out.write(rgb);
                    }
                }
                assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
            }
        }
    }

    /**
     * {@code terrain-switch-compare.mp4}: two moments of the flight where tiles switched levels most (the first film's
     * worst switches, at 8.7 and 12.7 s of its quicker start), slowed eight times, as the terrain was (left: switching at once, each level's layers
     * following its own slope and curvature) and as it is (right: tiles morphing between levels, layers at
     * {@code terrain.layerScale}), at one exposure.
     */
    @Test
    void switchComparison() throws Exception {
        assumeThat(TestSites.lauterbrunnen()).as("the Lauterbrunnen site imported into the terrain cache").isNotNull();
        Path dir = Path.of(System.getProperty("truesim.screenshots", "build/films"));
        Files.createDirectories(dir);
        int half = WIDTH / 2;
        double[][] windows = {{10.2, 10.9}, {14.3, 14.9}}; // where the flight switched most, the first film's 8.7 and 12.7 s
        int slow = 8;
        EngineSettings before = settings();
        before.set(TerrainSettings.MORPH_SECONDS, 0f);
        before.set(TerrainSettings.LAYER_SCALE, 1f);
        List<byte[]> left = new ArrayList<>();
        List<byte[]> right = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        for (double[] window : windows) {
            left.addAll(segment(before, half, window[0], window[1], slow));
            right.addAll(segment(settings(), half, window[0], window[1], slow));
            for (int f = 0; f < Math.round((window[1] - window[0]) * FPS * slow); f++) {
                times.add(window[0] + f / (FPS * slow));
            }
        }
        Process ffmpeg = ffmpeg(dir.resolve("terrain-switch-compare.mp4"), WIDTH, HEIGHT, 20);
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        byte[] rgb = new byte[3 * WIDTH * HEIGHT];
        try (OutputStream out = ffmpeg.getOutputStream()) {
            for (int f = 0; f < Math.min(left.size(), right.size()); f++) {
                for (int y = 0; y < HEIGHT; y++) {
                    System.arraycopy(left.get(f), 3 * y * half, rgb, 3 * y * WIDTH, 3 * half);
                    System.arraycopy(right.get(f), 3 * y * half, rgb, 3 * (y * WIDTH + half), 3 * half);
                }
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int o = 3 * (y * WIDTH + x);
                        frame.setRGB(x, y, (rgb[o] & 0xff) << 16 | (rgb[o + 1] & 0xff) << 8 | (rgb[o + 2] & 0xff));
                    }
                }
                Graphics2D g = frame.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                g.setColor(new java.awt.Color(0, 0, 0, 140));
                g.fillRect(0, 0, WIDTH, 34);
                g.setColor(java.awt.Color.WHITE);
                g.drawString("before: switch at once, layers per level", 14, 23);
                g.drawString("after: morphing levels, layers at one scale", half + 14, 23);
                g.drawString(String.format(Locale.ROOT, "%.3f s, slowed %dx", times.get(f), slow), 14, HEIGHT - 14);
                g.fillRect(half - 1, 0, 2, HEIGHT);
                g.dispose();
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        int c = frame.getRGB(x, y);
                        int o = 3 * (y * WIDTH + x);
                        rgb[o] = (byte) (c >> 16);
                        rgb[o + 1] = (byte) (c >> 8);
                        rgb[o + 2] = (byte) c;
                    }
                }
                out.write(rgb);
            }
        }
        assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
    }

    /** The flight from {@code from} to {@code to} seconds, slowed {@code slow} times, as RGB frames {@code width} wide. */
    private static List<byte[]> segment(EngineSettings settings, int width, double from, double to, int slow)
        throws Exception {
        List<byte[]> frames = new ArrayList<>();
        try (TestGpu gpu = TestGpu.open()) {
            Sky sky = Sky.of(gpu, width, HEIGHT, settings);
            try (TerrainRig rig = sky.rig()) {
                Terrain terrain = rig.terrain(load(rig), alpine().ref());
                rig.fov(Math.toRadians(55));
                rig.frameSeconds = 1 / (FPS * slow);
                rig.graph.settings().set(EV100, 11.2f);
                place(rig, terrain, from - 1);
                rig.settle(400);
                for (double t = from - 1; t < from; t += 1 / FPS) { // the flight's last second at full speed first
                    place(rig, terrain, t);
                    rig.frameSeconds = 1 / FPS;
                    step(rig, terrain);
                }
                rig.frameSeconds = 1 / (FPS * slow);
                int count = (int) Math.round((to - from) * FPS * slow);
                for (int f = 0; f < count; f++) {
                    place(rig, terrain, from + f / (FPS * slow));
                    rig.frame();
                    float[] color = rig.rgb();
                    byte[] rgb = new byte[3 * width * HEIGHT];
                    for (int i = 0; i < width * HEIGHT; i++) {
                        rgb[3 * i] = (byte) tone(color[3 * i]);
                        rgb[3 * i + 1] = (byte) tone(color[3 * i + 1]);
                        rgb[3 * i + 2] = (byte) tone(color[3 * i + 2]);
                    }
                    frames.add(rgb);
                }
            }
        }
        return frames;
    }

    /**
     * The map: the shown tiles round the camera from above, coloured by level, the tiles on their way outlined, the
     * camera and its view, the counts.
     */
    private static void map(BufferedImage image, TerrainRig rig, Terrain terrain, int width, double t) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(18, 18, 22));
        g.fillRect(0, 0, width, HEIGHT);
        double cx = rig.camera.position(0);
        double cz = rig.camera.position(2);
        double radius = 14_000; // metres from the camera to the map's edge, across
        double scale = width / (2 * radius);
        double oy = HEIGHT / 2.0;
        TerrainGrid grid = terrain.grid();
        int[] counts = new int[grid.levels()];
        terrain.forEachShown(key -> {
            int level = TerrainGrid.level(key);
            counts[level]++;
            double size = grid.size(level);
            int x0 = (int) Math.floor((TerrainGrid.x(key) * size - cx) * scale + width / 2.0);
            int y0 = (int) Math.floor((TerrainGrid.z(key) * size - cz) * scale + oy);
            int s = (int) Math.ceil(size * scale);
            Color c = LEVELS[Math.min(level, LEVELS.length - 1)];
            g.setColor(new java.awt.Color((float) c.r(), (float) c.g(), (float) c.b()));
            g.fillRect(x0, y0, s, s);
            g.setColor(new java.awt.Color(10, 10, 10));
            g.drawRect(x0, y0, s, s);
        });
        // The camera and its view
        double[] p = at(t);
        double heading = Math.atan2(p[5] - cz, p[3] - cx);
        int px = width / 2;
        int py = (int) oy;
        double half = rig.fovY() * width / HEIGHT / 2;
        g.setColor(java.awt.Color.WHITE);
        g.setStroke(new BasicStroke(2));
        for (double a : new double[] {heading - half, heading + half}) {
            g.drawLine(px, py, px + (int) (Math.cos(a) * 180), py + (int) (Math.sin(a) * 180));
        }
        g.fillOval(px - 5, py - 5, 10, 10);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString(String.format(Locale.ROOT, "tiles shown %d, held %d, wanted %d", terrain.shownTiles(),
            terrain.heldTiles(), terrain.wantedTiles()), 14, 24);
        g.drawString(String.format(Locale.ROOT, "camera %.0f m, %.1f s", rig.camera.position(1), t), 14, 44);
        g.drawString("north up, 28 km across", 14, HEIGHT - 16);
        for (int level = 0; level < grid.levels(); level++) {
            Color c = LEVELS[Math.min(level, LEVELS.length - 1)];
            g.setColor(new java.awt.Color((float) c.r(), (float) c.g(), (float) c.b()));
            g.fillRect(width - 150, 14 + 20 * level, 14, 14);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(String.format(Locale.ROOT, "%5.0f m: %d", grid.size(level), counts[level]), width - 128,
                26 + 20 * level);
        }
        g.dispose();
    }
}
