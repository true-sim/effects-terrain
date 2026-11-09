// For the tests: the terrain material's layer rules run on the GPU, so TerrainLayersTest holds their CPU twin
// (TerrainLayers) to the shader. One thread per point: altitude, slope, curvature in; rock, grass, scree, snow out
// (or the cliff's share of rock).
// (Its presence also builds the test listing's shading variants, TestTerrainShadingHooks.)
module layers;

import terrain.terrain;

public struct LayersPush
{
    public float4* points;   // altitude, slope (degrees), curvature, 1: the cliff's share of rock instead
    public float4* weights;  // rock, grass, scree, snow
    public TerrainParameters* rules;
    public uint count;
};

[[vk::push_constant]] ConstantBuffer<LayersPush> push;

[shader("compute")]
[numthreads(64, 1, 1)]
void layersMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= push.count)
    {
        return;
    }
    float4 p = push.points[id.x];
    push.weights[id.x] = p.w > 0.5 ? float4(cliffShare(*push.rules, p.y), 0, 0, 0)
        : terrainLayers(*push.rules, p.x, p.y, p.z, 0);
}
