---
status: accepted
last_reviewed: 2026-07-19
owners: [architecture, platform, performance]
---

# Technology and library evaluation

## 1. Decision criteria

Technologies are evaluated against MuxTV-specific risks:

```text
TV remote/focus/accessibility
Android TV/Fire TV lifecycle and platform APIs
live playback/codec/device integration
weak-device startup/memory/jank
large M3U/XMLTV/database workloads
sideload/update/signing
security and maintenance surface
build/CI/reproducibility
future portability
contributor/agent comprehensibility
```

Cross-platform reuse is valuable only after core Android TV quality and a real second target exist.

## 2. Kotlin + native Compose for TV — selected baseline

### Strengths

- first-class Android APIs/lifecycle/Media3/MediaSession/WorkManager/Room/PackageInstaller;
- official TV components/focus/accessibility samples;
- direct Fire TV/AOSP compatibility without extra rendering runtime;
- mature coroutines/Flow and Android tooling;
- best path for TV launcher, input, audio, surface, storage and updater integration;
- profiling/benchmark/baseline profiles integrated with platform;
- no Flutter/web bridge around critical remote/player paths.

### Risks

- Compose TV/focus APIs continue evolving;
- careless recomposition/images/animation can hurt weak TVs;
- Gradle/KSP/Hilt/Room complexity;
- Android-specific UI/storage must be reimplemented for future platforms.

### Controls

- pin stable releases;
- own Design System/focus tests;
- pure Kotlin contracts around platform libraries;
- minimal module graph/convention plugins;
- physical weak/Fire device gates;
- no premature shared UI.

## 3. Rust

### Good use cases

- CPU/memory-sensitive parser/matching/fingerprinting after measurement;
- fuzzable pure compute with stable byte/DTO boundary;
- potential server/desktop reuse later.

### Poor baseline use cases

- Android TV UI/focus/accessibility;
- MediaSession/audio/surface/lifecycle/package install;
- Room/WorkManager integration;
- app-wide orchestration/state;
- early contributors/debugging.

### Costs

- NDK/cargo/cross-ABI packaging;
- UniFFI binding/version/error/cancellation design;
- larger build/supply-chain/native crash surface;
- APK size/startup impact;
- harder profiler/symbol distribution;
- Android Rust targets not equal first-class Java/Kotlin framework APIs.

### Decision

No Rust in Phase 00–03 by default. Adopt per-operation only if benchmark demonstrates at least one meaningful threshold (initial candidate: >=25% CPU or >=30% peak-memory improvement) and startup/size/diagnostics/security gates pass ADR.

## 4. Flutter

### Strengths

- rapid polished custom UI;
- one Dart UI across mobile/desktop;
- active ecosystem;
- possible mpv/native plugins.

### Risks for MuxTV

- TV focus/remote behavior largely app-managed rather than Compose TV-native;
- Media3/MediaSession/PackageInstaller/TV launcher/device capability require plugins/platform channels;
- plugin quality/lifecycle varies;
- extra engine/runtime memory/startup on weak TVs;
- TV and desktop reuse can encourage lowest-common-denominator architecture;
- complex EPG/player surfaces still require native tuning.

### Decision

Rejected as primary TV client. May be reconsidered for a future phone/desktop companion with independent product requirements, not to share TV UI.

## 5. Compose Multiplatform

### Strengths

- Kotlin reuse and familiar Compose concepts;
- desktop/iOS possibilities;
- shared design primitives.

### Risks

- Android TV uses specialized Compose for TV components/focus/platform integration;
- shared UI can constrain TV-specific UX;
- additional targets/build matrix before product need;
- player/storage/updater remain platform-specific.

### Decision

No shared TV UI. Design tokens/domain logic may inspire other clients, but each client has native interaction architecture. Revisit only after second client is approved.

## 6. C#/.NET MAUI/Avalonia

### Strengths

- strong language/tooling and possible desktop reuse;
- Avalonia useful for future desktop control/server UI;
- .NET domain/services suitable for companion/backend if later approved.

