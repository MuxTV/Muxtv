# Alpha critical-journey traceability matrix — 2026-08-24

Status: runner-free static evidence inventory. Test names/files are repository facts; no execution result is inferred from file existence.

Reviewed accepted baseline: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Owner: #31 release hardening. Performance interpretation remains #27; U0/U1/M0 stabilization gates remain authoritative.

## Purpose

MuxTV already contains many focused tests. The release gap is not simply “add more tests”; it is to prove that each alpha-critical user journey has the correct evidence **at the right layer**:

```text
pure/unit contract
    +
database/network/security contract
    +
Android interaction correctness
    +
performance/trace evidence where claimed
    +
physical-device evidence where hardware-dependent
```

A source file existing is `COVERAGE_PRESENT`, not `GREEN`. GREEN requires exact-head execution evidence.

## Evidence states

- `COVERAGE_PRESENT` — repository test/benchmark source exists for the concern;
- `PARTIAL` — related coverage exists but not the full journey/claim;
- `MISSING_CUJ` — no dedicated full critical-journey evidence identified in this static review;
- `RUNNER_REQUIRED` — source exists or is specified but acceptance needs host/device execution;
- `PHYSICAL_REQUIRED` — emulator cannot validate the claim.

---

## CJ1 — App launch -> usable Home

### Existing evidence sources

- `MainActivitySmokeTest`;
- `HomeJourneyTest`;
- `RailNavigationJourneyTest`;
- accessibility journey coverage;
- `MuxTvMacrobenchmarks.coldStartup()`;
- `MuxTvMacrobenchmarks.warmStartup()`;
- `BaselineProfileGenerator.startup()`.

### Static status

`COVERAGE_PRESENT / RUNNER_REQUIRED`.

### Remaining release evidence

- exact candidate cold/warm startup distributions;
- Home usable/readiness anchor, not merely Activity creation;
- accepted U1 geometry/focus baseline before final UI performance comparison;
- API26/API36 correctness as routed;
- physical weak-TV startup only if making weak-hardware claims.

---

## CJ2 — Home/rail -> Channels -> sustained D-pad navigation

### Existing evidence sources

- `ChannelsFocusRestorationTest`;
- `RailNavigationJourneyTest`;
- `MuxTvFocusSurfaceInteractionTest`;
- `ChannelBrowseRepositoryTest`;
- `ChannelBrowseLargeCatalogMeasurementTest`;
- Macrobenchmark currently covers only `homeToChannels()` screen transition.

### Static status

Correctness: `COVERAGE_PRESENT`.

Release-performance CUJ: `PARTIAL`.

### Missing depth

A representative benchmark must include:

- 50–100 D-pad moves;
- at least one paging/window boundary;
- focus restoration/nearest surviving item behavior;
- populated catalog rather than empty screen reachability;
- frame distribution and DB/window timing attribution.

No new AVD is required; canonical API36 is reused for runtime interaction, with API26 compatibility where the release risk gate requires it.

---

## CJ3 — Search query -> results -> focus movement -> channel selection

### Existing evidence sources

- `SearchFocusRestorationTest`;
- `ChannelSearchDaoTest`;
- Search repository/query contracts;
- Macrobenchmark currently covers only `homeToSearch()`.

### Static status

Correctness: `COVERAGE_PRESENT`.

End-to-end performance CUJ: `PARTIAL`.

### Missing depth

- enter deterministic realistic query;
- wait for published results;
- move focus through results;
- optionally launch a result if release CUJ includes playback;
- record Search DB candidate/hydration timing separately from Compose frame timing;
- #178/M0 accepted before performance conclusions.

---

## CJ4 — Guide open -> horizontal + vertical navigation

### Existing evidence sources

- `GuideFocusJourneyTest`;
- Guide/NowNext database contracts including active-query bounds;
- `MuxTvMacrobenchmarks.homeToGuide()`.

### Static status

Correctness: `COVERAGE_PRESENT`.

Populated Guide performance CUJ: `PARTIAL`.

### Missing depth

- horizontal programme movement;
- vertical channel movement;
- multiple Guide window fetches;
- explicit open-ended programme case where relevant;
- frame timing + Guide channel/programme query attribution;
- deterministic focus restoration after window movement.

---

