pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Kilo"
include(":app", ":core-streaming", ":core-profile", ":core-search", ":core-ui", ":core-androidauto", ":core-companion", ":core-journeys", ":core-sounddna", ":core-discoveryradar", ":core-timemachine", ":core-updates")
include(":audioanalysis", ":djmode", ":songrecognition", ":casting")
include(":playbackcache", ":buffermanager", ":sourceprefetch", ":networkmonitor", ":playbackrecovery", ":playbackoptimization")
include(":core-mood")
include(":core:observability")
project(":core:observability").projectDir = file("core/observability")
include(":playback:prepared", ":playback:source", ":playback:warmup", ":playback:predictor", ":playback:stability", ":playback:cache")
project(":playback:prepared").projectDir = file("playback/prepared")
project(":playback:source").projectDir = file("playback/source")
project(":playback:warmup").projectDir = file("playback/warmup")
project(":playback:predictor").projectDir = file("playback/predictor")
project(":playback:stability").projectDir = file("playback/stability")
project(":playback:cache").projectDir = file("playback/cache")
include(":androidauto:performance")
project(":androidauto:performance").projectDir = file("androidauto/performance")
