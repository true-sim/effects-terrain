package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.material.TerrainType;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.world.World;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The whole path a game takes, for the GPU tests and films: an asset cache on a task scheduler, the scene resources,
 * a scene with its world streaming, the terrain in it, the sync onto a GPU scene, a meshlet renderer drawing it
 * through a frame graph (forward or visibility buffer, lit by a sun), and the scene's color of every frame read back.
 * The background clears to a color nothing on the terrain has, so a hole or crack shows as that color.
 */
final class TerrainRig implements AutoCloseable {



    /** The clear color: pure magenta, which no terrain layer is. */
    static final float[] CLEAR = {1, 0, 1};

    final TestGpu gpu;
    final VulkanDevice device;
    final int width;
    final int height;
    final boolean hdr;
    final FrameSlots slots = new FrameSlots(2);
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache assets;
    final SceneResources resources;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final World world;
    final GpuScene gpuScene;
    final SceneSync sync;
    final MeshletRenderer renderer;
    final SceneGeometry geometry;
    final Lights lights;
    final FrameGraph graph;
    final EngineSettings settings;
    final Camera camera = new Camera();
    final SceneView view;
    final ViewPort port;
    private final GpuBuffer[] readbacks;
    private final TestTarget target;
    private final List<AutoCloseable> closing = new ArrayList<>();
    Terrain terrain;
    /** Called each frame after the terrain's update, before the scene's (the films move things). */
    Consumer<TerrainRig> beforeScene = rig -> { };
    private double fovY = Math.toRadians(60);
    private int lastSlot;

    /** A rig of {@code width} x {@code height}, RGBA8 or (with {@code hdr}) half-float color. */
    TerrainRig(TestGpu gpu, int width, int height, boolean visibilityBuffer, boolean hdr) {
        this(gpu, width, height, visibilityBuffer, hdr, new EngineSettings());
    }

    TerrainRig(TestGpu gpu, int width, int height, boolean visibilityBuffer, boolean hdr, EngineSettings settings) {
        this(gpu, width, height, visibilityBuffer, hdr, settings, true);
    }

