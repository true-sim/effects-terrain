// The terrain material: rock, grass, scree and snow blended by slope, altitude and curvature, rock projected three
// ways on steep faces (triplanar), each layer a texture set (albedo, normal, roughness) times a color, so it shows
// without textures too. Its tiles (effects-terrain's TileMeshes) carry what it needs in their vertices:
//
//   UV0 + UV1  the planar position, metres: x and z from the tile's origin plus that origin modulo 16384 m, split in
//              two float16s each (high and low parts) so their sum keeps millimetres; continuous across tiles for
//              every texture whose size divides 16384 m.
//   COLOR.rg   the altitude above sea level (without the curvature's fall): (r * 255 * 256 + g * 255) / 8 - 1024
//              metres, linear between vertices because the two bytes interpolate apart.
//   COLOR.b    the curvature at terrain.layerScale: 0.5 + 0.5 * c, c from -1 (convex: ridges) to 1 (concave).
//   COLOR.a    one less the total cover of the modifiers' surface materials (1: the layers alone).
//   TANGENT    the mix of those materials, as a direction (TileMeshes.encodeMix; coverMix here): corners gravel,
//              sand, cobbles and dirt.
//
// The CPU twin of the layer rules is effects-terrain's TerrainLayers: keep them in step.
module terrain;

import materials;

[MaterialParameters(0)]
public struct TerrainParameters
{
    // Rock: cliffs and steep slopes, triplanar
    [Texture(SRGB)] public int rockAlbedo = -1;
    [Texture(LINEAR)] public int rockNormal = -1;
    [Texture(LINEAR)] public int rockRoughness = -1;     // in green, as glTF's ORM
    [Color] public float3 rockColor = float3(0.36, 0.34, 0.32);
    public float rockSize = 8;                           // metres per texture repeat
    // Grass: meadows on gentle slopes below the grass line
    [Texture(SRGB)] public int grassAlbedo = -1;
    [Texture(LINEAR)] public int grassNormal = -1;
    [Texture(LINEAR)] public int grassRoughness = -1;
    [Color] public float3 grassColor = float3(0.16, 0.23, 0.07);
    public float grassSize = 4;
    // Scree: moderate slopes, hollows under cliffs and everything above the grass line
    [Texture(SRGB)] public int screeAlbedo = -1;
    [Texture(LINEAR)] public int screeNormal = -1;
    [Texture(LINEAR)] public int screeRoughness = -1;
    [Color] public float3 screeColor = float3(0.42, 0.40, 0.37);
    public float screeSize = 4;
    // Snow: above the snow line where it is not too steep, deeper into hollows
    [Texture(SRGB)] public int snowAlbedo = -1;
    [Texture(LINEAR)] public int snowNormal = -1;
    [Texture(LINEAR)] public int snowRoughness = -1;
    [Color] public float3 snowColor = float3(0.86, 0.88, 0.92);
    public float snowSize = 8;
    // The rules (degrees and metres)
    [Range(0, 90)] public float rockSlopeStart = 34;     // rock from this slope...
    [Range(0, 90)] public float rockSlopeEnd = 46;       // ...to all rock here
    [Range(0, 90)] public float rockCurvature = 12;      // degrees more slope on convex ground (ridges show rock)
    [Range(0, 90)] public float screeSlopeStart = 24;    // scree from this slope...
    [Range(0, 90)] public float screeSlopeEnd = 34;
    [Range(0, 1)] public float screeCurvature = 0.6;     // how much hollows gather scree
    public float grassLine = 2250;                       // meadows give way to scree round this altitude
    public float grassLineBlend = 200;
    public float snowLine = 2850;                        // snow round this altitude...
    public float snowLineBlend = 250;
    public float snowCurvature = 300;                    // ...this much lower in hollows, higher on ridges
    [Range(0, 90)] public float snowSlopeStart = 38;     // snow slides off from this slope...
    [Range(0, 90)] public float snowSlopeEnd = 55;
    [Range(0, 1)] public float jitter = 0.35;            // how much noise breaks the lines between layers
    [Range(0, 64)] public float triplanarSharpness = 6;  // how sharply rock's three projections part (1 and up)
    [Range(0, 1)] public float macro = 0.25;             // how much large-scale variation tints the layers
    [Range(0, 4)] public float normalStrength = 1;
    // The modifiers' surface materials, laid over the layers where the tiles carry their cover (TerrainModifier):
    // gravel (river beds and bars, gravel roads), cobbles (streets), dirt (tracks), sand (river margins)
    [Texture(SRGB)] public int gravelAlbedo = -1;
    [Texture(LINEAR)] public int gravelNormal = -1;
    [Texture(LINEAR)] public int gravelRoughness = -1;
    [Color] public float3 gravelColor = float3(0.30, 0.29, 0.27);
    public float gravelSize = 2;
    [Texture(SRGB)] public int cobblesAlbedo = -1;
    [Texture(LINEAR)] public int cobblesNormal = -1;
    [Texture(LINEAR)] public int cobblesRoughness = -1;
    [Color] public float3 cobblesColor = float3(0.24, 0.23, 0.22);
    public float cobblesSize = 2;
    [Texture(SRGB)] public int dirtAlbedo = -1;
    [Texture(LINEAR)] public int dirtNormal = -1;
    [Texture(LINEAR)] public int dirtRoughness = -1;
    [Color] public float3 dirtColor = float3(0.26, 0.19, 0.13);
    public float dirtSize = 4;
    [Texture(SRGB)] public int sandAlbedo = -1;
    [Texture(LINEAR)] public int sandNormal = -1;
    [Texture(LINEAR)] public int sandRoughness = -1;
    [Color] public float3 sandColor = float3(0.45, 0.41, 0.33);
    public float sandSize = 4;
    [Range(0, 1)] public float coverJitter = 0.5;        // how much noise breaks the shoulders' edges
    [Range(0, 1)] public float coverRoughness = 0.85;    // the materials' roughness without textures
};

