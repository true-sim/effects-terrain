pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.19.1"
}

rootProject.name = "effects-terrain"

// Terrain from real elevation data: the pinned sources, their fetcher and importer, and the heights on the CPU (no GPU,
// for servers and placement tools), apart from the terrain drawn through the scene's streaming and the engine.
include("effects-terrain-data")
project(":effects-terrain-data").projectDir = file("data")
include("effects-terrain")
project(":effects-terrain").projectDir = file("terrain")

// Sibling checkouts are used as included builds when present (override with -Ptruesim.<repo>=<path>, or false).
val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "engine",
    "effects-atmosphere", "effects-weather")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
