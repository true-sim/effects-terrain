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
//              sand, cobbles and dirt; its sign (w) -1 on a wall whose relief is in the mesh (CliffMesh), so the
//              wall's ledges take the cliff layer whatever their slope.
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
    // Cliffs: the walls past cliffSlopeStart, limestone (or whatever texture set is given), projected three ways with
    // a world-space detail normal of bedding and joints, darkened by water streaks down the faces, lightened by fresh
    // scars, tinted by lichen and darkened where wet at their foot. Part of rock: it takes rock's place on the walls.
    [Texture(SRGB)] public int cliffAlbedo = -1;
    [Texture(LINEAR)] public int cliffNormal = -1;
    [Texture(LINEAR)] public int cliffRoughness = -1;
    [Color] public float3 cliffColor = float3(0.40, 0.395, 0.38); // limestone: 0.35 to 0.45 (times a texture's)
    public float cliffSize = 12;
    [Range(0, 1)] public float cliffSaturation = 1;      // a texture's colour kept (0: grey, as limestone; scans are often warm)
    [Range(0, 90)] public float cliffSlopeStart = 50;    // the walls' limestone from this slope...
    [Range(0, 90)] public float cliffSlopeEnd = 60;      // ...to all of it here
    [Range(0, 1)] public float cliffRough = 0.85;        // the rock's roughness (a texture's varies round it)
    [Range(0, 64)] public float cliffSharpness = 8;      // how sharply the three projections part (1 and up)
    [Range(0, 4)] public float beddingStrength = 0.5;    // the detail normal's bedding planes, 0: none
    [Range(0, 100)] public float beddingThickness = 1.3; // metres between bedding planes
    [Range(0, 45)] public float beddingDip = 4;          // degrees the beds dip...
    [Range(0, 360)] public float beddingAzimuth = 150;   // ...towards this azimuth, clockwise from north
    [Range(0, 100)] public float beddingWaviness = 0.6;  // metres the bedding planes wave up and down
    [Range(0, 4)] public float jointStrength = 0.3;      // the detail normal's vertical joints, 0: none
    [Range(0, 100)] public float jointSpacing = 9;       // metres between joints
    [Range(0, 1)] public float streaks = 0.55;           // dark water streaks down the faces
    [Range(0, 1)] public float bands = 0.25;             // darker bands under the beds' ledges (seen from afar)
    [Range(0, 100)] public float bandSpacing = 9;        // metres between them (as terrain.cliffs.bedThickness)
    [Range(0, 1)] public float scars = 0.35;             // lighter fresh rockfall scars
    [Range(0, 1)] public float lichen = 0.3;             // a lichen tint in patches
    [Color] public float3 lichenColor = float3(0.30, 0.31, 0.22);
    [Range(0, 1)] public float wetFoot = 0.5;            // darker where wet at the walls' foot
    [Range(0, 1)] public float streakRough = 0.93;       // the streaks' roughness: matte, never a sky reflection
    [Color] public float3 ledgeColor = float3(0.30, 0.295, 0.28); // the ledges' weathered limestone and grey debris
    [Range(0, 1)] public float ledgeLichen = 0.45;       // how much lichen the ledges hold
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

/** How much of the rock a slope of `slope` degrees shows as cliff (the walls' limestone): 0 to 1. */
public float cliffShare(TerrainParameters p, float slope)
{
    return smoothstep(p.cliffSlopeStart, max(p.cliffSlopeEnd, p.cliffSlopeStart + 0.01), slope);
}

/** A bedding plane's profile, period 1: a sharp step at each plane, easing back over the bed. */
float beddingStep(float u)
{
    float f = u - floor(u);
    return f - smoothstep(0.9, 1.0, f);
}

/**
 * The cliffs' world-space detail height at `q` (x, altitude, z; metres) for a pixel `pixel` metres across: bedding
 * planes near horizontal (dipping, waving) and two sets of vertical joints, each faded out where the pixel cannot
 * hold it.
 */
