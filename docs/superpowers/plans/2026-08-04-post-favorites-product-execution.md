# Post-Favorites Product Execution Plan

**Date:** 2026-08-04  
**Accepted base:** `main@64b64c933da665d00ac403fd410a39309e773d64`  
**Primary issue:** #29  
**Parallel evidence issue:** #27

## Objective

Move MuxTV from the accepted Channels/NowNext/Favorites foundation to a daily-usable TV product without introducing unmeasured native/database/player replacements.

The product critical path is:

`Search Core → Search TV → Recent → bounded Guide → playback recovery/Doctor → TV UX polish → alpha hardening`.

Self-hosted and device matrices are acceptance infrastructure. They prove changes; they are not the product roadmap.

## Package 0 — repository convergence

### Delivered

- #90 accepted as `7e1f18f31ab8628a104f2668d87e6478d7559242`;
- #91 closed/superseded;
- #92 accepted as `64b64c933da665d00ac403fd410a39309e773d64`;
- #83/#87/#89 closed unmerged as stale or measurement-unproven performance surfaces;
- #88 closed/superseded;
- authoritative README/current-state/status updated from the accepted Favorites main.

### Exit criterion

Repository truth names only accepted code as complete and does not direct work back to stale stacks.

## Package 1 — Search Core / Room v9

### Existing working implementation

The Search working branch already contains:

- bounded `ChannelSearchRepository` API;
- public default 100 / max 200 results;
- max six processed Unicode tokens;
- safe quoted FTS prefix expressions;
- Room v9 external-content FTS4 with `unicode61`;
- compact unique EPG programme-title vocabulary;
- current-policy/current-programme truth validation;
- cross-field multi-token matching;
- selective smallest-seed intersection with 800-candidate bound + overflow sentinel;
- deterministic structured TV ranking;
- earliest programme-boundary invalidation;
- rowid-preserving derived-index lifecycle;
- redacted diagnostics.

### Immediate work

1. Rebuild the final Search review surface from accepted post-Favorites `main` rather than merge the stale research ancestry.
2. Reconcile `ChannelPreferencesDao/repository` and Search DAO/repository registration exactly once.
3. Commit only Room-generated `core/database/schemas/app.muxtv.database.MuxTvDatabase/9.json`.
4. Run compile/KSP and eliminate compile warnings that become Kotlin 2.5 errors.
5. Prove v8→v9 migration on API26/API36.
6. Prove Cyrillic case/prefix behavior through real `unicode61` runtime queries.
7. Execute non-zero Search DAO/index/repository contracts.
8. Re-run source/EPG publication ownership regression contracts.
9. Measure migration/backfill DB size and representative 1k/10k/50k query latency.
10. Do not add `prefix=`, `primaryTitle` B-tree, FTS5, bundled SQLite or native search without measurements.
11. Guarded squash merge.

### Exit criterion

A clean post-Favorites PR is mergeable, generated schema v9 is committed, API26/API36 migration + Search contracts are green, and no Search result can bypass active catalog/current-policy EPG truth.

## Package 2 — Search TV

### Architecture

Create a dedicated `:feature:search` destination-scoped state owner. Do not put search state in Navigation arguments or a global singleton.

### Behavior

- blank query → empty state, never full-catalog query;
- typing debounce starts at 300 ms and remains adjustable by measurement;
- explicit IME Search/Done submits immediately;
- normalized duplicate query does not restart work;
- new generation cancels old repository/boundary work;
- stale generation cannot overwrite current state;
- current Content remains mounted on same-query refresh/boundary changes;
- no arbitrary frame-delay focus repair.

### TV focus

- entry focus → Search input;
- Down input → first result;
- Up first result → input;
- OK result → existing Player;
- Player → Back restores query + same canonical channel;
- missing channel → nearest previous;
- no results → input;
- IME submit leaves text field immediately to avoid fullscreen-keyboard traps on TV vendors.

### Exit criterion

Search is usable with D-pad + platform keyboard on API26/API36 and retains query/focus over Player navigation.

## Package 3 — Recent / Room v10

### Storage

Separate table from `user_channel_overlays`:

- `profileId`;
- `canonicalChannelId`;
- `lastSuccessfulPlaybackAt`;
- `successfulPlaybackCount`.

### Write authority

Write only after confirmed successful playback. Do not record on click, Player open, stream resolution, MediaItem creation or buffering.

### Read policy

- profile scoped;
- bounded retention;
- hidden/inactive channels excluded;
- canonical identity retained across source refreshes where catalog mapping preserves identity.

### Exit criterion

Recent reflects successful viewing rather than navigation intent or failed starts.

## Package 4 — bounded Guide

### Query boundary

Use a bounded viewport contract equivalent to:

```kotlin
GuideViewportQuery(
    profileId,
    channelIds,
    fromEpochMillis,
    toEpochMillis,
    channelLimit,
    programmeLimit,
)
```

### Rules

- no full-guide materialization;
- lazy channel × time window;
- current-time marker derived from accepted EPG semantics;
- ambiguous/stale EPG never shown as current truth;
- deterministic D-pad traversal;
- Player → Back restores channel/time focus anchor.

### Exit criterion

Guide cost scales with viewport, not total provider EPG size.

## Package 5 — issue #30 playback recovery + TV Doctor Lite

### Recovery ladder

`preferred → fallback1 → fallback2 → stop`

### Hard bounds

- max attempts;
- total recovery budget;
- per-attempt timeout;
- cancellation authority;
- no retry storm.

### Failure families

- DNS;
- TLS;
- TIMEOUT;
- HTTP;
- AUTH;
- REDIRECT;
- MANIFEST;
- DECODER;
- PLAYBACK.

AUTH is not treated as a generic transient network retry. Temporary fallback must not silently persist as preferred variant.

### Doctor

Expose only redacted local evidence. Never log locators, access tokens, cookies, Authorization values, provider secrets or raw exception strings.

## Package 6 — issue #33 TV UX polish

Perform visual/navigation polish only after real Search/Guide routes exist:

- Lounge/navigation shell;
- channel-row geometry and long RU labels;
- focused/selected/currently-playing visual distinction;
- minimal fullscreen Player controls;
- simplified Sources/Add Source;
- credential-free logo loading after row geometry stabilizes;
- 720p/1080p/4K/accessibility/device QA.

Do not introduce a second state architecture or custom global focus engine.

## Package 7 — issue #31 alpha hardening

- R8/resource shrink;
- Compose compiler metrics;
- Macrobenchmark;
- Baseline Profile;
- Startup Profile;
- startup/frame/process/Java/native memory evidence;
- API37 memory-limiter stress where applicable;
- install/upgrade/Room/Keystore recovery;
- physical Android/Google TV, constrained hardware and Fire TV;
- signing;
- changelog;
- SBOM/licenses;
- release checklist.

## Parallel lane — issue #27 performance evidence

Run independently of daily-use product work:

1. `current-normal` ×5;
2. `old-edge-normal` ×5;
3. `current-low-ram` ×5;
4. separate cross-profile interpretation;
5. classify each operation as hard-gate / warning-only / descriptive-only;
6. publish durable report.

Only after repeated evidence may an old #83/#87/#89 optimization idea be clean-rebuilt. Two-run smoke variance is not merge evidence.

## Native/runtime decision gate

Do not introduce Rust/UniFFI, bundled SQLite, libmpv or a second player engine on preference alone. Require:

1. reproducible bottleneck or compatibility gap;
2. measured benefit on representative devices/corpora;
3. ADR covering FFI/ABI/packaging/debugging/update cost;
4. fallback/rollback strategy.

Until then Kotlin + Room + Media3 remains the product path.
