# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** active after PR #42 / issue #39 closure.

**Goal:** move from the completed secure source → catalog → Player vertical slice to reproducible corpus evidence, immutable EPG and daily-use TV flows without introducing speculative runtime complexity.

## Baseline

- `main` includes PR #42 squash commit `764ec102808c4df57e826d05ce7b1334063bb520`.
- Issue #39 is completed.
- Kotlin, Compose for TV, Room and Media3 remain the production baseline.
- One process-owned ExoPlayer/MediaSession and one encrypted source-access owner remain authoritative.
- API 26/API 36 emulator evidence validates Android platform/lifecycle contracts, not vendor decoders, HDR, passthrough, Fire OS or weak ARM devices.

## Package 0 — Repository truth and runner hygiene

**Scope:** documentation and CI only.

- [x] synchronize README, `.work/CURRENT-STATE.md` and `.work/meta/status.yaml` after PR #42;
- [x] set issue #27 as the next milestone;
- [x] disable checkout pre-clean, enable repository-local `core.longpaths`, then perform explicit failing `git reset --hard` / `git clean -ffdx`;
- [x] emit workspace-cleanup evidence and verify an empty `git status`;
- [ ] pass Full twice without `Filename too long` cleanup warnings;
- [ ] merge as a small standalone PR.

## Package 1 — Issue #27 deterministic IPTV corpus foundation

### 1.1 Provider-neutral M3U generator

- create a deterministic generator owned by `core:testing` or a dedicated testing fixture package;
- define named profiles for 1k, 10k and 50k entries;
- take an explicit numeric seed and never embed real provider data;
- generate stable channel/source identities, groups, logos, headers and relative/absolute locators;
- include controlled duplicates, malformed attributes, long metadata, mixed line endings and skipped entries;
- stream output rather than retaining the whole large corpus in memory.

### 1.2 Corpus manifest

For every generated corpus emit:

- profile and generator schema version;
- seed;
- expected total/valid/skipped/duplicate counts;
- expected group and variant distributions;
- UTF-8 byte size;
- SHA-256;
- generator/source commit.

Acceptance:

- same profile + seed produces byte-identical output and digest on repeated runs;
- a changed seed changes the digest;
- fixtures contain no private locator, credential or provider names;
- manifest expectations are asserted by parser/importer tests.

### 1.3 HLS and XMLTV starter fixtures

- HLS master/media playlists with relative paths, redirects, header-sensitive subresources and malformed tags;
- XMLTV timezones, DST transitions, malformed timestamps, missing channel references and Unicode;
- keep these starter fixtures bounded; large XMLTV measurements belong to issue #28.

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
- optimize only a measured bottleneck and preserve bounded/security contracts.

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
- issue #33: remaining light TV-first visual modernization in data-dependency order, without another state framework;
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