    TerrainRig(TestGpu gpu, int width, int height, boolean visibilityBuffer, boolean hdr, EngineSettings settings,
        boolean occlusion) {
        this.gpu = gpu;
        this.device = gpu.device();
        this.width = width;
        this.height = height;
        this.hdr = hdr;
        this.settings = settings;
        int color = hdr ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 20, 1 << 14, 256), slots);
        materials = new Materials(device, 64, slots);
        materials.register(TerrainType.TYPE);
        textures = new Textures(device, slots);
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
        world = new World(scene, tasks);
        gpuScene = new GpuScene(device, pool, 4096, slots);
        sync = new SceneSync(resources, scene, gpuScene);
        renderer = new MeshletRenderer(device, gpuScene, color, VK_FORMAT_D32_SFLOAT, slots);
        geometry = SceneGeometry.meshlets(renderer);
        lights = new Lights(device, 4, 8, slots);
        lights.directional(0, -0.5f, -0.6f, -0.3f, 3, 3, 2.8f);
        lights.setCount(1);
        graph = new FrameGraph(device, slots);
        graph.settings(settings);
        port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 16, 1 << 23).withOcclusion(occlusion))
            .set(1, MeshletRenderer.MODE_LIT);
        Technique shading = visibilityBuffer
            ? new VisibilityBufferShading(geometry, view, color, VK_FORMAT_D32_SFLOAT).clearColor(CLEAR[0], CLEAR[1],
                CLEAR[2])
            : new ClusteredForwardShading(geometry, view, color, VK_FORMAT_D32_SFLOAT).clearColor(CLEAR[0], CLEAR[1],
                CLEAR[2]);
        port.add(shading).add(new ClusteredLights(lights, view));
        int bytes = hdr ? 8 : 4;
        readbacks = new GpuBuffer[] {GpuBuffer.hostVisible(device, (long) bytes * width * height,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT), GpuBuffer.hostVisible(device, (long) bytes * width * height,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT)};
        target = new TestTarget(device, width, height, hdr ? com.truesim.engine.vulkan.Format.R16G16B16A16_SFLOAT
            : com.truesim.engine.vulkan.Format.R8G8B8A8_UNORM);
        camera.position(0, 2000, 0).set(0, -1, 0, 0, 0, -1, fovY, width, height, 0.5);
    }

    /** Adds the readback and the output, after every other technique was added to the port. */
    TerrainRig output() {
        port.add(new Readback(this)).output(Keys.SCENE_COLOR, target.output());
        return this;
    }

    /** The terrain of {@code site} with {@code material}, as the rig's settings say. */
    Terrain terrain(TerrainSite site, AssetRef<MaterialData> material) {
        terrain = new Terrain(world, resources, site, material, settings);
        return terrain;
    }

    <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    /** Points the camera from ({@code x}, {@code y}, {@code z}) along ({@code dx}, {@code dy}, {@code dz}). */
    TerrainRig look(double x, double y, double z, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double fx = dx / length;
        double fy = dy / length;
        double fz = dz / length;
        // up: world up made perpendicular, or -z looking straight down
        double ux = -fy * fx;
        double uy = 1 - fy * fy;
        double uz = -fy * fz;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1e-6) {
            ux = 0;
            uy = 0;
            uz = -1;
            ul = 1;
        }
        camera.position(x, y, z).set(fx, fy, fz, ux / ul, uy / ul, uz / ul, fovY, width, height, 0.5);
        return this;
    }

    TerrainRig fov(double radians) {
        fovY = radians;
        return this;
    }

    double fovY() {
        return fovY;
    }

    /** One frame: loads arrive, the terrain follows the camera, the scene syncs, the graph draws. */
    void frame() {
        int slot = graph.beginFrame();
        slots.begin(slot);
        tasks.drainCompleted();
        resources.beginFrame();
        if (terrain != null) {
            terrain.follow(camera.position(0), camera.position(1), camera.position(2), fovY, height);
            world.update();
            terrain.update();
        } else {
            world.update();
        }
        beforeScene.accept(this);
        scene.update();
        sync.update();
        view.set(1, MeshletRenderer.MODE_LIT);
        graph.submit(0, 0);
        slots.end();
        lastSlot = slot;
    }

    /** Frames until the terrain has shown everything the world wants for this view and nothing is loading. */
    int settle(int maxFrames) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L; // frames are fast: the loads take the time
        for (int f = 0; f < maxFrames || System.nanoTime() < deadline; f++) {
            frame();
            if (f > 2 && terrain.source() != null && world.isReady(terrain.source()) && assets.loadingCount() == 0
                && resources.loaded() && terrain.heldTiles() > 0 && allWantedShow()) {
                frame();
                frame();
                device.waitIdle();
                return f;
            }
            Thread.sleep(1);
        }
        device.waitIdle();
        throw new AssertionError("The terrain did not settle in two minutes: " + terrain.wantedTiles()
            + " wanted, " + terrain.shownTiles() + " shown, " + terrain.heldTiles() + " held, " + assets.loadingCount()
            + " loading");
    }

    /** Whether every tile the world wants is on the GPU and the world loads nothing. */
    boolean allWantedShow() {
        return terrain.pendingTiles() == 0 && world.loadingCount() == 0;
    }

    /** The last frame's pixels as 0xAARRGGBB (RGBA8 rigs). */
    int[] argb() {
        device.waitIdle();
        MemorySegment memory = readbacks[lastSlot].memory();
        int[] out = new int[width * height];
        for (int i = 0; i < out.length; i++) {
            int rgba = memory.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            int r = rgba & 0xff;
            int g = (rgba >>> 8) & 0xff;
            int b = (rgba >>> 16) & 0xff;
            out[i] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return out;
    }

    /** The last frame's color, rgb per pixel (half-float rigs: scene units). */
    float[] rgb() {
        device.waitIdle();
        MemorySegment memory = readbacks[lastSlot].memory();
        float[] out = new float[3 * width * height];
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                out[3 * i + c] = Float.float16ToFloat(memory.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8L * i + 2L * c));
            }
        }
        return out;
    }

    /**
     * Writes {@code argb} as {@code <name>.png} into the screenshots directory ({@code -Pscreenshots=<dir>}, the system
     * property {@code truesim.screenshots}); nothing without one.
     */
    void save(String name, int[] argb) {
        String dir = System.getProperty("truesim.screenshots");
        if (dir == null) {
            return;
        }
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of(dir, name + ".png").toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** How many pixels of the last frame show the clear color (RGBA8 rigs). */
    int background(int[] argb) {
        int count = 0;
        for (int p : argb) {
            if ((p & 0xffffff) == 0xff00ff) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        device.waitIdle();
        for (int i = closing.size() - 1; i >= 0; i--) {
            try {
                closing.get(i).close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        if (terrain != null) {
            terrain.close();
            scene.update();
            sync.update();
        }
        graph.close();
        target.close();
        for (GpuBuffer b : readbacks) {
            b.close();
        }
        renderer.close();
        sync.close();
        world.close();
        resources.close();
        gpuScene.close();
        lights.close();
        assets.close();
        tasks.close();
        textures.close();
        materials.close();
        pool.close();
        assertThat(gpu.validationErrors()).as("validation errors").isZero();
    }

    /** Copies the scene's color into the frame slot's readback after everything drawn over it. */
    private record Readback(TerrainRig rig) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("readback").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(rig.width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(rig.width, rig.height, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        rig.readbacks[rig.graph.frameSlots().current()].handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
