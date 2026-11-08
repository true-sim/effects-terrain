plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Terrain data for true-sim: pinned public elevation sources (manifest, SHA-256, licences), their fetcher " +
    "into a local cache, the GeoTIFF importer, and the terrain's heights, normals and layers on the CPU (no GPU)."

dependencies {
    api("com.truesim.scene:scene-assets:0.70.0") // the asset types and importers the site loads through
    api("com.truesim.common:common-math:0.6.1") // math the same on every platform
}

// Downloads the manifest's tiles into the cache (never committed) and imports them into a site the asset manager
// loads: ./gradlew fetchTerrain [-Ptruesim.terrain.cache=<dir>] [-Ptruesim.terrain.manifest=<name>].
val terrainCache = providers.gradleProperty("truesim.terrain.cache")
    .orElse(rootProject.layout.projectDirectory.dir("terrain-cache").asFile.path)
val terrainManifest = providers.gradleProperty("truesim.terrain.manifest").orElse("lauterbrunnen")
tasks.register<JavaExec>("fetchTerrain") {
    group = "terrain"
    description = "Fetches the manifest's elevation tiles into the terrain cache, checks their SHA-256 and imports them."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.truesim.effects.terrain.data.FetchTerrain"
    args(terrainManifest.get(), terrainCache.get())
}

// The file formats' branches are mostly refusals of what the sources never write (other compressions, layouts).
coverage {
    minimum = 0.75
}
