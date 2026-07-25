pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MuxTV"

include(
    ":app:tv",
    ":core:common",
    ":core:model",
    ":core:database",
    ":core:designsystem",
    ":core:ui",
    ":core:testing",
    ":core:network",
    ":core:credentials",
    ":catalog:api",
    ":catalog:ingest",
    ":catalog:importer",
    ":catalog:refresh",
    ":catalog:sync",
    ":player:api",
    ":player:media3",
    ":player:fake",
    ":feature:home",
    ":feature:channels",
    ":feature:player",
    ":feature:sources",
)