/**
 * The mix of the modifiers' materials a tile's tangent carries (TileMeshes.encodeMix): their weights, gravel, sand,
 * cobbles, dirt, summing to 1, bilinear between the corners.
 */
public float4 coverMix(float3 t)
{
    float l1 = abs(t.x) + abs(t.y) + abs(t.z);
    float2 uv = l1 > 1e-6 ? t.xy / l1 : float2(0, -0.5);
    float a = saturate(uv.y + 0.5 + uv.x);
    float b = saturate(uv.y + 0.5 - uv.x);
    return float4((1 - a) * (1 - b), a * (1 - b), (1 - a) * b, a * b);
}

/** The layer weights the rules give: rock, grass, scree, snow (summing to 1). */
public float4 terrainLayers(TerrainParameters p, float altitude, float slope, float curvature, float jitter)
{
    float convex = saturate(-curvature);
    float concave = saturate(curvature);
    float rock = smoothstep(p.rockSlopeStart, p.rockSlopeEnd, slope + convex * p.rockCurvature + jitter * 6);
    float snowAltitude = altitude + (concave - convex) * p.snowCurvature + jitter * p.snowLineBlend;
    float snow = smoothstep(p.snowLine - p.snowLineBlend, p.snowLine + p.snowLineBlend, snowAltitude)
        * (1 - smoothstep(p.snowSlopeStart, p.snowSlopeEnd, slope));
    float scree = max(smoothstep(p.screeSlopeStart, p.screeSlopeEnd, slope + jitter * 6),
        smoothstep(p.grassLine - p.grassLineBlend, p.grassLine + p.grassLineBlend, altitude + jitter * p.grassLineBlend));
    scree = saturate(scree + concave * p.screeCurvature * smoothstep(p.screeSlopeStart * 0.5, p.screeSlopeStart, slope));
    float rest = (1 - rock) * (1 - snow);
    return float4(rock * (1 - snow), rest * (1 - scree), rest * scree, snow);
}

/** A hash of a lattice point, 0 to 1, periodic every 128 points (16384 m at 128 m apart: across tiles). */
float lattice(int2 c)
{
    uint2 w = uint2(c & 127);
    uint h = w.x * 0x8da6b343u ^ w.y * 0xd8163841u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return float(h & 0xffffu) / 65535.0;
}

/** Value noise, 0 to 1, of the planar position `xz` at cells `cell` metres wide (128 / cell whole). */
float valueNoise(float2 xz, float cell)
{
    float2 f = xz / cell;
    float2 i = floor(f);
    float2 u = f - i;
    u = u * u * (3 - 2 * u);
    int2 c = int2(i);
    return lerp(lerp(lattice(c), lattice(c + int2(1, 0)), u.x), lerp(lattice(c + int2(0, 1)), lattice(c + int2(1, 1)), u.x),
        u.y);
}

