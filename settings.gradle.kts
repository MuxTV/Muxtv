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
    ":app:phone",
    ":core:common",
    ":core:model",
    ":core:database",
    ":core:designsystem",
    ":core:ui",
    ":core:testing",
    ":core:network",
    ":core:credentials",
    ":local-control:core",
    ":local-control:server",
    ":local-control:onboarding",
    ":local-control:crypto-lab",
    ":local-control:crypto-android-compat",
    ":local-control:qr-public",
    ":local-control:tv-qr",
    ":local-control:enrollment",
    ":local-control:wire-lab",
    ":local-control:native-transport",
    ":local-control:phone-flow",
    ":catalog:api",
    ":catalog:ingest",
    ":catalog:importer",
    ":catalog:refresh",
    ":catalog:sync",
    ":catalog:onboarding",
    ":player:api",
    ":player:media3",
    ":player:fake",
    ":feature:home",
    ":feature:channels",
    ":feature:guide",
    ":feature:search",
    ":feature:player",
    ":feature:sources",
    ":feature:doctor",
    ":feature:settings",
    ":benchmark:macrobenchmark",
    ":benchmark:jvm",
    ":benchmark:competitive",
)
