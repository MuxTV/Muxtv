---
status: accepted
last_reviewed: 2026-07-19
scope: official-primary-sources
---

# Официальные источники архитектуры

Проверено 19 июля 2026 года. При обновлении stack сначала проверяются official release notes/guides/source repositories, затем меняется `meta/dependencies.yaml`; изменение architecture/runtime behavior требует ADR и regression evidence.

## Android TV, UI, focus and accessibility

- Compose for TV releases: https://developer.android.com/jetpack/androidx/releases/tv
- Compose on Android TV: https://developer.android.com/training/tv/playback/compose
- Compose for TV introduction: https://developer.android.com/codelabs/compose-for-tv-introduction
- TV design/navigation guidance: https://developer.android.com/design/ui/tv
- Build adaptive TV layouts: https://developer.android.com/training/tv/playback/compose/layouts
- Navigation 3 releases: https://developer.android.com/jetpack/androidx/releases/navigation3
- Android TV samples: https://github.com/android/tv-samples

Зафиксировано:

- Compose for TV — основной framework;
- stable `tv-material` 1.1.0 и `tv-foundation` 1.0.0;
- Compose BOM 2026.06.00;
- Navigation 3 stable 1.1.4;
- mobile Material controls не заменяют TV variants для focusable UI;
- D-pad/focus/accessibility и 10-foot layout являются release gates;
- official samples используются для API/behavior, но не как full product architecture.

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
- deprecated legacy Variant APIs не используются;
- wrapper/toolchain/dependencies pinned for reproducibility.

## Playback and media session

- Media3 releases: https://developer.android.com/jetpack/androidx/releases/media3
- AndroidX Media source: https://github.com/androidx/media
- HLS with Media3: https://developer.android.com/media/media3/exoplayer/hls
- Live streaming: https://developer.android.com/media/media3/exoplayer/live-streaming
- MediaSession: https://developer.android.com/media/media3/session/control-playback
- Audio focus: https://developer.android.com/media/optimize/audio-focus

Зафиксировано:

- Media3 1.10.1 production baseline; preview versions not used in stable builds;
- `ERROR_CODE_BEHIND_LIVE_WINDOW` and live-edge recovery handled explicitly;
- player/session lifetime belongs to service/controller, not Activity/Composable;
- Media3 types do not leak through MuxTV domain/feature contracts;
- runtime codec/device evidence supplements advertised capability.

## Storage, Room and background work

- Room 3 releases: https://developer.android.com/jetpack/androidx/releases/room3
- Room migration testing: https://developer.android.com/training/data-storage/room/migrating-db-versions
- WorkManager releases: https://developer.android.com/jetpack/androidx/releases/work
- Long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- User-initiated data transfer: https://developer.android.com/develop/background-work/background-tasks/uidt
- DataStore releases: https://developer.android.com/jetpack/androidx/releases/datastore

Зафиксировано:

- Room 3.0.0 may be used after scaffold prototype;
- database remains Android-first behind repository ports until second-client evidence (ADR-0003);
- schema export and migration tests mandatory;
- WorkManager for deferrable reliable jobs, but large user-initiated transfer/refresh must respect execution quotas and foreground/user-initiated alternatives;
- parser/import jobs checkpoint and never rely on one unlimited worker;
- DataStore only for small settings, not catalog/EPG.

## Networking and security

- Network Security Configuration: https://developer.android.com/privacy-and-security/security-config
- OWASP XML External Entity Prevention: https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html
- OkHttp documentation: https://square.github.io/okhttp/
- Android cryptography/Keystore: https://developer.android.com/privacy-and-security/keystore
- App security best practices: https://developer.android.com/privacy-and-security/security-best-practices

Зафиксировано:

- per-source runtime network policy supplements manifest Network Security Configuration;
- no global TLS bypass;
- cleartext and LAN/private address access are explicit and scoped;
- sensitive headers stripped on cross-origin redirects by default;
- remote XML disables DTD/external entities/XInclude and uses bounded streaming parse;
- credentials are opaque/redacted and excluded from backup by default.

## DI, local server and images

- AndroidX Hilt releases: https://developer.android.com/jetpack/androidx/releases/hilt
- Dagger releases: https://github.com/google/dagger/releases
- Coil changelog: https://coil-kt.github.io/coil/changelog/
- Ktor releases: https://ktor.io/docs/releases.html
- Ktor server platforms: https://ktor.io/docs/server-platforms.html

