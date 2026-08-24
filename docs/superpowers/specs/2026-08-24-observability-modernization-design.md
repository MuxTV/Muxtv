# MuxTV Observability Modernization Design

**Status:** approved execution design derived from issue #179 and the 2026-08-24 latest-stack review.

**Baseline:** accepted `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` (PR #181 / D0).

**Governing stabilization plan:** `docs/superpowers/plans/2026-08-22-muxtv-stabilization-master-plan.md`.

## Problem

MuxTV already has strong functional boundaries (revision-based catalog/EPG, service-owned Media3 player, bounded recovery, Doctor Lite, deterministic corpus, Macrobenchmark/Baseline Profile foundation), but several modern stack capabilities are either unused or underused:

- WorkManager 2.11.2 failure callbacks are not wired;
- AndroidX Tracing is present but there is no repository-owned trace boundary;
- OkHttp 5.3 has no EventListener-based phase timing;
- Media3 playback evidence is coarser than required to justify #109 buffer policy;
- AGP 9.3+ R8 Configuration Analyzer is not part of #31 release evidence;
- Macrobenchmark journeys mostly prove screen reachability rather than sustained TV interaction;
- Room3 pool/FTS5/WITHOUT ROWID capabilities are available but currently have no trustworthy decision evidence.

The failure mode to avoid is a generic telemetry subsystem or a wave of speculative performance changes that destroys attribution.

## Core decision

Adopt an **evidence-first, typed-owner model**:

```text
framework/runtime source
        │
        ├── WorkManager -> WorkFailure diagnostics (#191)
        ├── Tracing 2.0 -> MuxTvTrace evidence boundary (#192)
        ├── OkHttp -> NetworkTiming observations (#193)
        └── Media3 -> playback analytics evidence (#109/#27)
                         │
                         ├── Benchmark / Perfetto / #27 evidence
                         └── coarse secret-safe Doctor projection (#30)
```

There is deliberately **no universal raw `TelemetryEvent` bus**. Each subsystem owns typed semantics and exposes only a narrow projection to Doctor/evidence.

## Stabilization relationship

The accepted critical path remains:

```text
D0 accepted -> U0 (#189) -> U1 -> M0 (#178) -> performance/release decisions
```

Observability preparation is a parallel train, not a replacement:

```text
O0 docs/issues
 -> O1 WorkManager diagnostics
 -> O2 Tracing 2.0 boundary
 -> O3 OkHttp timings
 -> O4 playback/R8/Benchmark evidence integration
```

Rules:

- O-work must not modify #189 U0 code, marker, geometry or dependency baseline;
- O-work may be prepared and host-tested before M0;
- O-work cannot authorize Room/Compose/parser/buffer tuning until #178 restores trustworthy measurement semantics where relevant;
- #190 remains a combined compatibility probe and is not the final merge unit for these changes.

## Device contract

Persistent repository-owned Android TV AVD identities are exactly:

- `MuxTV_TV_OLD_API26`
- `MuxTV_TV_CURRENT_API36`

No low-RAM, mainstream, 720p, benchmark, measurement or other AVD identity is permitted. Display/density/stress configurations reuse those devices. Weak-ARM, thermal, vendor MediaCodec, HDR/passthrough and absolute-performance claims require physical-device evidence.

## O1 — WorkManager diagnostics (#191)

### Boundary

WorkManager configuration remains owned by the application composition root. Framework callbacks translate failures into stable, bounded diagnostic types. Callback handling is observational and non-throwing.

Initialization failure diagnostics must not require Room or WorkManager itself to be healthy. This prevents recursive failure during WorkManager startup.

### Data policy

Allowed:

- stable failure kind;
- timestamp;
- bounded safe worker category/identity when available and explicitly sanitized;
- bounded count/dedup metadata.

Forbidden:

- `Throwable.message` / stack trace in persisted/exported data;
- source/playback URLs or query values;
- headers/cookies/credentials/tokens;
- arbitrary WorkManager input/output Data;
- channel/programme/user text.

### Doctor integration

Doctor consumes a coarse background-work projection through an explicit reader/model. Do not add WorkManager failures to `PlaybackObservation`.

## O2 — Tracing 2.0 (#192)

### Boundary

Use a repository-owned `MuxTvTrace` abstraction with stable names and explicit disabled/no-op behavior. Product correctness must never depend on tracing.

Initial names:

- `MuxTv.SourceRefresh`
- `MuxTv.M3uParse`
- `MuxTv.CatalogStage`
- `MuxTv.CatalogActivate`
- `MuxTv.EpgImport`
- `MuxTv.EpgMatch`
- `MuxTv.Search`
- `MuxTv.GuideWindow`
- `MuxTv.PlayerResolve`
- `MuxTv.PlayerPrepare`
- `MuxTv.FirstFrame`
- `MuxTv.Seek`
- `MuxTv.Rebuffer`

Use coroutine-aware tracing only for meaningful suspend boundaries. Do not trace every function or Compose recomposition.

### Module ownership

Do not force Android framework types into domain contracts. Preferred first implementation:

1. keep trace names/attribute policy in a lightweight platform-neutral contract;
2. keep AndroidX Tracing adapter at an adapter/application boundary;
3. instrument catalog/database/player adapters, not pure domain decisions;
4. only introduce a new `core:observability` module if dependency analysis proves that direct adapter-local usage would duplicate policy across multiple modules. Module creation is not a goal by itself.

This decision must be validated against dependency guards before implementation.

### Runtime policy

- production does not write trace files by default;
- benchmark/debug may install in-process/Perfetto sinks;
- trace failures do not affect product behavior;
- metadata follows the same secret policy as Doctor and network diagnostics.

## O3 — OkHttp timings (#193)

Keep the existing shared `Dispatcher`/`ConnectionPool`/base-client architecture.

Measure source-refresh phases:

- DNS;
- connect;
- TLS;
- connection acquire/reuse where available;
- request start;
- response headers/TTFB;
- body completion/failure;
- total call duration.

Playback is different: HLS/DASH can produce high-volume segment traffic. Segment timing must default to disabled, aggregation or strictly bounded sampling. Never persist one row per segment indefinitely.

Network listeners are observational only and must not change redirect, header, timeout, cleartext or retry behavior.

## O4 — Playback, R8 and Benchmark evidence

### Media3 analytics

Owned by #109/#27. Attach bounded analytics to the existing single service-owned ExoPlayer. Capture only evidence needed for decisions: first frame, rebuffer, seek-resume, selected format/bitrate, decoder failure category, dropped frames/audio underrun where useful, coarse transport and bounded memory context.

Do not persist raw Media3 event streams. Do not add PlayerPool, a second player or SimpleCache.

### R8

Owned by #31. Use AGP 9.3+ `analyzeReleaseR8Config` as evidence before changing keep radius. Record shrinking/optimization/obfuscation scores and review broad keep rules. Keep-rule reductions are separate tested changes.

### Benchmark

Extend from screen-open smoke to deterministic TV CUJs:

- Channels 50–100 D-pad moves and paging/focus restoration;
- Search deterministic query + results traversal + open/return;
- Guide vertical/horizontal navigation across a populated window;
- Player start -> first frame -> bounded channel zaps;
- bounded repeated semantic seeks;
- memory/soak where practical.

Benchmark 1.5 + Tracing 2.0 should be used to correlate system and in-process evidence.

## Measure-first capabilities

These remain experiments, not default modernization:

- Room3 explicit connection pools/query coroutine context (#196);
- FTS5/trigram migration (#196);
- `WITHOUT ROWID` (#196);
- Gradle configuration-cache parallelism / Isolated Projects (#195);
- Compose hot-path restructuring;
- adaptive Media3 LoadControl/back buffer (#109);
- M3U parser structural optimization;
- Rust/native parser;
- SimpleCache or alternate playback engine.

A valid result may be “keep current defaults”.

## Error handling requirements

Observability must be less reliable than the product it observes:

- recorder/listener/tracer failure is swallowed at the boundary after best-effort classification;
- bounded buffers have explicit overflow/drop behavior;
- no diagnostic path may introduce retry loops;
- no diagnostic callback blocks main/playback/network threads on Room or disk I/O;
- timestamps/durations use monotonic clocks for elapsed-time measurements where applicable;
- wall-clock is used only for human/report timestamps;
- callback/event ordering assumptions are tested rather than inferred.

## Security requirements

Never expose through trace/diagnostic/timing metadata:

- full URL/path/query/fragment;
- Authorization/Cookie/custom secret headers;
- access tokens, signed URLs or credentials;
- arbitrary exception messages or stack traces;
- IPTV channel/programme/provider display text unless an existing redacted contract explicitly permits it.

Tests must include hostile secret-bearing fixtures to prove the boundary.

## Acceptance philosophy

Every implementation package follows:

```text
RED contract -> observed expected failure -> minimal GREEN -> host exact-head -> device evidence if runtime-sensitive
```

No GREEN claim is valid until executed. With the self-hosted runner offline, coordination/docs may proceed, but production implementation branches stop before GREEN until the relevant RED can actually be observed.

## Ownership map

- #179 — umbrella ordering/applicability;
- #191 — WorkManager failure diagnostics;
- #192 — Tracing 2.0;
- #193 — OkHttp timings;
- #194 — durable truth sync;
- #195 — Gradle 9.7 experiments, post-alpha;
- #196 — Room3 experiments, blocked by #178;
- #27/#178 — measurement authority/correctness;
- #31 — R8/Benchmark/release;
- #30 — Doctor projection;
- #100 — conditional HTTP 304;
- #109 — playback analytics/buffer decision;
- #146 — Room3 dependency-only patch;
- #189 — U0 UI characterization; independent;
- #190 — combined stack compatibility staging; not final merge unit.
