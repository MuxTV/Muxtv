# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** active; repository hygiene and deterministic M3U foundation are complete.

**Goal:** move from the completed secure source → catalog → Player vertical slice to reproducible corpus evidence, immutable EPG and daily-use TV flows without introducing speculative runtime complexity.

## Baseline

- `main` includes exact-origin playback approval PR #42, runner hygiene PR #43, M3U diagnostic redaction PR #45 and deterministic M3U foundation PR #44.
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

## Package 1A — Issue #27 deterministic M3U foundation

**Status:** completed by PR #44, squash commit `3e24cccb188b53652285929a11e3b50697aad5f7`.

- [x] create a deterministic generator owned by `core:testing`;
- [x] define named profiles for 1k, 10k and 50k entries;
- [x] take an explicit numeric seed and exact source commit;
- [x] generate stable synthetic identities, groups, logos, headers and relative/absolute locators;
- [x] include controlled duplicates, parser-recognized malformed input, long metadata, mixed line endings and skipped entries;
- [x] stream output rather than retaining the whole large corpus in memory;
- [x] calculate expected parsed/skipped/warning/duplicate counts, UTF-8 byte size and SHA-256;
- [x] prove equal profile + seed + source commit produces byte-identical output;
- [x] prove changed seed changes output/digest without changing profile expectations;
- [x] assert manifest expectations through the real `StreamingM3uParser`;
- [x] keep fixtures provider-neutral and credential-free;
- [x] include `:core:testing:test` in the permanent Fast/Full gate;
- [x] prove generator flushes but does not close caller-owned output.

## Package 1B — Canonical corpus artifact and generation entry point

**Status:** next implementation package.

### Manifest artifact

Emit canonical UTF-8 JSON with stable field order:

1. manifest schema version;
2. generator schema version;
3. profile ID;
4. seed;
5. exact source commit;
6. expected parsed/skipped/warning/duplicate/unique counts;
7. UTF-8 playlist byte size;
8. playlist SHA-256.

Constraints:

- no reflection-dependent or map-order-dependent serialization;
- LF line ending and trailing newline are explicit;
- same manifest produces byte-identical JSON across runs;
- writer flushes but never closes caller-owned output;
- source commit and digests are validated lowercase hex;
- manifest diagnostics do not contain corpus payload or future provider values.

### Generation entry point

Add a repository-owned command/script that:

- takes named profile, numeric seed, exact source commit and output directory;
- writes `.m3u8` and `.manifest.json` with deterministic names;
- refuses accidental overwrite unless explicitly requested;
- writes through temporary files and moves completed artifacts into place;
- removes partial temporary artifacts on failure/cancellation;
- emits no private source values and does not require Android SDK/emulator.

### Acceptance

- [ ] RED tests for canonical JSON snapshot, deterministic bytes, field order, LF/newline and stream ownership;
- [ ] RED tests for invalid source commit/digest and safe diagnostics;
- [ ] minimal manifest writer implementation;
- [ ] RED tests for artifact naming, overwrite refusal and partial-file cleanup;
- [ ] minimal generation entry point;
- [ ] focused tests and Full;
- [ ] evidence contains generated small profile and manifest only, not a committed large corpus;
- [ ] squash merge as a focused PR; keep issue #27 open.

## Package 1C — HLS and XMLTV starter fixtures

- HLS master/media playlists with relative paths, redirects, header-sensitive subresources and malformed tags;
- XMLTV timezones, DST transitions, malformed timestamps, missing channel references and Unicode;
- keep fixtures bounded; large XMLTV measurements belong to issue #28;
- define typed fixture manifests rather than relying on filenames alone.

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