Зафиксировано:

- Dagger/Hilt 2.59.2 and AndroidX Hilt 1.4.0 baseline pending scaffold validation;
- Coil 3.5.0 and Ktor 3.5.1 baseline;
- OkHttp pin verified during scaffold against full dependency graph;
- local Ktor server is ephemeral, paired, capability-scoped and not a permanent LAN service;
- image fetch/decode has separate security/resource policy.

## Kotlin Multiplatform

- Google KMP guidance: https://developer.android.com/kotlin/multiplatform
- Android-KMP plugin: https://developer.android.com/kotlin/multiplatform/plugin
- Recommended structure: https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html

Зафиксировано:

- native TV app separated from platform-neutral modules;
- shared UI not a goal;
- pure Kotlin/JVM modules may remain KMP-compatible without forcing KMP build;
- actual KMP target requires second-platform/product need and ADR;
- Room database is not made multiplatform prematurely.

## Rust and native code

- Rust Android targets: https://doc.rust-lang.org/rustc/platform-support/android.html
- UniFFI guide: https://mozilla.github.io/uniffi-rs/latest/
- Android NDK guides: https://developer.android.com/ndk/guides

Зафиксировано:

- Android Rust targets are Tier 2;
- UniFFI generates bindings but does not solve ABI/packaging/crash/update security;
- Rust remains optional compute optimization after benchmark ADR;
- libmpv/FFmpeg/native components require explicit ABI, security, size and device testing.

## Package signing, installation and updates

- apksigner: https://developer.android.com/tools/apksigner
- PackageInstaller: https://developer.android.com/reference/android/content/pm/PackageInstaller
- App signing: https://developer.android.com/studio/publish/app-signing
- Android developer verification FAQ: https://developer.android.com/developer-verification/guides/faq
- GitHub releases: https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
- GitHub Actions security hardening: https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions

Зафиксировано:

- Android signing certificate/lineage is root of in-place update trust;
- PackageInstaller user/system confirmation remains mandatory outside managed owner scenarios;
- updater verifies repo/channel/versionCode/package/hash/certificate before handoff;
- release workflow uses minimal permissions, pinned actions and protected signing;
- GitHub outage/update check never blocks app use;
- package identity fixed before first public APK.

## Fire TV

- Fire TV development overview: https://developer.amazon.com/docs/fire-tv/getting-started-developing-apps-and-games.html
- Fire OS overview/device specifications: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html
- Fire TV remote input: https://developer.amazon.com/docs/fire-tv/remote-input.html

Зафиксировано:

- Fire OS is AOSP-based but not assumed identical to Google TV;
- core app has no mandatory Google Play Services;
- physical Fire TV device tier is a release gate;
- remote, lifecycle, storage, network, codec and accessibility behavior tested separately.

## XMLTV and IPTV format references

- XMLTV project/DTD: https://github.com/XMLTV/xmltv
- Kodi IPTV Simple wiki: https://github.com/kodi-pvr/pvr.iptvsimple/wiki
- Kodi IPTV Simple source: https://github.com/kodi-pvr/pvr.iptvsimple

These are format/de-facto compatibility references, not security policy. Runtime input remains untrusted and bounded; Kodi-specific plugin/inputstream behavior is not executed automatically.

## Performance and profiling

- Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
- Create Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Benchmark releases: https://developer.android.com/jetpack/androidx/releases/benchmark
- Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Perfetto: https://developer.android.com/topic/performance/tracing
- Memory profiling: https://developer.android.com/studio/profile/memory-profiler

Зафиксировано:

- Macrobenchmark 1.4.1 and ProfileInstaller 1.4.1 baseline;
- Baseline/Startup Profiles required for critical journeys including sideloaded APK;
- performance claims name exact device/firmware/network/method/sample count;
- emulator does not prove codec/thermal/Fire TV performance;
- deterministic fixtures plus physical device lab gate release.

## Research hierarchy

1. current official docs/release notes/source;
2. protocol/format canonical projects;
3. mature reference repositories and issues;
4. community reports/benchmarks as hypotheses;
5. MuxTV prototype/tests/measurements as final decision evidence.

Conflicts are recorded rather than resolved by repository popularity.