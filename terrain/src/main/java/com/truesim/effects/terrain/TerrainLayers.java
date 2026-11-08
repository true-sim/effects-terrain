package com.truesim.effects.terrain;

import com.truesim.effects.terrain.material.TerrainParameters;

/**
 * The terrain material's layer rules on the CPU (terrain.slang's {@code terrainLayers}, kept in step): how much rock,
 * grass, scree and snow a point shows from its altitude, slope and curvature, without the shader's noise breaking the
 * lines between them. What placement reads: vegetation where the grass is, no trees on rock or snow.
 */
public final class TerrainLayers {

    /** Index of rock in the weights. */
    public static final int ROCK = 0;
    /** Index of grass in the weights. */
    public static final int GRASS = 1;
    /** Index of scree in the weights. */
    public static final int SCREE = 2;
    /** Index of snow in the weights. */
    public static final int SNOW = 3;

    private TerrainLayers() {
    }

    /**
     * The weights (rock, grass, scree, snow; summing to 1) of a point at {@code altitude} metres, {@code slope}
     * degrees and curvature {@code curvature} (-1 convex to 1 concave), written into {@code weights}.
     */
    public static void weights(TerrainParameters p, double altitude, double slope, double curvature,
        double[] weights) {
        double convex = Math.clamp(-curvature, 0, 1);
        double concave = Math.clamp(curvature, 0, 1);
        double rock = smoothstep(p.rockSlopeStart(), p.rockSlopeEnd(), slope + convex * p.rockCurvature());
        double snowAltitude = altitude + (concave - convex) * p.snowCurvature();
        double snow = smoothstep(p.snowLine() - p.snowLineBlend(), p.snowLine() + p.snowLineBlend(), snowAltitude)
            * (1 - smoothstep(p.snowSlopeStart(), p.snowSlopeEnd(), slope));
        double scree = Math.max(smoothstep(p.screeSlopeStart(), p.screeSlopeEnd(), slope),
            smoothstep(p.grassLine() - p.grassLineBlend(), p.grassLine() + p.grassLineBlend(), altitude));
        scree = Math.clamp(scree + concave * p.screeCurvature()
            * smoothstep(p.screeSlopeStart() * 0.5, p.screeSlopeStart(), slope), 0, 1);
        double rest = (1 - rock) * (1 - snow);
        weights[ROCK] = rock * (1 - snow);
        weights[GRASS] = rest * (1 - scree);
        weights[SCREE] = rest * scree;
        weights[SNOW] = snow;
    }

    /** GLSL's smoothstep: 0 below {@code edge0}, 1 above {@code edge1}, Hermite between. */
    static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
