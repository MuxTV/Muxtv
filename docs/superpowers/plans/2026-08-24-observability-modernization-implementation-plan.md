# MuxTV Observability Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the highest-value latest-stack observability gaps without contaminating U0 UI characterization or introducing speculative performance changes before trustworthy measurements exist.

**Architecture:** Preserve typed subsystem ownership. WorkManager, Tracing, OkHttp and Media3 each expose bounded evidence through narrow contracts; Doctor receives only coarse secret-safe projections. No universal raw telemetry bus is introduced. Observability preparation can proceed in parallel with U0/U1/M0, but DB/player/parser/UI tuning decisions remain evidence-gated.

**Tech Stack:** Kotlin 2.4.10, Coroutines/Flow, WorkManager 2.11.2, AndroidX Tracing 2.0.0, OkHttp 5.3, Media3 1.11 candidate, Benchmark 1.5 RC candidate, AGP 9.4 candidate, Gradle 9.7.1 candidate, JUnit4, Truth, MockWebServer3, PowerShell 7, Perfetto/AndroidX Benchmark, self-hosted Windows runner.

**Spec:** `docs/superpowers/specs/2026-08-24-observability-modernization-design.md`.

**Governing stabilization plan:** `docs/superpowers/plans/2026-08-22-muxtv-stabilization-master-plan.md`.

## Global constraints

- [ ] Do not modify PR #189 U0 characterization code, marker, A/B/C refs or dependency baseline as part of O-work.
- [ ] Do not merge combined stack staging #190 as a dependency mega-PR; use it only to discover compatibility failures, then split accepted versions.
- [ ] Persistent AVDs are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- [ ] Do not create a low-RAM/mainstream/720p/benchmark/measurement AVD.
- [ ] Do not generalize emulator evidence to weak ARM, thermal, vendor MediaCodec, HDR or passthrough behavior.
- [ ] Every production/configuration package uses RED -> observed expected failure -> minimal GREEN -> exact-head verification.
- [ ] With no executable host/runner, documentation/issue work may proceed but a production GREEN implementation must not be committed as “TDD complete” before the corresponding RED is actually observed.
- [ ] No raw URL/path/query/header/token/title/exception text may cross an observability boundary.
- [ ] Observability failure must not change product/network/player/worker outcome.

---

## Task O0 — Durable coordination and truth sync (#194)

**Files:**

- `docs/superpowers/specs/2026-08-24-observability-modernization-design.md`
- `docs/superpowers/plans/2026-08-24-observability-modernization-implementation-plan.md`
- `.work/CURRENT-STATE.md`
- `.work/meta/status.yaml`
- `.work/ROADMAP.md`

### O0.1 Record accepted baseline

- [ ] Set reviewed accepted main to `5aa9c108cc63187d8066494fb30c73b82f4e0f97` / PR #181.
- [ ] Record PR #175 single service-owned seek authority as accepted; remove #132 dual-ownership from current gaps.
- [ ] Record D0 exact two-AVD contract as accepted infrastructure truth.
- [ ] Keep dynamic open PR/issue state outside durable truth except as explicitly labeled execution references.

### O0.2 Record current execution topology