## CJ5 — Add/configure source -> durable activation -> usable catalog

### Existing evidence sources

- `AppNavigationSourceJourneyTest`;
- `SourceEntryFocusTest`;
- `SourceEntrySecurityTest`;
- source onboarding/import/refresh tests in their owning modules;
- database staging/active-truth contracts;
- `CatalogStagingAtomicityTest`.

### Static status

`COVERAGE_PRESENT`, with alpha exact-head execution still `RUNNER_REQUIRED`.

### Required release proof

- sanitized source fixture only;
- prepare/activate/cancel path;
- failed refresh cannot replace previous-good active revision;
- credential/locator values absent from UI semantics/screenshots/diagnostic artifacts;
- restart/recovery behavior according to durable onboarding contract.

Remote QR/LAN pairing is not part of this alpha journey unless separately accepted.

---

## CJ6 — Source refresh -> immutable revision publication

### Existing evidence sources

- active channel truth contracts;
- staging atomicity;
- source revision/refresh tests;
- deterministic M3U parser tests;
- #27 measurement harness foundation.

### Static status

Correctness: `COVERAGE_PRESENT`.

Measured publication cost: `PARTIAL`, with #178/M0 required before DB-performance conclusions.

### Required evidence

- parser/staging/activation timings separated;
- DB/WAL delta;
- active revision/result digest;
- cancellation/stale run cannot publish;
- eventual #100 304 path measured separately.

---

## CJ7 — Channel OK -> service-owned playback -> first rendered frame

### Existing evidence sources

Android:

- `MediaSessionServiceSmokeTest`;
- `PlayerSurfaceContentJourneyTest`;
- `PlayerOverlayJourneyTest`;
- `PlayerHttpApprovalTest`;
- on-device/external playback fixtures.

Media3 unit contracts include first-frame recorder/tracker/profile identity, capability projection, failure classification and controller/session behaviors.

### Static status

Playback correctness primitives: `COVERAGE_PRESENT`.

Release first-frame CUJ: `PARTIAL`.

### Missing depth

Macrobenchmark/Baseline Profile does not currently drive a real channel launch to first rendered frame. Release evidence needs:

- deterministic playable fixture;
- request/prepare -> first rendered frame timing;
- exact active session/generation;
- no second player;
- player error/recovery classification;
- API26/API36 correctness as routed.

Vendor decoder/codec performance is `PHYSICAL_REQUIRED` before public hardware claims.

---

## CJ8 — Repeated channel zapping

### Existing evidence

Service/player generation and first-frame primitives exist, but no dedicated Macrobenchmark CUJ was identified that repeatedly changes channels and records first-frame/rebuffer distributions.

### Static status

`PARTIAL / MISSING_CUJ` for release performance.

### Required CUJ

- deterministic list of playable fixtures/candidates;
- bounded repeated channel replacements;
- generation-safe stale completion handling;
- first-frame per zap;
- rebuffer/failure counts;
- bounded memory growth.

No PlayerPool is introduced to make the benchmark easier; the test must exercise the accepted one-player service architecture.

---

## CJ9 — Semantic seek burst

### Existing evidence sources

- `SeekHudJourneyTest`;
- #175 accepted service-owned seek authority;
- player/service unit contracts around seek/generation/reconciliation.

### Static status

Correctness architecture: `COVERAGE_PRESENT`.

Measured burst performance: `PARTIAL`.

### Required release/performance evidence

- bounded 50/100/200-style request burst only where the test fixture supports seeking;
- requested vs applied/coalesced/stale counts;
- service acceptance -> applied seek -> rendered frame where causal evidence is valid;
- pending job/allocation evidence if investigating waiter churn;
- rebuffer/memory impact;
- one service mutation authority remains intact.

---

## CJ10 — Track selection / player controls / Back

### Existing evidence sources

- `TrackSelectionSheetJourneyTest`;
- `PlayerOverlayJourneyTest`;
- Media3 track projection unit tests;
- rail/player focus interaction tests.

### Static status

`COVERAGE_PRESENT / RUNNER_REQUIRED`.

### Release concerns

- five-button D-pad + Back reachability;
- hidden controls do not retain meaningful focus;
- current track state reflects actual player/session truth;
- long RU/large text/reduced motion remain reachable where affected.

