---
status: accepted
last_reviewed: 2026-07-19
scope: official-primary-sources
---

# Официальные источники архитектуры

Проверено 19 июля 2026 года. При обновлении stack сначала проверяются эти источники, затем меняется `meta/dependencies.yaml` и оформляется ADR при изменении архитектурного поведения.

## Android TV и UI

- Compose for TV releases: https://developer.android.com/jetpack/androidx/releases/tv
- Compose on Android TV: https://developer.android.com/training/tv/playback/compose
- Compose for TV introduction: https://developer.android.com/codelabs/compose-for-tv-introduction
- Navigation 3 releases: https://developer.android.com/jetpack/androidx/releases/navigation3

Зафиксировано:

- Compose for TV — основной TV UI framework;
- stable `tv-material` 1.1.0 и `tv-foundation` 1.0.0;
- Compose BOM 2026.06.00;
- Navigation 3 stable 1.1.4;
- mobile Material components не используются вместо TV variants для focusable controls.

## Build toolchain

- AGP 9.3 release notes: https://developer.android.com/build/releases/agp-9-3-0-release-notes
- AGP roadmap: https://developer.android.com/build/releases/gradle-plugin-roadmap
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Kotlin 2.4: https://kotlinlang.org/docs/whatsnew24.html

Зафиксировано:

- AGP 9.3.0;
- Gradle 9.5.0;
- JDK 17;
- compile/target SDK 37;
- Kotlin 2.4.10;
- deprecated legacy Variant APIs не используются.

## Playback

- Media3 releases: https://developer.android.com/jetpack/androidx/releases/media3
- HLS with Media3: https://developer.android.com/media/media3/exoplayer/hls

Зафиксировано: Media3 1.10.1 как production baseline; preview 1.11 не используется в stable builds.

## Storage и background work

- Room 3 releases: https://developer.android.com/jetpack/androidx/releases/room3
- WorkManager releases: https://developer.android.com/jetpack/androidx/releases/work
- DataStore releases: https://developer.android.com/jetpack/androidx/releases/datastore

Зафиксировано:

- Room 3.0.0 для новой schema и KMP-compatible core;
- WorkManager 2.11.2 для deferrable reliable jobs;
- DataStore 1.2.1 для небольших transactional settings.

## DI, networking и images

- AndroidX Hilt releases: https://developer.android.com/jetpack/androidx/releases/hilt
- Dagger releases: https://github.com/google/dagger/releases
- Coil changelog: https://coil-kt.github.io/coil/changelog/
- Ktor releases: https://ktor.io/docs/releases.html
- OkHttp documentation: https://square.github.io/okhttp/

Зафиксировано: Dagger/Hilt 2.59.2, AndroidX Hilt 1.4.0, Coil 3.5.0, Ktor 3.5.1. OkHttp pin проверяется при scaffold вместе с dependency graph Media3/Coil/Ktor.

## Kotlin Multiplatform

- Google KMP guidance: https://developer.android.com/kotlin/multiplatform
- Android-KMP plugin: https://developer.android.com/kotlin/multiplatform/plugin
- Recommended KMP structure: https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html

Зафиксировано:

- native TV app module отделён от shared library modules;
- используется `com.android.kotlin.multiplatform.library` для KMP Android targets;
- shared UI не является целью;
- platform entry points находятся в отдельных modules.

## Rust

- Rust Android targets: https://doc.rust-lang.org/rustc/platform-support/android.html
- UniFFI guide: https://mozilla.github.io/uniffi-rs/latest/

Зафиксировано: Android Rust targets — Tier 2; UniFFI поддерживает Kotlin bindings, но не решает packaging. Rust остаётся optional optimization path.

## Performance

- Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
- Create Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Benchmark releases: https://developer.android.com/jetpack/androidx/releases/benchmark

Зафиксировано: Macrobenchmark 1.4.1 и ProfileInstaller 1.4.1; Baseline/Startup Profiles обязательны для critical user journeys, включая sideloaded APK.