- [ ] State U0 (#189) -> U1 -> M0 (#178) as the stabilization critical path.
- [ ] State O1/O2/O3 as parallel host-first evidence preparation that cannot authorize performance tuning before the applicable measurement gate.
- [ ] Link dedicated owners #191, #192, #193, #195, #196.
- [ ] Mark #190 as a staging candidate, not accepted stack truth.

### O0.3 Verify docs-only branch

Run when checkout is available:

```powershell
pwsh -NoProfile -File .\tools\ci\Get-RepositoryLiveState.ps1
git diff --check main...HEAD
git diff --name-only main...HEAD
```

Expected: docs/spec/plan/truth files only.

**Commit:** `docs(truth): sync D0 seek and observability execution baseline`

---

## Task O1 — WorkManager typed failure diagnostics (#191)

**Why first:** No dependency upgrade is required; surface is small; it closes an actual alpha blind spot; it does not require DB schema or player/UI behavior changes.

### O1.1 Pure diagnostic contract RED

**Create:**

- `core/common/src/main/kotlin/app/muxtv/common/BackgroundWorkFailure.kt`
- `core/common/src/test/kotlin/app/muxtv/common/BackgroundWorkFailureTest.kt`

Define platform-neutral types only:

```kotlin
internal enum class BackgroundWorkFailureKind {
    INITIALIZATION,
    SCHEDULING,
    WORKER_INITIALIZATION,
    WORKER_EXECUTION,
}

data class BackgroundWorkFailureObservation(
    val kind: BackgroundWorkFailureKind,
    val timestampEpochMillis: Long,
    val safeWorkerCategory: String? = null,
)
```

Do **not** add throwable/message/url/header fields.

Tests must prove:

- [ ] only the four stable kinds exist;
- [ ] worker category is bounded/normalized or rejected if arbitrary content is allowed;
- [ ] observation equality/order is deterministic;
- [ ] there is no raw exception/string payload field that can bypass redaction.

Run RED:

```powershell
.\gradlew.bat :core:common:test --tests "*BackgroundWorkFailureTest" --no-daemon --console=plain --stacktrace
```

Expected RED: missing production contract or failed security/bounds assertion for the intended reason.

Do not continue until the RED is observed and its failure signature recorded.

### O1.2 Bounded process-local store RED -> GREEN

**Create:**

- `core/common/src/main/kotlin/app/muxtv/common/BackgroundWorkFailureStore.kt`
- `core/common/src/test/kotlin/app/muxtv/common/BackgroundWorkFailureStoreTest.kt`

Contract:

- bounded fixed capacity;
- deterministic oldest-drop behavior;
- thread-safe snapshot/record;
- recorder failure cannot throw through framework callback usage;
- no disk/Room dependency.

RED tests:

- [ ] capacity `N` retains exactly newest `N`;
- [ ] ordering is newest/oldest exactly as Doctor expects;
- [ ] concurrent record/snapshot does not expose mutable backing state;
- [ ] a safe wrapper catches sink exceptions.

Run:

```powershell
.\gradlew.bat :core:common:test --tests "*BackgroundWorkFailureStoreTest" --no-daemon --console=plain --stacktrace
```

Minimal GREEN only after observed RED.

### O1.3 WorkManager configuration factory RED -> GREEN

**Create:**

- `app/tv/src/main/kotlin/app/muxtv/MuxTvWorkManagerConfigurationFactory.kt`
- `app/tv/src/test/kotlin/app/muxtv/MuxTvWorkManagerConfigurationFactoryTest.kt`

**Modify:**

- `app/tv/src/main/kotlin/app/muxtv/MuxTvApplication.kt`

Factory responsibilities:

- install `HiltWorkerFactory`;
- install all four stable failure handlers;
- map framework callbacks to `BackgroundWorkFailureKind`;
- never persist raw `Throwable` or `WorkerExceptionInfo` content;
- swallow diagnostic-recorder failure at this boundary;
- leave WorkManager task executor/coroutine context/retry semantics untouched.

RED tests should make a fake recorder throw and prove handler invocation itself remains non-throwing. Prefer exposing handler construction as testable pure functions instead of relying on reflection into `Configuration` internals.

Run:

```powershell
.\gradlew.bat :app:tv:testDebugUnitTest --tests "*MuxTvWorkManagerConfigurationFactoryTest" --no-daemon --console=plain --stacktrace
```

### O1.4 Doctor coarse projection RED -> GREEN

**Modify/create only after O1.1–O1.3 are GREEN:**

- `feature/doctor/build.gradle.kts` — add `:core:common` only if required by the final projection contract;
- `feature/doctor/src/main/kotlin/app/muxtv/feature/doctor/DoctorRoute.kt`;
- `feature/doctor/src/main/kotlin/app/muxtv/feature/doctor/DoctorReportFormatter.kt`;
- `feature/doctor/src/test/kotlin/app/muxtv/feature/doctor/DoctorReportFormatterTest.kt`;
- `feature/doctor/src/test/kotlin/app/muxtv/feature/doctor/DoctorExportPolicyTest.kt` only if export gating changes;
- `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt` or the existing app composition boundary that supplies Doctor dependencies.

Do not merge WorkManager data into `PlaybackObservation`. Doctor should render a separate background-work section/projection with stable Russian remediation text.

Security RED fixture must include an exception such as:

```text
https://user:secret@provider.example/list.m3u?token=DO_NOT_EXPORT Authorization=Bearer DO_NOT_EXPORT
```

and prove none of those tokens appear in formatted output.

Run:

```powershell
.\gradlew.bat :feature:doctor:testDebugUnitTest :app:tv:testDebugUnitTest --no-daemon --console=plain --stacktrace
```

### O1.5 Host/device acceptance

Host exact-head:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
```

Later device acceptance:

- [ ] API36 `MuxTV_TV_CURRENT_API36` startup/refresh smoke;
- [ ] API26 `MuxTV_TV_OLD_API26` compatibility smoke;
- [ ] no additional AVD identity.

**Suggested commits:**

1. `test(diagnostics): define background work failure contract`
2. `feat(diagnostics): add bounded background work failure store`
3. `feat(work): observe WorkManager framework failures safely`
4. `feat(doctor): project background work failures safely`

---

## Task O2 — AndroidX Tracing 2.0 boundary (#192)

### O2.1 Dependency/module feasibility RED

Preferred ownership is an isolated lightweight Kotlin/JVM module so `core:common` stays free of observability implementation dependencies.

**Candidate files:**

- `settings.gradle.kts` — add `:core:observability`;
- `core/observability/build.gradle.kts` — `muxtv.kotlin.library` first;
- `gradle/libs.versions.toml` — isolated `tracing = "2.0.0"` final merge unit, not folded blindly into #190;
- `core/observability/src/test/kotlin/app/muxtv/observability/TracingDependencyContractTest.kt`.

First prove AndroidX Tracing 2.0 resolves/compiles for the repository JVM module. If its selected artifact/variant cannot satisfy the Kotlin library boundary, stop and use an Android adapter module; do not contaminate `core:common` merely to force resolution.

Run:

```powershell
.\gradlew.bat :core:observability:test --no-daemon --console=plain --stacktrace
```

### O2.2 Static name/attribute policy RED -> GREEN

**Create:**

- `core/observability/src/main/kotlin/app/muxtv/observability/MuxTvTrace.kt`
- `core/observability/src/main/kotlin/app/muxtv/observability/MuxTvTraceName.kt`
- `core/observability/src/test/kotlin/app/muxtv/observability/MuxTvTracePolicyTest.kt`

Use static span names from the design spec. Prefer typed/numeric/enum attributes; do not expose a generic arbitrary `Map<String,String>` metadata API.

RED tests:

- [ ] static taxonomy exactly matches approved names;
- [ ] disabled/no-op path produces no product-visible side effect;
- [ ] secret-bearing arbitrary strings cannot be attached through public wrapper API;
- [ ] trace adapter exceptions do not escape product call sites.

### O2.3 Instrument only adapter boundaries

**Initial candidate files:**

- source refresh/import coordinator owner in `catalog/refresh` / `catalog/importer` after exact call-site inspection;
- `core/database/src/main/kotlin/app/muxtv/database/RoomChannelSearchRepository.kt`;
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`;
- first-frame/rebuffer/seek helpers only where one semantic owner exists.

Do not instrument pure domain policies or every DAO/function.

Use coroutine-aware spans where the operation meaningfully crosses suspension/thread boundaries.

Run focused module tests, then Fast validation.

### O2.4 Benchmark integration

After Benchmark 1.5 split is buildable:

- [ ] benchmark/debug captures include target in-process traces;
- [ ] merge output shows stable `MuxTv.*` spans;
- [ ] production does not persist trace files by default;
- [ ] exact trace capture is evidence only, not product state.

**Suggested commits:**

1. `test(observability): define trace policy contract`
2. `feat(observability): add Tracing 2 boundary`
3. `feat(observability): trace catalog search and player boundaries`
4. `test(benchmark): merge MuxTV in-process traces`

---

## Task O3 — OkHttp phase timing (#193)

### O3.1 Timing model RED -> GREEN

**Create:**

- `core/network/src/main/kotlin/app/muxtv/network/NetworkTimingObservation.kt`
- `core/network/src/test/kotlin/app/muxtv/network/NetworkTimingObservationTest.kt`

Use monotonic elapsed-nanos/millis for durations. Wall clock is optional report timestamp only.

Typed phases only. No raw Request/HttpUrl/Throwable is retained by the observation.

Tests:

- [ ] negative/out-of-order duration is rejected or safely omitted;
- [ ] missing phases are represented explicitly, not fabricated as zero;
- [ ] source/playback policy is part of observation context without a locator;
- [ ] no raw secret-bearing string field exists.

### O3.2 EventListener RED -> GREEN with MockWebServer

**Create:**

- `core/network/src/main/kotlin/app/muxtv/network/MuxTvNetworkTimingEventListener.kt`
- `core/network/src/test/kotlin/app/muxtv/network/MuxTvNetworkTimingEventListenerTest.kt`

**Modify:**

- `core/network/src/main/kotlin/app/muxtv/network/MuxTvHttpResources.kt`
- `core/network/src/test/kotlin/app/muxtv/network/MuxTvHttpResourcesTest.kt`

Tests with MockWebServer3:

- [ ] successful source request emits total + available DNS/connect/TTFB/body phases;
- [ ] reused connection does not fabricate a new connect/TLS duration;
- [ ] failure emits typed terminal state without raw exception message;
- [ ] redirect/security interceptors keep existing behavior;
- [ ] listener/recorder exception does not change HTTP call result;
- [ ] base/source/playback clients still share dispatcher and connection pool;
- [ ] URL/header fixture contains secrets and snapshot/report contains none.

Run:

```powershell
.\gradlew.bat :core:network:testDebugUnitTest --no-daemon --console=plain --stacktrace
```

### O3.3 Playback flood protection

Add explicit policy before enabling on the Media3 datasource client:

- disabled, aggregate, or fixed bounded sampling by default;
- no unbounded per-segment durable rows;
- aggregation keys cannot contain raw locator/path/title;
- counters/durations reset on bounded session/interval.

Write burst test with hundreds/thousands of synthetic segment events and prove memory/record count remains bounded.

### O3.4 #100 integration

#193 remains observational. #100 owns validators and 304 behavior.

When #100 executes, compare:

```text
200 unchanged -> network body -> parse -> stage -> DB/WAL -> publication
304           -> headers -> NOT_MODIFIED -> no parse/stage/new revision
```

using the same network timing model.

**Suggested commits:**

1. `test(network): define secret-safe timing model`
2. `feat(network): record OkHttp phase timings`
3. `fix(network): bound playback timing volume`

---

## Task O4 — Media3 analytics evidence prerequisite (#109/#27)

Do this after the Media3 1.11 isolated dependency package is buildable. Preserve one service-owned ExoPlayer.

**Create/modify candidates:**

- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackAnalyticsObserver.kt`
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackAnalyticsObserverTest.kt`
- existing player measurement scripts/tests under `tools/android` / `tools/measurements` only after exact ownership inspection.

RED model tests first for:

- [ ] first-frame duration;
- [ ] rebuffer start/end pairing and count;
- [ ] seek-resume correlation only for current generation;
- [ ] selected width/height/bitrate/coarse format without locator/title;
- [ ] dropped-frame/audio-underrun counters where exposed;
- [ ] stale callback rejection;
- [ ] bounded retention;
- [ ] raw Media3 events are not persisted.

Do **not** change `LoadControl`, back buffer or cache in O4.

Device acceptance later uses API26/API36 canonical AVDs plus physical weak/current TV for hardware claims.

---

## Task O5 — R8 Configuration Analyzer evidence (#31)

No new R8 issue. #31 owns release hardening.

### O5.1 Static task/evidence contract

**Candidate new files:**

- `tools/release/Invoke-R8ConfigurationAnalysis.ps1`
- `tools/release/Test-R8ConfigurationAnalysisContract.ps1`

Before hardcoding task name, inspect tasks on the accepted AGP package:

```powershell
.\gradlew.bat :app:tv:tasks --all --no-daemon --console=plain | Select-String "analyze.*R8Config"
```

Expected candidate: `:app:tv:analyzeReleaseR8Config`.

Runner script should:

- execute standalone analyzer;
- fail if report is absent;
- copy/report exact commit + AGP/R8/Java/Gradle metadata;
- never parse scores as pass/fail budgets on first run;
- archive HTML/proto/text summary as descriptive evidence.

### O5.2 Review before keep-rule changes

- [ ] record shrinking/optimization/obfuscation scores;
- [ ] identify broad keep radius/global disables/duplicates;
- [ ] if a keep rule needs narrowing, use a separate RED/GREEN change with release regression tests;
- [ ] do not delete keep rules automatically.

Official standalone analyzer requires AGP 9.3+; current main and staged AGP both satisfy that requirement.

---

## Task O6 — Benchmark/Baseline Profile real TV CUJs (#31/#27)

**Modify:**

- `benchmark/macrobenchmark/src/main/kotlin/app/muxtv/benchmark/MuxTvCriticalUserJourneys.kt`
- `benchmark/macrobenchmark/src/main/kotlin/app/muxtv/benchmark/MuxTvMacrobenchmarks.kt`
- `benchmark/macrobenchmark/src/main/kotlin/app/muxtv/benchmark/BaselineProfileGenerator.kt`

Add deterministic journeys separately so failures are attributable:

1. Channels 50–100 D-pad moves with paging/focus boundary;
2. Search deterministic query + result traversal + open/Back;
3. Guide vertical/horizontal window traversal;
4. Player start -> first frame -> bounded zaps;
5. bounded semantic seek burst through service authority.

Do not mix all journeys into one test method.

Metrics:

- startup;
- frame p50/p95/p99 where Benchmark reports them;
- jank/frame distributions;
- first-frame/player spans from O2/O4;
- memory/soak only with a separately reproducible methodology.

No third benchmark AVD; use `MuxTV_TV_CURRENT_API36` for current-device benchmark work and API26 only where compatibility evidence is required.

---

## Task O7 — Measurement-gated Room experiments (#196)

**Hard dependency:** #178/M0 accepted.

No production Room change before the following baseline exists:

- query p50/p95;
- `EXPLAIN QUERY PLAN`;
- actual journal mode;
- transaction duration;
- connection wait/hold where observable;
- read concurrency demand;
- M3U/EPG publication contention;
- DB/WAL size;
- `SQLITE_BUSY`/retry incidence;
- Search quality on 1k/10k/50k deterministic corpus.

Then run **separate** experiments:

- current default vs explicit pool candidate;
- FTS4 Unicode61 vs FTS5 candidate(s);
- table-specific `WITHOUT ROWID` candidate.

A valid conclusion is to keep the current default.

---

## Task O8 — Gradle 9.7 build experiments (#195)

**Priority:** post-alpha / non-blocking.

After the toolchain split is accepted:

- [ ] baseline repeated configuration-cache create/reuse timings;
- [ ] test `org.gradle.configuration-cache.parallel=true` separately;
- [ ] test Isolated Projects separately;
- [ ] inspect `build-logic` cross-project model access;
- [ ] audit zero-Kotlin Android modules before considering local `enableKotlin=false`;
- [ ] 20–30 comparable runs where practical;
- [ ] keep only deterministic material wins.

Do not enable incubating build behavior in #190.

---

## Runner return sequence

When the self-hosted runner is available again, do not run everything blindly. Use this order:

1. [ ] #189 exact current head / U0 admission and one-shot characterization according to its frozen plan.
2. [ ] #190 combined stack compatibility candidate: host compile/tests first; diagnose exact failing dependency/toolchain boundary.
3. [ ] Split accepted stack versions into independent merge units per #179/#146 rather than merging #190 wholesale.
4. [ ] For #191, execute the first RED contract; only after observed RED commit minimal GREEN and continue O1.
5. [ ] Repeat RED/GREEN for #192 and #193.
6. [ ] Restack/finish #178 M0 before trusting DB performance conclusions.
7. [ ] Add O4 player analytics and O5/O6 release/Benchmark evidence.
8. [ ] Execute #100 304 and compare end-to-end cost.
9. [ ] Only then choose measured optimization work (#109/#196/parser/Compose).

## Definition of done for the observability train

The train is complete for MVP alpha when:

- WorkManager framework failures have bounded typed secret-safe observations;
- source network timing can separate DNS/connect/TLS/TTFB/body/total without secrets;
- catalog/search/player critical operations emit stable in-process trace evidence;
- Media3 evidence can quantify first-frame/rebuffer/seek-resume before LoadControl decisions;
- R8 analyzer output is part of release evidence;
- Macrobenchmark/Baseline Profile covers sustained deterministic TV interaction, not only screen opens;
- Doctor exposes only coarse actionable projections, never raw telemetry;
- no observability subsystem changes functional outcomes or creates unbounded storage/event volume;
- no third AVD exists;
- Room/build/runtime optimizations are accepted only from reproducible evidence.
