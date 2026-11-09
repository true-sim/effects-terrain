package com.truesim.effects.terrain;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.color.Color;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.TextureData;
import java.lang.reflect.RecordComponent;

/**
 * The terrain's material as scene data: the {@code terrain} material type ({@code TerrainType.TYPE}, registered with the
 * renderer's materials) with its rules and a texture set per layer, the textures assets like any other (the showcase
 * gives Poly Haven's; tests generate plain ones). Without textures a layer shows its color.
 *
 * <pre>{@code
 * materials.register(TerrainType.TYPE);
 * MaterialData data = TerrainMaterial.data(TerrainParameters.DEFAULTS,
 *     new TerrainMaterial.Layer(rockAlbedo, rockNormal, rockRoughness, 8), grass, scree, snow);
 * }</pre>
 */
public final class TerrainMaterial {

    /** The material type's name. */
    public static final String TYPE = "terrain";

    private TerrainMaterial() {
    }

    /**
     * One layer's texture set: albedo (sRGB), tangent-space normal and roughness (in green, as glTF's ORM) textures,
     * each null for none, and the metres one repeat of them covers (snapped so a whole number of repeats fits in
     * {@link TerrainGrid#PLANAR_PERIOD}: textures run on unbroken across tiles).
     */
    public record Layer(AssetRef<TextureData> albedo, AssetRef<TextureData> normal, AssetRef<TextureData> roughness,
        double size) {

        /** A layer of its color alone. */
        public static final Layer PLAIN = new Layer(null, null, null, 4);

        public Layer {
            if (!(size > 0) || size > TerrainGrid.PLANAR_PERIOD) {
                throw new IllegalArgumentException("A layer's textures repeat every 0 to " + TerrainGrid.PLANAR_PERIOD
                    + " m: " + size);
            }
        }
    }

    /** {@code size} snapped so a whole number of repeats fits in the planar period. */
    public static float snap(double size) {
        double repeats = Math.max(1, Math.rint(TerrainGrid.PLANAR_PERIOD / size));
        return (float) (TerrainGrid.PLANAR_PERIOD / repeats);
    }

    /**
     * The texture sets of the modifiers' surface materials ({@link com.truesim.effects.terrain.data.TerrainModifier}):
     * gravel, cobbles, dirt and sand; {@link Layer#PLAIN} for a color alone.
     */
    public record Cover(Layer gravel, Layer cobbles, Layer dirt, Layer sand) {

        /** Every material its color alone. */
        public static final Cover PLAIN = new Cover(new Layer(null, null, null, 2), new Layer(null, null, null, 2),
            Layer.PLAIN, Layer.PLAIN);
    }

    /**
     * The material data of {@code rules} with the four layers' texture sets and the modifiers' surface materials'
     * ({@code cover}).
     */
    @AllowAllocation("material data, made rarely")
    public static MaterialData data(TerrainParameters rules, Layer rock, Layer grass, Layer scree, Layer snow,
        Cover cover) {
        MaterialData.Builder builder = builder(rules, rock, grass, scree, snow);
        layer(builder, "gravel", cover.gravel());
        layer(builder, "cobbles", cover.cobbles());
        layer(builder, "dirt", cover.dirt());
        layer(builder, "sand", cover.sand());
        return builder.build();
    }

    /** The material data of the rules and colors of {@code rules} with the four layers' texture sets. */
    @AllowAllocation("material data, made rarely")
    public static MaterialData data(TerrainParameters rules, Layer rock, Layer grass, Layer scree, Layer snow) {
        return builder(rules, rock, grass, scree, snow).build();
    }

    @AllowAllocation("material data, made rarely")
    private static MaterialData.Builder builder(TerrainParameters rules, Layer rock, Layer grass, Layer scree,
        Layer snow) {
        MaterialData.Builder builder = MaterialData.builder(TYPE);
        try {
            for (RecordComponent component : TerrainParameters.class.getRecordComponents()) {
                Object value = component.getAccessor().invoke(rules);
                String name = component.getName();
                if (value instanceof Float f && !name.endsWith("Size")) {
                    builder.set(name, f);
                } else if (value instanceof Color c) {
                    builder.set(name, (float) c.r(), (float) c.g(), (float) c.b());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        layer(builder, "rock", rock);
        layer(builder, "grass", grass);
        layer(builder, "scree", scree);
        layer(builder, "snow", snow);
        return builder;
    }

    @AllowAllocation("material data, made rarely")
    private static void layer(MaterialData.Builder builder, String name, Layer layer) {
        builder.set(name + "Size", snap(layer.size()));
        if (layer.albedo() != null) {
            builder.texture(name + "Albedo", layer.albedo());
        }
        if (layer.normal() != null) {
            builder.texture(name + "Normal", layer.normal());
        }
        if (layer.roughness() != null) {
            builder.texture(name + "Roughness", layer.roughness());
        }
    }
}
