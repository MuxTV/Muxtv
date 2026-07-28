# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** active; repository hygiene, deterministic corpus generation, canonical artifacts, executable entry point and bounded starter fixtures are complete.

**Goal:** move from the completed secure source → catalog → Player vertical slice to reproducible measured evidence, immutable EPG and daily-use TV flows without introducing speculative runtime complexity.

## Baseline

- `main` includes exact-origin playback approval PR #42, runner hygiene PR #43, M3U diagnostic redaction PR #45, deterministic M3U foundation PR #44, canonical manifest PR #47, artifact publisher PR #48, corpus entry point PR #50 and typed fixture PR #51.
- Issues #26 and #39 are completed; issue #27 remains active only for descriptive measurements, variance evidence and later consumer binding.
- Kotlin, Compose for TV, Room and Media3 remain the production baseline.
- One process-owned ExoPlayer/MediaSession and one encrypted source-access owner remain authoritative.
- API 26/API 36 emulator evidence validates Android platform/lifecycle contracts, not vendor decoders, HDR, passthrough, Fire OS or weak ARM devices.

## Package 0 — Repository truth and runner hygiene

**Status:** completed by PR #43 and subsequent repository-truth PRs.

- [x] synchronize human and machine-readable repository truth;
- [x] make persistent Windows workspace cleanup deterministic with repository-local `core.longpaths`;
- [x] emit clean-workspace evidence;
- [x] keep pure documentation sync separate from runtime changes.

## Package 1A — Deterministic M3U foundation

**Status:** completed by PR #44, squash commit `3e24cccb188b53652285929a11e3b50697aad5f7`.

- [x] repository-owned streaming generator in `core:testing`;
- [x] named 1k/10k/50k profiles;
- [x] explicit numeric seed and exact source commit;
- [x] synthetic identities/groups/logos/headers and relative/absolute locators;
- [x] controlled duplicates, malformed input, long metadata and mixed line endings;
- [x] streaming output without materializing the large corpus;
- [x] expected parsed/skipped/warning/duplicate counts, UTF-8 size and SHA-256;
- [x] byte-identical repeated output and changed-seed contract;
- [x] real `StreamingM3uParser` agreement;
- [x] provider-neutral and credential-free fixtures;
- [x] permanent `:core:testing:test` Fast/Full gate;
- [x] caller-owned stream flush/no-close contract.

## Package 1B — Canonical corpus artifacts

**Status:** completed by PR #47 and PR #48.

- [x] stable artifact IDs `small-1k`, `medium-10k`, `large-50k`;
- [x] full lowercase 40-character source commit;
- [x] explicit manifest/generator schema versions;
- [x] fixed-order UTF-8 JSON with explicit LF/trailing newline;
- [x] deterministic `.m3u8 + .manifest.json` filenames;
- [x] playlist size/SHA-256 agreement;
- [x] manifest-last commit marker;
- [x] explicit overwrite through staging and backup/restore;
- [x] typed publish/rollback failures and recoverable backup preservation;
- [x] path-redacted diagnostics;
- [x] pure Kotlin implementation outside Android runtime.

## Package 1C — Repository generation entry point

**Status:** completed by PR #50, squash commit `7134a4aaf2968ae0b7d62cf01bab254eb97e6b9f`.

- [x] strict profile/seed/source-commit/output/overwrite options;
- [x] deterministic rejection of missing, duplicate, unknown and malformed options;
- [x] safe output without full paths or supplied values;
- [x] stable success/usage/publish/internal exit codes;
- [x] one call into the existing publisher;
- [x] `:core:testing:generateM3uCorpus` JavaExec task;
- [x] configuration-cache-compatible wiring without execution-time build-script capture;
- [x] exact-head `small-1k` generation and archived evidence;
- [x] no large corpus committed to Git;
- [x] Full run `30377371429`.

## Package 1D — HLS and XMLTV starter fixtures

**Status:** completed by PR #51, squash commit `a26fd4ba492948c413b317c168db5678db4ed00e`.

- [x] HLS master with relative variants;
- [x] encrypted HLS media fixture with relative key and segments;
- [x] malformed HLS master with typed missing-URI expectation;
- [x] XMLTV DST/Unicode fixture;
- [x] XMLTV missing-channel-reference fixture;
- [x] XMLTV malformed-timestamp fixture;
- [x] stable IDs and deterministic order;
- [x] per-fixture 16 KiB and aggregate 64 KiB bounds;
- [x] synthetic `.example` resources without user info/query/fragment;
- [x] required header names and synthetic redirect metadata without credential values;
- [x] payload-redacted diagnostics and secret-safe lookup;
- [x] no parallel HLS/XMLTV runtime parser;
- [x] Full run `30378845744`.

## Package 2A — Descriptive M3U parse measurements

