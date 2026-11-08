// For the tests: the terrain material's layer rules run on the GPU, so TerrainLayersTest holds their CPU twin
// (TerrainLayers) to the shader. One thread per point: altitude, slope, curvature in; rock, grass, scree, snow out.
// (Its presence also builds the test listing's shading variants, TestTerrainShadingHooks.)
module layers;

import terrain.terrain;

public struct LayersPush
{
    public float4* points;   // altitude, slope (degrees), curvature, unused
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
    push.weights[id.x] = terrainLayers(*push.rules, p.x, p.y, p.z, 0);
}