float cliffDetail(TerrainParameters p, float3 q, float pixel)
{
    float az = radians(p.beddingAzimuth);
    float along = q.x * sin(az) - q.z * cos(az);
    float wave = (valueNoise(q.xz, 16) - 0.5) * 2 * p.beddingWaviness;
    float bed = q.y + tan(radians(p.beddingDip)) * along + wave;
    float t = max(p.beddingThickness, 0.05);
    float h = 0;
    // fine laminae and stronger planes, each its own thickness and strength (some missing), fading along the wall
    float fine = saturate(t / (6 * pixel) - 0.5);
    float coarse = saturate(4 * t / (6 * pixel) - 0.5);
    float lf = bed / t + (valueNoise(float2(bed / (3 * t), 0.25), 1) - 0.5) * 1.2;
    float lc = bed / (4 * t) + (valueNoise(float2(bed / (9 * t), 0.75), 1) - 0.5) * 1.1;
    float sf = lattice(int2(int(floor(lf)), 3));
    float sc = lattice(int2(int(floor(lc)), 5));
    float along2 = q.x * 0.906 + q.z * 0.423;
    float fadeF = saturate(valueNoise(float2(along2, floor(lf) * 13.0), 12) * 2.2 - 0.6);
    float fadeC = saturate(valueNoise(float2(along2, floor(lc) * 17.0), 32) * 2.4 - 0.7);
    h += p.beddingStrength * t * 0.12 * (fine * beddingStep(lf) * smoothstep(0.25, 0.6, sf) * fadeF
        + coarse * 1.6 * beddingStep(lc) * smoothstep(0.2, 0.5, sc) * fadeC);
    float sj = max(p.jointSpacing, 0.1);
    float jfade = saturate(sj / (12 * pixel) - 0.5);
    if (jfade > 0)
    {
        float a = q.x * 0.906 + q.z * 0.423; // the sets strike 25 and 115 degrees, as the relief's joints
        float b = -q.x * 0.423 + q.z * 0.906;
        float ja = abs(frac(a / sj + valueNoise(q.xz, 8) * 0.6) - 0.5) * 2;
        float jb = abs(frac(b / (1.7 * sj) + valueNoise(q.zx, 8) * 0.6) - 0.5) * 2;
        h -= p.jointStrength * jfade * sj * 0.05 * (smoothstep(0.86, 1.0, ja) + smoothstep(0.9, 1.0, jb));
    }
    return h;
}

/**
 * The cliff layer at a point of normal `n`: albedo and roughness into `ar`, the shading normal returned (world space).
 * Three projections of the texture set (the x and z faces with the altitude as v, the top with the plane), the
 * world-space detail of bedding and joints, then the faces' macro variation: streaks, scars, lichen, the wet foot.
 */