---

## CJ11 — HTTP/security boundary during source/playback access

### Existing evidence sources

Network unit tests include:

- exact HTTP origin;
- redirect policy;
- secure redirect interceptor;
- playback request policy interceptor;
- sensitive header policy;
- response-size limit;
- source URL policy;
- URI redaction.

Android source/player journeys also include security-focused tests.

### Static status

`COVERAGE_PRESENT`.

### Release concerns

- no Authorization/Cookie/token/locator leakage in evidence;
- headers do not leak source/variant A -> B;
- cleartext approval remains exact-origin scoped;
- redirects preserve/drop sensitive headers according to accepted policy;
- #193 network timing must remain metadata-safe.

---

## CJ12 — credential storage / reset / re-auth boundary

### Existing evidence sources

Credential unit contracts include:

- AES-GCM AEAD;
- envelope codec;
- credential primitives;
- credential-store contract.

### Static status

Pure security contract: `COVERAGE_PRESENT`.

Android Keystore/lifecycle/reinstall/upgrade release evidence: `RUNNER_REQUIRED` and partly device-specific.

### Required alpha proof

- credential reference and encrypted material lifecycle follows accepted store contract;
- restart/upgrade behavior;
- clear/reset behavior;
- failed/deleted credential becomes typed re-auth state, not raw error/secret leakage;
- no credential content in release evidence.

---

## CJ13 — database upgrade from supported previous alpha

### Existing evidence sources

- Room database version 10 with contiguous migration chain;
- DB migration androidTests exist for historical steps;
- schema parity/export infrastructure exists.

### Static status

`COVERAGE_PRESENT`, but final supported-alpha upgrade path is `RUNNER_REQUIRED`.

### Release proof

Freeze which previous app/database version is supported for upgrade, then test the exact packaged migration path on canonical API26/API36 as required. Do not equate individual historical migration test files with a proven release-to-release upgrade artifact.

---

## CJ14 — app/process/reboot scheduling lifecycle

### Existing ownership

#118 accepted user-unlocked/WorkManager lifecycle behavior. #191 is the future typed WorkManager failure-observability owner.

### Static status

Architecture contract exists. Release device evidence remains `RUNNER_REQUIRED` for reboot/unlock/package replacement cases.

### Constraints

No Direct-Boot credential/Room expansion and no second durable scheduler.

---

## CJ15 — release/R8/Baseline Profile package

### Existing foundation

- release optimization enabled;
- narrow application keep-rules file;
- Baseline Profile plugin and generator;
- Macrobenchmark module.

### Static status

Foundation: `COVERAGE_PRESENT`.

Alpha qualification: `PARTIAL`.

### Missing release evidence

- R8 Configuration Analyzer exact candidate report;
- release runtime smoke with optimized artifact;
- Baseline Profile packaged verification;
- expanded hot CUJs from CJ2/CJ3/CJ4/CJ7/CJ8/CJ9;
- signing/SBOM/provenance contract implementation;
- exact artifact digest/certificate identity.

---

## Highest-priority evidence gaps after U0/U1/M0

These are not “write more unit tests” tasks. They are missing end-to-end release evidence:

1. Channels sustained D-pad/paging Macrobenchmark;
2. real Search query/results/focus Macrobenchmark;
3. populated Guide horizontal/vertical Macrobenchmark;
4. Player launch -> first rendered frame benchmark;
5. repeated channel-zap benchmark/evidence;
6. semantic seek burst evidence;
7. optimized release/R8 runtime smoke;
8. signed artifact/SBOM/provenance generation;
9. exact supported upgrade path;
10. physical-device codec/HDR/audio/constrained-hardware evidence before broad claims.

## What should not be duplicated

Do not create new test architectures when focused contracts already exist.

- focus correctness should reuse existing app/tv journey patterns;
- playback benchmarks should reuse the service-owned player and current on-device fixture boundary;
- DB performance should reuse #27/#178 measurement authority;
- security tests should reuse synthetic secret-shaped values/redaction boundaries;
- release hardware claims should feed #31 support-claim taxonomy.

## Stop condition while runner is unavailable

The traceability map is sufficient to define missing executable work. Do not add Macrobenchmark/player/DB production code until the relevant executable RED/baseline can be observed.