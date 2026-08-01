# MuxTV Post-Remote-EPG Execution Plan

> **Status:** active from `main` commit `27bb5bc49685779251b75c6e0aa134e4aaf4d3b1`.

## Goal

Move from the completed secure XMLTV acquisition + immutable EPG storage foundation to durable EPG scheduling, deterministic channel matching/now-next, daily-use TV surfaces, bounded playback recovery and an evidence-backed alpha without introducing parallel state frameworks or speculative native code.

## Verified baseline

Merged runtime/evidence packages now include:

- PR #59 — immutable measurement variance foundation;
- PR #60 — strict M3U/Room/Player measurement report adapters;
- PR #61 — sequential multi-run measurement orchestration;
- PR #62 — measurement repository-truth sync;
- PR #63 — bounded secure streaming XMLTV parser;
- PR #64 — Room v5 immutable EPG revisions, staging/activation and migration/device contracts;
- PR #68 — bounded magic-first plain/gzip/ZIP EPG payload decoding;
- PR #72 — secure conditional remote EPG refresh over the existing encrypted access/network boundary.

Current architectural baseline remains Kotlin + Coroutines/Flow + Compose for TV + Room 3 + WorkManager + OkHttp + Media3. Rust/UniFFI, bundled SQLite, libmpv and a second playback engine remain evidence-gated.

## P0 — Repository truth sync

**Scope:** documentation only.

- update `.work/CURRENT-STATE.md`, `.work/meta/status.yaml` and `README.md` to Room v5 and `main` `27bb5bc...`;
- mark XMLTV parser, immutable EPG storage, payload decoder and remote EPG acquisition complete;
- make #70 and #71 the active EPG critical path;
- keep issue #27 open as parallel measurement debt, not as a blocker for already-started EPG work;
- treat issue #23 as historical RFC/adoption rationale where its old roadmap numbering conflicts with current issues;
- point repository documentation at this plan.

Validation: Full documentation/build validation only; no DeviceMatrix is required for documentation-only changes.

## P1 — Issue #70 durable EPG refresh scheduling and state

Implement as two reviewable runtime packages so database semantics can be validated independently from WorkManager wiring.

### P1A — EPG refresh persistence + Room v5→v6 migration

Add EPG-specific durable refresh contracts rather than reusing M3U `SourceRefreshCompletion` directly. The existing M3U completion requires a new catalog revision on every success, which is incompatible with a valid conditional `304 Not Modified` EPG success.

Recommended typed domain:

- `EpgRefreshPolicy` — source ID, enabled, interval, unmetered, charging, updated-at;
- `EpgRefreshRunState` — IDLE/RUNNING/SUCCEEDED/FAILED/NEEDS_AUTH/CANCELLED;
- `EpgRefreshResultKind` or safe result code distinguishing `REFRESHED` from `NOT_MODIFIED`;
- `EpgRefreshStatus` — timestamps, last successful active revision, last success time, safe family/code and bounded counters;
- `EpgRefreshAttempt` — bounded history, trigger, timestamps, safe family/code and aggregate counts only;
- lease ownership — opaque run token + start time, stale reclamation and old-token rejection.

Persistence rules:

- separate tables `epg_refresh_policies`, `epg_refresh_states`, `epg_refresh_attempts` (or equivalently isolated EPG tables);
- do not put URL, credential values, programme text, raw exceptions or HTTP validator values into state/history rows;
- validators required for conditional requests must have an explicit durable owner separate from public refresh state/history and must never appear in diagnostics or generated `toString()` output;
- successful `304` updates last-success metadata without allocating/activating an EPG revision;
- successful `200` records the actual activated revision;
- timeout/cancellation/failure never replaces previous-good guide data;
- completion is conditional on the current run token, so an old worker cannot finish a reclaimed lease;
- prune attempts transactionally to a fixed per-source bound.

Migration discipline:

1. preserve committed Room v5 schema as the source migration fixture;
2. add explicit v5→v6 migration;
3. export/commit v6 schema;
4. add `MigrationTestHelper` v5→v6 coverage;
5. run API 26 and API 36 database migration/device contracts.

### P1B — WorkManager orchestration and remote-refresh mapping

Reuse the existing `catalog:sync` scheduler/worker architecture, helpers and operational policies; do not create a second scheduling framework.

Work identity:

- EPG names/tags are distinct from M3U work names and contain only the opaque EPG source ID;
- manual/startup uses unique one-time work + `ExistingWorkPolicy.KEEP`;
- periodic uses unique periodic work + `ExistingPeriodicWorkPolicy.UPDATE`;
- policy removal cancels only EPG work for that source and deletes scheduling policy/state required by the issue contract.

Constraints:

- CONNECTED by default;
- UNMETERED and requires-charging from typed policy;
- operational timeout must remain strictly lower than lease-stale duration.

Outcome mapping from PR #72:

