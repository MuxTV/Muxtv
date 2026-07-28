# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** active; repository hygiene, deterministic M3U foundation, canonical manifest serialization and artifact-pair publication are complete.

**Goal:** move from the completed secure source → catalog → Player vertical slice to reproducible corpus evidence, immutable EPG and daily-use TV flows without introducing speculative runtime complexity.

## Baseline

- `main` includes exact-origin playback approval PR #42, runner hygiene PR #43, M3U diagnostic redaction PR #45, deterministic M3U foundation PR #44, canonical manifest PR #47 and artifact publisher PR #48.
- Issues #26 and #39 are completed; issue #27 remains active.
- Kotlin, Compose for TV, Room and Media3 remain the production baseline.
- One process-owned ExoPlayer/MediaSession and one encrypted source-access owner remain authoritative.
- API 26/API 36 emulator evidence validates Android platform/lifecycle contracts, not vendor decoders, HDR, passthrough, Fire OS or weak ARM devices.

## Package 0 — Repository truth and runner hygiene

**Status:** completed by PR #43, squash commit `80dff5132f624ffedacfdbab0d7bdfe67d85f2a8`.

- [x] synchronize README, `.work/CURRENT-STATE.md` and `.work/meta/status.yaml` after PR #42;
- [x] set issue #27 as the next milestone;
- [x] disable checkout pre-clean, enable repository-local `core.longpaths`, then perform explicit failing `git reset --hard` / `git clean -ffdx`;
- [x] emit workspace-cleanup evidence and verify an empty `git status`;
- [x] pass Full twice without `Filename too long` cleanup warnings;
- [x] merge as a small standalone PR.

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

### Canonical manifest — PR #47

- [x] stable artifact IDs `small-1k`, `medium-10k`, `large-50k`;
- [x] full lowercase 40-character source commit;
- [x] explicit manifest/generator schema versions;
- [x] fixed-order UTF-8 JSON without reflection/map-order dependence;
- [x] explicit LF and exactly one trailing newline;
- [x] byte-identical serialization;
- [x] writer flushes but never closes caller-owned output;
- [x] no corpus payload/provider data/runtime dependency.

### Artifact pair publisher — PR #48

- [x] deterministic `.m3u8 + .manifest.json` filenames;
- [x] playlist size/SHA-256 agree with manifest;
- [x] manifest published last as commit marker;
- [x] implicit overwrite refused before mutation;
- [x] explicit overwrite through staging and backup/restore;
- [x] partial pair removed on second-publish failure;
- [x] previous complete pair restored on overwrite failure;
- [x] typed `TargetExists`, `PublishFailed` and `RollbackFailed`;
- [x] recoverable backup retained when rollback cannot finish;
- [x] staging cleanup contributes to rollback outcome;
- [x] filesystem paths redacted from request/result/error diagnostics;
- [x] implementation remains pure Kotlin in `core:testing`.

## Package 1C — Repository generation entry point

**Status:** next implementation package.

Add one repository-owned Gradle/CLI command over the merged publisher.

### Inputs

- named profile: `small-1k`, `medium-10k`, `large-50k`;
- signed 64-bit seed;
- exact lowercase 40-character source commit;
- output directory;
- explicit overwrite flag.

### Behavior

- parse arguments without echoing untrusted values into failures;
- reject missing, duplicate and unknown options deterministically;
- call only the existing `M3uCorpusArtifactPublisher`;
- print safe profile/count/filename/digest summary, never the full output path;
- return stable non-zero exit codes for usage and publication failures;
- require no Android SDK, emulator or runtime dependency;
- expose a documented Gradle task suitable for local and self-hosted use.

### Acceptance

- [ ] RED tests for valid invocation and deterministic filenames;
- [ ] RED tests for missing/duplicate/unknown arguments and invalid profile/seed/SHA;
- [ ] RED test that stdout/stderr do not contain output-directory or supplied secret-like values;
- [ ] RED test for target-exists mapping and explicit overwrite;
- [ ] minimal command implementation;
- [ ] Gradle `JavaExec` entry point with lazy project properties;
- [ ] focused tests and Full validation;
- [ ] generated small-profile evidence only; no large corpus committed;
- [ ] squash merge as a focused PR; issue #27 remains open.

## Package 1D — HLS and XMLTV starter fixtures

- HLS master/media playlists with relative paths, redirects, header-sensitive subresources and malformed tags;
- XMLTV timezones, DST transitions, malformed timestamps, missing channel references and Unicode;
- keep fixtures bounded; large XMLTV measurements belong to issue #28;
- define typed fixture manifests rather than relying on filenames alone;
- keep every locator/provider identity synthetic and reserved.

## Package 2 — Issue #27 measured boundaries

Measure before setting budgets:

1. M3U streaming parse wall time and allocations;
2. 250-entry staging batches and activation transaction;
3. active-channel query and source-overview query;
4. Player request installation proxy and reconnect/setup overhead;
5. normal and low-RAM virtual profiles with exact environment metadata.

Rules:

- first report distributions and variance; do not invent failing thresholds from one machine;
- separate correctness gates from descriptive benchmarks;
- record JDK, Gradle, Kotlin, AGP, ABI, API, RAM/CPU and commit;
- optimize only a measured bottleneck and preserve bounded/security contracts;
- introduce a dedicated threshold gate only after repeated variance evidence.

## Package 3 — Issue #28 XMLTV and immutable EPG revisions

- bounded streaming XMLTV parser;
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

- issue #30: bounded variant fallback and TV Doctor Lite, only after corpus evidence;
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