### Android TV risks

- weaker official Android TV/focus/Media3 ecosystem;
- native platform bindings still required;
- deployment/runtime/AOT/package complexity;
- limited evidence from mature TV IPTV clients compared with Kotlin Android;
- risks optimizing for developer familiarity over platform fit.

### Decision

Not TV client baseline. C#/.NET remains viable for optional desktop companion/server/tooling, with separate repository/module architecture after product approval.

## 7. React Native / Expo

### Strengths

- rapid mobile UI and JS ecosystem;
- phone companion potential.

### Risks

- Android TV support/focus/native media integration not the primary supported path;
- bridge/runtime overhead and native modules around core TV features;
- dependency churn;
- large EPG/player/lifecycle require substantial native code.

### Decision

Rejected for TV client. Could be evaluated for phone companion only against native/KMP/Flutter at that time.

## 8. Web/PWA/Electron/Tauri

### Web/PWA

Useful for bundled local phone control panel. Poor TV runtime due to browser codec/CORS/network/storage/focus/system integration limitations.

### Electron

Rejected on TV: heavy desktop runtime and not Android TV-native.

### Tauri

Potential future desktop companion; Rust/webview adds value for small desktop app, but unrelated to Android TV core.

### Decision

Use static web UI only inside paired local-control server. No webview-based TV application.

## 9. Media engine

### Media3 — baseline

Strengths:

- official Android playback/session/lifecycle ecosystem;
- HLS/DASH/RTSP/progressive support;
- hardware codec integration;
- tracks/live timeline/errors;
- Compose/player/session tooling;
- security and updates through AndroidX.

Risks:

- device codecs lie/fail;
- unusual containers/audio/subtitles may fail;
- retry/player APIs can leak if not isolated.

Controls: `PlaybackEngine`, device evidence, error mapping, fixtures/physical matrix.

### libmpv/mpv-android — optional compatibility

Strengths: broad format/subtitle/decoder behavior and useful comparison baseline.

Costs: native build/ABI/size/crash/security, non-turnkey embedding, separate lifecycle/event semantics.

Decision: optional compatibility flavor only after corpus demonstrates significant coverage gap.

### VLC/libVLC

Similar optional compatibility class. Not selected because another large native engine would multiply ABI/security/behavior maintenance. Evaluate only against libmpv with measured stream corpus if Media3 gaps demand fallback.

### FFmpeg extensions

May solve audio codec gaps but add native licensing/build/ABI/security surface. Add specific decoder extension, not full general dependency, after device/corpus evidence and license review.

## 10. Database and persistence

### Room/SQLite — selected

- schema/DAO/migration tooling;
- transactions/WAL/indexes/FTS;
- Android instrumentation;
- local-first durability.

Risks: Room 3 is new; KSP/plugin/API migration; huge EPG requires careful queries/transactions.

Decision: Android-first Room behind repositories. Prototype version integration; no destructive fallback; schema/migration/performance tests.

### SQLDelight

Strengths: strong SQL-first/KMP story, generated APIs.

Risks: extra tooling/migration/query architecture choice without second platform; Android Room ecosystem benefits lost.

Decision: rejected baseline, revisit only if Room blocks required portability/performance.

### Realm/Object stores

Rejected: less transparent SQL/FTS/migration/control for catalog/EPG and unnecessary dependency/format lock-in.

## 11. Networking

### OkHttp — selected baseline

- mature Android HTTP/TLS/interceptors/cache/cancellation;
- MockWebServer tests;
- compatible with many ecosystem libraries.

Own policies required for SSRF/private IP/redirect credentials/resource limits; OkHttp alone is not security policy.

### Ktor client

Not needed if OkHttp already baseline. Avoid dual client stacks without clear platform reuse. Ktor server remains for embedded local-control.

## 12. Dependency injection

### Hilt/Dagger — provisional selected baseline

Strengths: Android lifecycle integration, compile-time graph, common ecosystem.

