---
status: accepted
last_reviewed: 2026-07-19
---

# Quality gates

## 1. Principle

A change is accepted only when evidence covers correctness, migration/data integrity, TV focus/accessibility, security/privacy and measured performance for affected critical journeys. High test count does not compensate for missing device or recovery evidence.

## 2. Pull request gates

Every PR:

- Gradle wrapper/toolchain/configuration-cache validation;
- compile all affected modules and debug/release-like variant where relevant;
- unit tests;
- static analysis and Android lint;
- dependency/version catalog validation;
- architecture/dependency cycle tests;
- secret scan and dependency review;
- `.work` metadata/schema/document link consistency when docs change.

Conditional gates by impact:

### Domain/data/profile

- typed ID/invariant tests;
- Room schema export/migration tests;
- primary profile invariant and cascade/scope tests;
- source/EPG revision transaction/process-death tests;
- backup compatibility fixtures;
- no destructive migration fallback.

### Parser/network/security

- parser corpus/golden/property/fuzz tests;
- decompression/resource/cancellation tests;
- SSRF/address/redirect/header credential matrix;
- XXE/archive bomb/hostile scheme tests;
- redaction canary scan;
- previous active revision preserved after failure.

### Playback

- fake-engine state/recovery/error mapping tests;
- Media3 adapter tests/local controlled streams;
- stop/cancel/Activity recreation/surface/network tests;
- track semantic identity;
- no raw engine error/UI secret;
- physical-device test when codec/audio/display behavior changes.

### UI/design

- Compose semantics and deterministic D-pad focus graph;
- Back/focus restoration;
- screenshot matrix for changed components/routes;
- selected/focused/pressed/disabled/high-contrast/large-text states;
- accessibility labels/roles/actions;
- macrobenchmark comparison if Home/live/EPG/player layout or image pipeline changes.

### Smart Channels/TV Doctor

- labeled matching/calibration corpus;
- hard-conflict/manual-decision tests;
- merge/split/mutation exact inverse tests;
- scoring version/determinism/hysteresis/cooldown tests;
- probe scheduler resource/concurrency tests;
- preview/apply/undo tests.

### Local control/extensions/update

- auth/capability/rate/CSRF/origin/Host tests;
- pairing replay/expiry/revoke;
- extension contract/version/signature/timeouts;
- updater wrong repo/package/version/hash/certificate/downgrade;
- no untrusted workflow receives release signing secret.

## 3. Release gates

Additionally:

- clean build from fresh checkout with pinned JDK/SDK/toolchain;
- all PR gates on exact release commit;
- instrumented tests on emulator;
- physical device smoke on Android TV/Google TV/Fire TV tiers;
- Macrobenchmark on reference devices;
- Baseline Profile generation and packaged `baseline.prof` verification;
- minified release build/R8 mapping retention policy;
- APK package/version/TV manifest/signature certificate inspection;
- SBOM, SHA-256, release metadata and notes;
- clean install, upgrade from every supported schema, backup/restore and recovery scenarios;
- source/M3U/XMLTV/playback fault smoke;
- at least one endurance run for stable release class;
- release artifact/update verification through PackageInstaller test;
- security/privacy checklist and diagnostic canary scan;
- factual `CURRENT-STATE.md`/release support matrix update.

## 4. Initial performance budgets

Budgets are hypotheses until physical Tier A/B/F baseline exists; methodology in `benchmark-methodology.md`.

| Metric | Initial budget |
|---|---:|
| Cold start to interactive shell, reference Tier B | p50 ≤ 1.5 s, p95 ≤ 2.5 s |
| Return from background | p95 ≤ 700 ms |
| Open cached channel list | p95 ≤ 300 ms |
| Common local search result after debounce | p95 ≤ 150 ms |
| Channel zap to stable first frame/audio, available stream | p50 ≤ 1.2 s, p95 ≤ 3.0 s |
| Sustained jank in rail navigation | < 2% frames |
| Import 10,000 M3U entries | ≤ 5 s, peak app heap ≤ 180 MB on defined Tier B |
| XMLTV processing | streaming; heap does not grow linearly with input size |
| Source/EPG commit transaction | short; target calibrated, never includes network/full parse |
| Leak after 100 channel switches | no monotonic player/surface/listener growth; heap returns to defined steady band |
| Focus input response | no missed/stuck focus under defined rapid key sequence |

Regression policy:

- statistically credible >10% requires explanation/evidence;
- >20% or budget breach blocks merge without accepted ADR;
- security/correctness cannot be traded for speed silently;
- emulator numbers cannot satisfy codec/Fire/thermal gate.

## 5. Test pyramid

- Domain/policies/scoring/parsers: fast JVM tests, property/fuzz corpus.
- Database: instrumented schema/migration/transaction/process-death-oriented tests.
- Features: reducer/ViewModel/use-case tests with fake ports.
- UI: semantics/focus/screenshot/accessibility.
- Playback orchestration: deterministic fake engine/fault clock.
- Media3 adapter: instrumented/local stream fixtures and physical device cases.
- Network/local-control/update: controlled test server/proxy and malicious scenarios.
- End-to-end: small set of critical journeys from `specifications/user-journeys.md`.
- Lab: physical device/codec/network/endurance.

Avoid brittle full-stack tests for every branch; maintain strong contracts at boundaries and a small verified journey set.

## 6. Device matrix

Minimum classes:

1. constrained Android TV/AOSP, 2 GB RAM or less, API 26–28;
2. mass Google TV, 2–3 GB RAM, API 30+;
3. current 4K high-end box/display/audio chain, API 33+;
4. constrained/current Fire TV devices;
5. emulator for deterministic UI/DB tests.

Each result records model, firmware/build, API, output mode, network and media fixture. Manufacturer name alone is insufficient.

## 7. Observability

- structured local events with central redaction;
- bounded ring buffer and retention;
- correlation IDs for source/EPG/backup/playback/Doctor/update/local-control;
- metrics/provenance without raw credentials/content;
- explicit previewable diagnostic export;
- telemetry absent by default;
- crash upload only opt-in and separate ADR;
- logs/reports never considered substitute for reproducible test.

## 8. Flaky tests

- flaky test is tracked as defect, not silently retried indefinitely;
- bounded retry may diagnose infrastructure only; final gate reports original failure;
- quarantining critical migration/security/focus/playback test is prohibited without owner/expiry/issue;
- screenshot updates require visual review, not bulk acceptance;
- physical-device intermittent failure retains traces and blocks support claim until classified.

## 9. Completion evidence

A phase/release review records:

```text
exact commit/build
commands and exit results
CI run links
schema/API versions
device/firmware/network
benchmark method/results
critical journey pass/fail
known limitations/deferred items
security/release verification
```

No phase is marked complete based only on code diff or agent report.