package com.truesim.effects.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.Push;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The material's layer rules: their CPU twin ({@link TerrainLayers}, what placement reads) gives what the shader's
 * {@code terrainLayers} gives, point for point over altitudes, slopes and curvatures (run on the GPU by a test kernel);
 * and the rules say what they should: meadows low and gentle, rock on cliffs and ridges, scree in hollows and high up,
 * snow above the snow line where it is not too steep.
 */
class TerrainLayersTest {

    private static final TerrainParameters RULES = TerrainParameters.DEFAULTS;

    private static double[] cpu(double altitude, double slope, double curvature) {
        double[] w = new double[4];
        TerrainLayers.weights(RULES, altitude, slope, curvature, w);
        return w;
    }

    @Test
    void theRulesPlaceEachLayerWhereItBelongs() {
        assertThat(cpu(900, 5, 0)[TerrainLayers.GRASS]).as("a valley meadow").isEqualTo(1, within(1e-9));
        assertThat(cpu(1500, 60, 0)[TerrainLayers.ROCK]).as("a cliff").isEqualTo(1, within(1e-9));
        assertThat(cpu(1500, 36, -1)[TerrainLayers.ROCK]).as("a convex ridge, moderately steep")
            .isGreaterThan(cpu(1500, 36, 0)[TerrainLayers.ROCK]);
        assertThat(cpu(1500, 18, 1)[TerrainLayers.SCREE]).as("a hollow").isGreaterThan(0.3);
        assertThat(cpu(2500, 10, 0)[TerrainLayers.SCREE]).as("above the grass line").isEqualTo(1, within(1e-9));
        assertThat(cpu(3500, 10, 0)[TerrainLayers.SNOW]).as("above the snow line").isEqualTo(1, within(1e-9));
        assertThat(cpu(3500, 70, 0)[TerrainLayers.SNOW]).as("a cliff above it").isZero();
        assertThat(cpu(2700, 10, 1)[TerrainLayers.SNOW]).as("a hollow below the line")
            .isGreaterThan(cpu(2700, 10, 0)[TerrainLayers.SNOW]);
        for (double a = 400; a < 4500; a += 137) {
            for (double s = 0; s <= 90; s += 7) {
                double[] w = cpu(a, s, Math.sin(a + s));
                assertThat(w[0] + w[1] + w[2] + w[3]).isCloseTo(1, within(1e-9));
            }
        }
    }

    @Test
    void cliffsTakeRocksPlaceFromTheirStartToTheirEnd() {
        assertThat(TerrainLayers.cliff(RULES, RULES.cliffSlopeStart() - 1)).isZero();
        assertThat(TerrainLayers.cliff(RULES, RULES.cliffSlopeStart())).isZero();
        assertThat(TerrainLayers.cliff(RULES, 0.5 * (RULES.cliffSlopeStart() + RULES.cliffSlopeEnd())))
            .isCloseTo(0.5, within(1e-9));
        assertThat(TerrainLayers.cliff(RULES, RULES.cliffSlopeEnd())).isEqualTo(1);
        assertThat(TerrainLayers.cliff(RULES, 90)).isEqualTo(1);
        assertThat(RULES.cliffSlopeStart()).as("from about 50 degrees").isCloseTo(50, within(1e-6f));
        assertThat(RULES.cliffSlopeEnd()).as("whole by about 60").isCloseTo(60, within(1e-6f));
        double before = 0;
        for (double s = 40; s <= 70; s += 0.5) {
            double share = TerrainLayers.cliff(RULES, s);
            assertThat(share).isGreaterThanOrEqualTo(before);
            before = share;
        }
        // a cliff's rock is the walls' limestone; rock below the cliffs' slope is rock still
        assertThat(cpu(1500, 70, 0)[TerrainLayers.ROCK] * TerrainLayers.cliff(RULES, 70)).isEqualTo(1, within(1e-9));
        assertThat(cpu(1500, 45, 0)[TerrainLayers.ROCK] * TerrainLayers.cliff(RULES, 45)).isZero();
    }

    @Test
    void theCpuTwinGivesWhatTheShaderGives() {
        int count = 0;
        float[] points = new float[4 * 4096 + 4 * 400];
        for (double a = 300; a < 4600 && count < 4096; a += 97) {
            for (double s = 0; s <= 90 && count < 4096; s += 3.5) {
                for (double c = -1; c <= 1.001 && count < 4096; c += 0.5) {
                    points[4 * count] = (float) a;
                    points[4 * count + 1] = (float) s;
                    points[4 * count + 2] = (float) c;
                    count++;
                }
            }
        }
        int layers = count;
        for (double s = 0; s <= 90; s += 0.25) { // the cliff's share
            points[4 * count + 1] = (float) s;
            points[4 * count + 3] = 1;
            count++;
        }
        float[] gpu;
        try (TestGpu test = TestGpu.open()) {
            gpu = run(test.device(), points, count);
            test.assertNoValidationErrors("the layers kernel");
        }
        double worst = 0;
        for (int i = layers; i < count; i++) {
            worst = Math.max(worst, Math.abs(TerrainLayers.cliff(RULES, points[4 * i + 1]) - gpu[4 * i]));
        }
        for (int i = 0; i < layers; i++) {
            double[] w = cpu(points[4 * i], points[4 * i + 1], points[4 * i + 2]);
            for (int k = 0; k < 4; k++) {
                worst = Math.max(worst, Math.abs(w[k] - gpu[4 * i + k]));
            }
        }
        assertThat(worst).as("largest difference of a weight, over %d points", count).isLessThan(1e-4);
    }

    /** The shader's weights of {@code count} points (altitude, slope, curvature, unused) by the test kernel. */
    private static float[] run(VulkanDevice device, float[] points, int count) {
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        try (GpuBuffer in = GpuBuffer.hostVisible(device, 16L * count, usage);
             GpuBuffer out = GpuBuffer.hostVisible(device, 16L * count, usage);
             GpuBuffer rules = GpuBuffer.hostVisible(device, TerrainParameters.BYTES, usage)) {
            in.memory().copyFrom(java.lang.foreign.MemorySegment.ofArray(points).asSlice(0, 16L * count));
            RULES.write(rules.memory(), 0);
            Key<Image> key = Key.image("test.layers");
            Technique layers = new Technique() {
                @Override
                public List<Key<?>> provides() {
                    return List.of(key);
                }

                @Override
                public void build(GraphBuilder g) {
                    Pipeline pipeline = g.computeProgram("shaders/test/layers.spv", "layersMain", 28);
                    Push push = new Push();
                    Image image = g.create("layers", ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
                    g.pass("layers").write(image, Access.TRANSFER_DST).sideEffects().record(ctx -> {
                        ctx.clear(image.mip(0), 0, 0, 0, 1);
                        ctx.dispatch(pipeline, push.clear().address(in.deviceAddress()).address(out.deviceAddress())
                            .address(rules.deviceAddress()).u32(count), (count + 63) / 64);
                    });
                    g.provide(key, image);
                }
            };
            int imageUsage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            try (FrameGraph graph = new FrameGraph(device, 1);
                 GpuImage image = new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, imageUsage,
                     VK_IMAGE_ASPECT_COLOR_BIT)) {
                graph.add(layers).output(key, new ExternalImage("layers", 4, 4, Format.R8G8B8A8_UNORM, imageUsage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view()));
                graph.beginFrame();
                graph.submit(0, 0);
                device.waitIdle();
            }
            return out.memory().asSlice(0, 16L * count).toArray(ValueLayout.JAVA_FLOAT);
        }
    }
}