Costs: generated code/build complexity, Android-centric, can hide composition.

Controls:

- use only composition root/adapters;
- domain remains constructor-injected/plain;
- avoid entry-point proliferation;
- benchmark build/startup and test overrides in Phase 00.

### Manual DI

Simpler for small app and transparent, but graph grows with providers/player/background work. Kept as fallback if Hilt prototype adds disproportionate complexity.

### Metro/kotlin-inject

Interesting alternatives, especially KMP/codegen, but not selected without Android TV production maturity/toolchain evidence. Revisit by ADR only if Hilt causes measured problems.

## 13. Images

### Coil — selected

Kotlin/Compose integration, request sizing/caching. Still requires MuxTV ImageClient/security/dimension policy.

Avoid loading original multi-megapixel images for cards/backgrounds. SVG only with safe renderer/no external resources.

## 14. Background work

### WorkManager — selected for deferrable durable jobs

Good for periodic refresh/cleanup and retryable jobs. Not an unlimited execution environment. Large user-initiated download/import may need foreground/user-initiated data transfer approach; parser work checkpointed.

Avoid layering WorkManager retries over independent unlimited provider/network retries.

## 15. Local control server

### Ktor embedded — selected provisional

- Kotlin server/router/websocket/SSE;
- bundled static phone UI;
- integrates with app use cases.

Risks: server/network attack surface and memory/lifecycle. Mitigated by ephemeral activation, pairing, capability middleware, limits and no generic proxy.

Alternatives such as NanoHTTPD are smaller but less structured; revisit if Ktor size/startup measured excessive.

## 16. Serialization

### kotlinx.serialization

Selected for internal/versioned DTO/backup/local API metadata where supported. Schemas/version fields and bounded parsing still required. Do not serialize Room/entities directly as public contract.

XMLTV uses secure streaming pull parser rather than mapping entire XML through object serializer.

## 17. Logging/observability

Prefer small internal structured facade + bounded ring buffer/redactor. Timber may be used as Android sink, not domain contract. No mandatory cloud analytics/crash SDK. OpenTelemetry is excessive for MVP unless local/export use case emerges.

## 18. Testing libraries

- JUnit/kotlin test according module;
- kotlinx-coroutines-test;
- Truth or Kotlin assertions, one consistent baseline;
- MockWebServer;
- Room migration/instrumentation;
- Compose UI/TV semantics/screenshot tests;
- Macrobenchmark/Baseline Profiles/Perfetto;
- property/fuzz tests for parsers/redaction/matching;
- test server/fault proxy.

Avoid accumulating multiple assertion/mocking frameworks without need. Prefer fakes over deep mocking for domain/player/provider contracts.

## 19. Library adoption gate

Before adding dependency:

1. exact MuxTV requirement;
2. official current release/docs;
3. maintenance/security/license review;
4. size/startup/build/transitive impact;
5. Android TV/Fire compatibility;
6. existing platform/JDK implementation alternative;
7. isolation boundary;
8. tests/upgrade owner;
9. version catalog entry;
10. ADR if architecture/native/public contract affected.

## 20. Final stack baseline

```text
TV app: Kotlin + Android SDK + Compose for TV + Navigation 3
Playback: Media3 behind PlaybackEngine
State: Coroutines/Flow, immutable UDF
Storage: Android-first Room/SQLite
Network: OkHttp with MuxTV policy
Images: Coil with bounded safe image pipeline
DI: Hilt provisional, plain constructor injection below composition root
Background: WorkManager + appropriate foreground/user-initiated mechanisms
Local phone setup: embedded Ktor + bundled static web UI
Serialization: kotlinx.serialization; secure pull XML parser
Testing: JVM/instrumentation/screenshot/Macrobenchmark/Perfetto/fuzz corpus
Rust/libmpv/KMP DB: evidence-gated optional future paths
```

This stack optimizes the actual first product—reliable Android TV/Fire TV—while preserving replaceable contracts rather than paying cross-platform/native complexity in advance.