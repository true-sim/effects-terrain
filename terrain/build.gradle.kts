plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Terrain for true-sim: heightfield tiles from real elevation data streamed by the scene's multi-level " +
    "world streaming, built into meshlet meshes through the asset manager, and the terrain material type (rock, " +
    "grass, scree and snow by slope, height and curvature, triplanar on steep rock) in every way the engine shades."

dependencies {
    api(project(":effects-terrain-data"))
    api("com.truesim.engine:engine-scene:0.194.0") // the renderer, the scene bridge, material types
    api("com.truesim.scene:scene-world:0.70.0") // multi-level streaming
    api("com.truesim.scene:scene-meshbuild:0.70.0") // tiles into meshlets
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.194.0")) // the headless renderer
    testImplementation("com.truesim.effects:atmosphere-render:0.4.0") // the sky of the films
    testImplementation("com.truesim.effects:weather-snow:0.3.0") // snow lying on the terrain
}

// The terrain material's parameters (TerrainParameters) as a Java record and its MaterialType (TerrainMaterial).
slang {
    javaPackage = "com.truesim.effects.terrain.material"
}

// The benchmarks (TRUESIM_BENCHMARK=true) and films (TRUESIM_SCREENSHOTS=true) measure the code, not its coverage
// probes; films go to -Pscreenshots=<directory>, the terrain from -Ptruesim.terrain.cache=<directory>.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "6g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
    providers.gradleProperty("screenshots").orNull?.let { systemProperty("truesim.screenshots", it) }
    providers.gradleProperty("truesim.terrain.cache").orNull?.let { systemProperty("truesim.terrain.cache", it) }
}