**Status:** next implementation package.

Add a repository-owned JVM measurement runner that measures the existing deterministic corpus through the real `StreamingM3uParser`.

### Required inputs

- stable profile ID;
- signed seed;
- exact lowercase source commit;
- warmup count;
- measured iteration count;
- output report path.

### Method

1. generate one deterministic corpus artifact outside the measured section;
2. validate generated manifest and expected parser counts;
3. perform documented warmups;
4. reopen the same immutable corpus for each measured parse;
5. consume entries/warnings through a no-retention sink;
6. record wall-clock nanoseconds for every iteration;
7. record per-thread allocated bytes when supported by the JVM, otherwise explicit `unavailable`;
8. retain every valid sample; do not silently remove outliers;
9. emit canonical UTF-8 JSON with method/environment/fixture metadata and raw samples;
10. calculate min, median/p50, p90, p95, max and failure count without assigning a pass/fail budget.

### Environment metadata

- exact MuxTV source commit;
- report/method schema versions;
- profile, seed, corpus byte size and SHA-256;
- OS name/version/architecture;
- JVM vendor/version/runtime;
- available processors and max JVM heap;
- warmups and measured iterations;
- allocation support state;
- caller-provided runner label such as `local` or `self-hosted-windows-x64`.

### Acceptance

- [ ] RED report-model and percentile contracts;
- [ ] RED command option/safe-error contracts;
- [ ] RED real parser count-agreement contract;
- [ ] generation excluded from measured parse samples;
- [ ] minimum five measured iterations per methodology;
- [ ] canonical JSON with raw samples and one trailing newline;
- [ ] deterministic metadata ordering but explicitly non-deterministic timings;
- [ ] report contains no corpus payload, locator, provider identity, full temp path or supplied unknown value;
- [ ] Gradle task suitable for local/self-hosted execution;
- [ ] dedicated workflow step produces descriptive `small-1k` evidence without becoming a threshold gate;
- [ ] focused tests and Full validation;
- [ ] issue #27 remains open after merge.

## Package 2B — Catalog stage/activate/query measurements

After Package 2A:

1. generate a 10k corpus and prepare immutable importer input outside measured stages;
2. measure 250-entry stage batches separately from parse;
3. measure activation transaction separately;
4. measure active-channel first page and source-overview queries;
5. reset temp database state per documented run;
6. record WAL/temp/database sizes where available;
7. use Android instrumentation or a representative Room runtime rather than pretending a fake store is SQLite performance;
8. report distributions first, no threshold gate.

## Package 2C — Player setup proxy measurements

- resolve variant and access policy separately;
- measure request serialization/install command and reconnect/setup coordination;
- never call a successful command equivalent to first stable video frame;
- codec/startup/zapping claims require real device/network/stream milestones later;
- preserve one process-owned player/session and cancellation contracts.

## Package 2D — Repeated variance evidence

- repeat Package 2A/2B/2C on comparable exact environments;
- label warm/cold/cache state;
- compare only same device/firmware/runtime class;
- normal and low-RAM emulator profiles are separate datasets;
- introduce a dedicated threshold gate only after variance is understood;
- physical Tier A/B/Fire results remain required for release claims.

## Package 3 — Issue #28 XMLTV and immutable EPG revisions

- bind the existing typed XMLTV fixtures to a bounded streaming parser;
- timezone/DST and malformed-input contracts;
- EPG credentials/access separate from M3U source access;
- staging, atomic activation, rollback and previous-good retention;
- explicit channel-match confidence and reason;
- bounded now/next projections for the UI.

## Package 4 — Issue #29 daily-use discovery

- Guide, Search, Favorites and Recent over bounded projections;
- stable channel identity and current focus ownership remain unchanged;
- Navigation keys carry IDs only;
- no program payload, locator or provider secret in saved state or semantics.

## Package 5 — Issues #30, #33 and #31

- issue #30: bind HLS fixtures to bounded variant fallback and TV Doctor Lite only after evidence;
- issue #33: dedicated channel rows, Player overlay, Sources simplification and later light shell without another state framework;
- issue #31: R8/resource shrinking, Baseline Profile, signing, SBOM, release checklist and physical Android/Google TV/Fire TV alpha gate.

## Deferred decisions

Rust/UniFFI, libmpv, bundled SQLite, Paging, a second player engine and full KMP storage require:

1. a reproducible corpus;
2. a measured bottleneck or compatibility gap;
3. a focused ADR with migration/rollback cost;
4. tests proving no regression in security, cancellation and device behavior.

## Evidence required for every package

- reviewed exact head;
- focused RED/GREEN tests;
- Full validation;
- DeviceCurrent/DeviceMatrix only when Android system boundaries change;
- secret-free evidence artifacts;
- synchronized human and machine-readable status after merge.