/** One layer's texture set at `uv` (gradients `dx`, `dy`): albedo (times its color) and roughness in .w. */
float4 layerColor(int albedo, int roughness, float3 color, float2 uv, float2 dx, float2 dy)
{
    float3 a = color;
    if (albedo >= 0)
    {
        a *= sampleTexture(albedo, uv, dx, dy).rgb;
    }
    float r = 0.9;
    if (roughness >= 0)
    {
        r = sampleTexture(roughness, uv, dx, dy).g;
    }
    return float4(a, r);
}

/** A tangent-space normal of map `slot` at `uv` (0, 0, 1 without one), its xy scaled by `strength`. */
float3 layerNormal(int slot, float2 uv, float2 dx, float2 dy, float strength)
{
    if (slot < 0)
    {
        return float3(0, 0, 1);
    }
    float3 t = sampleTexture(slot, uv, dx, dy).xyz * 2 - 1;
    t.xy *= strength;
    return t;
}

[MaterialType("terrain")]
public struct TerrainType : IMaterial
{
    public typealias Parameters = TerrainParameters;

    public static Surface surface(TerrainParameters p, MaterialRef m, SurfaceInput in)
    {
        float3 n = normalize(in.normal);
        // Where the point is: the planar position (UV0 + UV1), the altitude and curvature (the vertex color)
        float2 xz = in.uv + in.uv1;
        float2 dxz = in.dx + in.dx1;
        float2 dyz = in.dy + in.dy1;
        float altitude = (in.color.r * 255.0 * 256.0 + in.color.g * 255.0) / 8.0 - 1024.0;
        float curvature = in.color.b * 2 - 1;
        // The altitude's own screen gradients, from the slope: how far up a step across the screen goes
        float ny = max(abs(n.y), 0.08);
        float dAltX = -(n.x * dxz.x + n.z * dxz.y) / ny;
        float dAltY = -(n.x * dyz.x + n.z * dyz.y) / ny;
        // The slope the layers follow: the surface's own, which tiles morph from level to level with their normals
        float slope = degrees(acos(clamp(n.y, -1.0, 1.0)));
        float noise = valueNoise(xz, 16) * 0.65 + valueNoise(xz, 4) * 0.35;
        float4 w = terrainLayers(p, altitude, slope, curvature, (noise - 0.5) * 2 * p.jitter);

        float3 albedo = 0;
        float roughness = 0;
        float3 normal = 0;
        // Grass, scree, snow: projected from above
        [ForceUnroll]
        for (int layer = 1; layer < 4; layer++)
        {
            float weight = w[layer];
            if (weight <= 0.001)
            {
                continue;
            }
            int a = layer == 1 ? p.grassAlbedo : layer == 2 ? p.screeAlbedo : p.snowAlbedo;
            int r = layer == 1 ? p.grassRoughness : layer == 2 ? p.screeRoughness : p.snowRoughness;
            int nm = layer == 1 ? p.grassNormal : layer == 2 ? p.screeNormal : p.snowNormal;
            float3 c = layer == 1 ? p.grassColor : layer == 2 ? p.screeColor : p.snowColor;
            float size = layer == 1 ? p.grassSize : layer == 2 ? p.screeSize : p.snowSize;
            float2 uv = xz / size;
            float2 dx = dxz / size;
            float2 dy = dyz / size;
            float4 ar = layerColor(a, r, c, uv, dx, dy);
            float3 t = layerNormal(nm, uv, dx, dy, p.normalStrength);
            albedo += ar.rgb * weight;
            roughness += ar.w * weight;
            // whiteout onto the surface: tangent x along +x, y along +z
            normal += float3(t.x + n.x, abs(t.z) * n.y, t.y + n.z) * weight;
        }
        // Rock: three projections, by how much the surface faces each axis
        if (w.x > 0.001)
        {
            float3 blend = pow(abs(n), max(p.triplanarSharpness, 1.0));
            blend /= blend.x + blend.y + blend.z;
            float inv = 1 / p.rockSize;
            float4 ar = 0;
            float3 rn = 0;
            if (blend.y > 0.01)
            {
                float2 uv = xz * inv;
                float4 c = layerColor(p.rockAlbedo, p.rockRoughness, p.rockColor, uv, dxz * inv, dyz * inv);
                float3 t = layerNormal(p.rockNormal, uv, dxz * inv, dyz * inv, p.normalStrength);
                ar += c * blend.y;
                rn += float3(t.x + n.x, abs(t.z) * n.y, t.y + n.z) * blend.y;
            }
            if (blend.x > 0.01)
            {
                float2 uv = float2(xz.y, altitude) * inv;
                float2 dx = float2(dxz.y, dAltX) * inv;
                float2 dy = float2(dyz.y, dAltY) * inv;
                float4 c = layerColor(p.rockAlbedo, p.rockRoughness, p.rockColor, uv, dx, dy);
                float3 t = layerNormal(p.rockNormal, uv, dx, dy, p.normalStrength);
                ar += c * blend.x;
                rn += float3(abs(t.z) * n.x, t.y + n.y, t.x + n.z) * blend.x;
            }
            if (blend.z > 0.01)
            {
                float2 uv = float2(xz.x, altitude) * inv;
                float2 dx = float2(dxz.x, dAltX) * inv;
                float2 dy = float2(dyz.x, dAltY) * inv;
                float4 c = layerColor(p.rockAlbedo, p.rockRoughness, p.rockColor, uv, dx, dy);
                float3 t = layerNormal(p.rockNormal, uv, dx, dy, p.normalStrength);
                ar += c * blend.z;
                rn += float3(t.x + n.x, t.y + n.y, abs(t.z) * n.z) * blend.z;
            }
            albedo += ar.rgb * w.x;
            roughness += ar.w * w.x;
            normal += rn * w.x;
        }
        // Large-scale variation, so wide slopes do not repeat
        float macro = valueNoise(xz, 128) * 0.6 + valueNoise(xz, 32) * 0.4;
        albedo *= 1 + (macro - 0.5) * 2 * p.macro;

        // The modifiers' surface materials over the layers, their shoulders broken by noise
        float cover = saturate(1 - in.color.a);
        if (cover > 0.002)
        {
            float edge = valueNoise(xz, 1) * 0.6 + valueNoise(xz, 0.25) * 0.4;
            cover = saturate(cover + (edge - 0.5) * 2 * p.coverJitter * cover * (1 - cover) * 4);
            cover = smoothstep(0.0, 1.0, cover);
            float4 mix = coverMix(in.tangent.xyz);
            float3 ca = 0;
            float cr = 0;
            float3 cn = 0;
            [ForceUnroll]
            for (int k = 0; k < 4; k++)
            {
                float weight = mix[k];
                if (weight <= 0.001)
                {
                    continue;
                }
                int a = k == 0 ? p.gravelAlbedo : k == 1 ? p.sandAlbedo : k == 2 ? p.cobblesAlbedo : p.dirtAlbedo;
                int r = k == 0 ? p.gravelRoughness : k == 1 ? p.sandRoughness : k == 2 ? p.cobblesRoughness : p.dirtRoughness;
                int nm = k == 0 ? p.gravelNormal : k == 1 ? p.sandNormal : k == 2 ? p.cobblesNormal : p.dirtNormal;
                float3 c = k == 0 ? p.gravelColor : k == 1 ? p.sandColor : k == 2 ? p.cobblesColor : p.dirtColor;
                float size = max(k == 0 ? p.gravelSize : k == 1 ? p.sandSize : k == 2 ? p.cobblesSize : p.dirtSize, 0.01);
                float2 uv = xz / size;
                float4 ar = layerColor(a, r, c, uv, dxz / size, dyz / size);
                if (r < 0)
                {
                    ar.w = p.coverRoughness;
                }
                float3 t = layerNormal(nm, uv, dxz / size, dyz / size, p.normalStrength);
                ca += ar.rgb * weight;
                cr += ar.w * weight;
                cn += float3(t.x + n.x, abs(t.z) * n.y, t.y + n.z) * weight;
            }
            // without textures, a little grain so the materials read as ground
            float grain = valueNoise(xz, 0.125) * 0.5 + valueNoise(xz, 0.5) * 0.5;
            ca *= 0.8 + 0.4 * grain;
            albedo = lerp(albedo, ca, cover);
            roughness = lerp(roughness, cr, cover);
            normal = lerp(normal, cn, cover);
        }

        float3 shaded = normalize(normal);
        if (!all(isfinite(shaded)) || dot(shaded, n) <= 0)
        {
            shaded = n;
        }
        Surface s = Surface.defaults(shaded);
        s.albedo = saturate(albedo);
        s.roughness = clamp(roughness, 0.3, 1.0);
        s.metallic = 0;
        return s;
    }
};