- `Refreshed` → successful refreshed attempt, persist returned validators only after successful import;
- `NotModified` → successful not-modified attempt, no new revision, persist returned validators;
- credential missing/unavailable/corrupted and insecure approval required → typed permanent/auth state as appropriate;
- retryable network/5xx/timeout → WorkManager retry within the bounded retry policy;
- URL/payload/import structural rejection and permanent 4xx → typed permanent failure;
- coroutine cancellation → persist CANCELLED from `NonCancellable`, then rethrow.

Startup:

- reconcile stored EPG periodic policies once after app/process initialization through the same application startup ownership used for source refresh;
- different EPG sources may execute independently, but one source is serialized by the DB lease across manual/periodic/startup work.

Required tests:

- one-source manual/periodic overlap is prevented;
- different sources are independent;
- current lease cannot be stolen; stale lease can;
- old token cannot complete a newer attempt;
- 304 success creates no revision;
- 200 success records activated revision;
- retry/timeout/cancel preserves previous-good revision;
- validator ownership is updated only after successful 200/304 completion;
- policy removal and startup reconciliation are deterministic;
- diagnostic strings contain only safe codes/counts.

## P2 — Issue #71 deterministic EPG matching and now-next

Keep matching pure and explainable:

1. exact normalized external/tvg identity within explicit provider relation;
2. exact normalized display name within the related provider/source;
3. narrowly constrained deterministic aliases (for example number + normalized name);
4. unresolved/ambiguous — never an unconstrained fuzzy winner.

Persist or cache matching by explicit catalog revision + active EPG revision so Compose never performs whole-guide matching. Store canonical channel ID plus safe reason code; no raw debug strings.

Expose bounded `NowNext` by canonical channel IDs with current, next and `nextBoundaryEpochMillis`. Open-ended programmes derive an effective boundary from the next programme when available. UI invalidates on active EPG revision change or the next boundary, not second-by-second polling.

If matching persistence needs schema changes, use a separate v6→v7 migration/PR rather than expanding #70 migration scope.

## P3 — Close issue #28

Gate closure on:

- Full validation;
- API 26/API 36 DB/device migration contracts;
- synthetic integration: remote XMLTV → decode → import → activate → match → now-next;
- cancellation/failure previous-good preservation;
- redaction audit;
- issue acceptance reconciliation.

Guide UI remains issue #29, not #28.

## P4 — Finish issue #27 in parallel

Run independent five-run campaigns:

- `current-normal`;
- `old-edge-normal` (API 26, documented API 28 fallback only when exact image unavailable);
- `current-low-ram`.

Keep profiles separate. Produce a per-operation decision: hard gate, warning-only or descriptive-only. Do not select Rust/UniFFI or structural database/player rewrites before this evidence. HLS runtime fixture binding remains owned by #30.

## P5 — Issue #29 daily-use product slices

Land as narrow PRs:

1. real Channels now/next presentation;
2. Favorites through `UserChannelOverlay` and canonical IDs;
3. bounded profile-scoped Recent updated only after successful playback start;
4. bounded/debounced DB-backed Search across channel/name/number/group/current programme;
5. Guide with bounded time window and lazy rows/cells;
6. D-pad focus and Player/Back continuity across filters, Search and Guide.

## P6 — Issue #33 TV-first UX

Recommended order after real data exists:

1. D2 dedicated channel rows over existing focus ownership;
2. now/next visual integration;
3. D3 hidden-by-default fullscreen Player overlay;
4. D4 Sources/Add Source simplification;
5. real Guide/Search destinations;
6. D1 restrained light shell/navigation;
7. D5 credential-free bounded logo loader;
8. D6 visual/device QA.

No global MVI/Redux, custom focus engine or placeholder Guide/Search surfaces.

## P7 — Issue #30 bounded fallback + TV Doctor Lite

- bind repository HLS fixtures to the real fallback consumer;
- bounded attempt/time ladder;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback families;
- auth is not retried as generic transient network failure;
- successful temporary fallback does not overwrite preferred variant;
- Activity recreation and WorkManager cannot multiply attempts;
- redacted local diagnostic export;
- keep Media3 as the only player engine unless measured evidence demands an ADR.

## P8 — Issue #31 alpha hardening

Split release work:

- R8/resource shrinking as a separate change;
- Baseline Profile module and measured before/after startup/journey evidence;
- virtual old/mainstream/current/low-RAM matrix;
- physical current Android/Google TV + constrained device + Fire TV evidence;
- install/upgrade/Keystore/Room migration recovery;
- signed `0.1.0-alpha`, changelog, SBOM/licenses/dependency report and release checklist;
- codec/HDR/passthrough/Fire compatibility claims only where physically evidenced.

## Cross-cutting review rules

- RED test before production behavior changes; then minimal GREEN implementation and refactor;
- one migration concern per PR;
- no raw provider-controlled values in errors/logs/traces/status history;
- immutable active revisions remain the reader boundary;
- cancellation must preserve previous-good data and rethrow coroutine cancellation;
- remote/AVD evidence proves only the boundary it actually exercises;
- no optimization or native rewrite without reproducible measurements.