float3 cliffSurface(TerrainParameters p, float3 n, float2 xz, float altitude, float2 dxz, float2 dyz, float dAltX,
    float dAltY, float curvature, float wall, out float4 ar)
{
    float3 blend = pow(abs(n), max(p.cliffSharpness, 1.0));
    blend /= blend.x + blend.y + blend.z;
    float inv = 1 / max(p.cliffSize, 0.01);
    float4 c = 0;
    float3 rn = 0;
    if (blend.y > 0.01)
    {
        float2 uv = xz * inv;
        c += layerColor(p.cliffAlbedo, p.cliffRoughness, p.cliffColor, uv, dxz * inv, dyz * inv) * blend.y;
        float3 t = layerNormal(p.cliffNormal, uv, dxz * inv, dyz * inv, p.normalStrength);
        rn += float3(t.x + n.x, abs(t.z) * n.y, t.y + n.z) * blend.y;
    }
    if (blend.x > 0.01)
    {
        float2 uv = float2(xz.y, altitude) * inv;
        float2 dx = float2(dxz.y, dAltX) * inv;
        float2 dy = float2(dyz.y, dAltY) * inv;
        c += layerColor(p.cliffAlbedo, p.cliffRoughness, p.cliffColor, uv, dx, dy) * blend.x;
        float3 t = layerNormal(p.cliffNormal, uv, dx, dy, p.normalStrength);
        rn += float3(abs(t.z) * n.x, t.y + n.y, t.x + n.z) * blend.x;
    }
    if (blend.z > 0.01)
    {
        float2 uv = float2(xz.x, altitude) * inv;
        float2 dx = float2(dxz.x, dAltX) * inv;
        float2 dy = float2(dyz.x, dAltY) * inv;
        c += layerColor(p.cliffAlbedo, p.cliffRoughness, p.cliffColor, uv, dx, dy) * blend.z;
        float3 t = layerNormal(p.cliffNormal, uv, dx, dy, p.normalStrength);
        rn += float3(t.x + n.x, t.y + n.y, abs(t.z) * n.z) * blend.z;
    }
    float grey = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));
    c.rgb = lerp(float3(grey, grey, grey) * float3(1.0, 0.99, 0.96), c.rgb, p.cliffSaturation);
    float3 shaded = normalize(rn);
    if (!all(isfinite(shaded)))
    {
        shaded = n;
    }
    // the rock's roughness, a texture's varying round it
    float rough = p.cliffRoughness >= 0 ? lerp(p.cliffRough, c.w, 0.35) : p.cliffRough;

    // World-space detail, bedding and joints: its gradient by differences, taken across the surface
    float pixel = max(length(float3(dxz.x, dAltX, dxz.y)), length(float3(dyz.x, dAltY, dyz.y)));
    pixel = max(pixel, 0.002);
    float3 q = float3(xz.x, altitude, xz.y);
    float e = max(pixel, 0.03);
    float h0 = cliffDetail(p, q, pixel);
    float3 grad = float3(cliffDetail(p, q + float3(e, 0, 0), pixel) - h0,
        cliffDetail(p, q + float3(0, e, 0), pixel) - h0, cliffDetail(p, q + float3(0, 0, e), pixel) - h0) / e;
    grad -= shaded * dot(shaded, grad);
    float3 detailed = normalize(shaded - grad);
    if (all(isfinite(detailed)) && dot(detailed, n) > 0)
    {
        shaded = detailed;
    }

    // Macro variation on the faces, in (along the face, altitude) of the two side projections
    float side = blend.x + blend.z + 1e-4;
    float across = lerp(xz.x, xz.y, blend.x / side);
    // water streaks: narrow along the face, long down it
    float2 sq = float2(across, altitude * 0.05);
    float streak = valueNoise(sq, 8) * 0.6 + valueNoise(sq * float2(1, 2), 2) * 0.4;
    streak = smoothstep(0.55, 0.85, streak) * (0.5 + 0.5 * valueNoise(float2(across, altitude * 0.3), 32));
    // bands: darker under each bed's ledge, where the wall steps in (the relief's beds, dipping as the bedding)
    float az = radians(p.beddingAzimuth);
    float bed = altitude + tan(radians(p.beddingDip)) * (xz.x * sin(az) - xz.y * cos(az))
        + (valueNoise(xz, 32) - 0.5) * 4;
    float spacing = max(p.bandSpacing, 0.5);
    float u = bed / spacing + (valueNoise(float2(bed / spacing, 0.5), 1) - 0.5) * 1.3;
    float f = u - floor(u);
    // each band its own width and depth (some none), each fading in and out along the wall where it likes
    float which = floor(u);
    float depth = lattice(int2(int(which), 9));
    float width = 0.12 + 0.25 * lattice(int2(int(which), 11));
    float band = smoothstep(0.92 - width, 0.92 - width * 0.4, f) * (1 - smoothstep(0.93, 1.0, f));
    band *= smoothstep(0.25, 0.55, depth) * saturate(valueNoise(float2(across, which * 23.0), 40) * 2.4 - 0.7);
    band *= saturate(spacing / (8 * pixel) - 0.25);
    // fresh scars: lighter, warmer patches where rock broke away
    float scar = smoothstep(0.64, 0.74, valueNoise(float2(across, altitude * 0.7), 24) * 0.7
        + valueNoise(float2(across, altitude), 6) * 0.3);
    // lichen: patches, more where the wall is less steep
    float patch = smoothstep(0.45, 0.8, valueNoise(float2(across, altitude), 5) * 0.6 + valueNoise(xz, 2) * 0.4);
    float flat = saturate(1.9 - 1.6 * sqrt(saturate(1 - n.y * n.y)));
    // wet at the foot: the concave bend at the bottom of the walls
    float wet = smoothstep(0.15, 0.7, curvature) * p.wetFoot;

    float3 albedo = c.rgb;
    // the ledges on a wall: weathered limestone and grey debris, a little lichen (not the slopes' layers)
    float ledge = wall * smoothstep(0.45, 0.8, n.y);
    float debris = 0.75 + 0.5 * (valueNoise(xz, 0.35) * 0.6 + valueNoise(xz, 1.5) * 0.4);
    albedo = lerp(albedo, p.ledgeColor * debris, ledge * 0.85);
    patch = lerp(patch, max(patch, smoothstep(0.35, 0.7, valueNoise(xz, 1.2))), ledge * p.ledgeLichen);
    // water streaks: darker and dull (organic stains: brown-grey, rough)
    albedo *= 1 - p.streaks * 0.65 * streak * float3(0.92, 1.0, 1.18); // stained brown-grey: blue darkened most
    rough = lerp(rough, max(p.streakRough, rough), saturate(streak * 1.5));
    albedo *= 1 - p.bands * band;
    albedo = lerp(albedo, albedo * float3(1.32, 1.27, 1.18) + float3(0.04, 0.035, 0.02),
        p.scars * scar * (1 - streak));
    albedo = lerp(albedo, p.lichenColor * (0.6 + 0.8 * valueNoise(xz, 0.5)),
        saturate(p.lichen * patch * (0.5 + 0.5 * flat) + ledge * p.ledgeLichen * patch * 0.5));
    albedo *= 1 - 0.45 * wet;
    rough = max(lerp(rough, 0.7, wet * 0.7), 0.6);
    ar = float4(albedo, rough);
    return shaded;
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
        // Cliffs: the walls' share of rock
        // the walls' share: rock's past the cliffs' slope, and everything on a wall (the tiles flag a wall's vertices by
        // their tangent's sign: its ledges stay limestone and debris, not the slopes' layers)
        float wall = saturate(0.5 - 0.5 * in.tangent.w);
        float rockCliff = w.x * cliffShare(p, slope + (noise - 0.5) * 2 * p.jitter * 4);
        float cliff = max(rockCliff, wall * (1 - w.w));
        w.x -= rockCliff;
        w *= (1 - cliff) / max(1 - rockCliff, 1e-4);
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
        if (cliff > 0.001)
        {
            float4 car;
            float3 cn = cliffSurface(p, n, xz, altitude, dxz, dyz, dAltX, dAltY, curvature, wall, car);
            albedo += car.rgb * cliff * (1 + (macro - 0.5) * p.macro);
            roughness += car.w * cliff;
            normal += cn * cliff;
        }

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
