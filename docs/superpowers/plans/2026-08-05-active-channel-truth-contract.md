# Active Channel Truth Contract Implementation Plan

> **Issue:** #114  
> **Base:** accepted `main@7af053ca14281d9e63a51470fbeb3cb8d708c318`  
> **Scope:** one database-owned cross-surface semantic contract; production query changes only when a RED proves drift.

## Goal

Prove that every profile-facing channel surface applies the same base membership before its own surface predicate:

1. at least one stream variant belongs to the source's `activeRevision`;
2. the canonical channel is visible for the selected profile;
3. only then apply Search, Recent, Favorites or Guide-specific projection.

The contract must survive an active source revision swap in the same database.

## Existing owners

Keep current ownership; do not create a shared SQL-string framework or another repository layer.

- Playback rows/direct playback: `RoomPlaybackCatalog` + `PlaybackCatalogDao`.
- Search: `RoomChannelSearchRepository` + `ChannelSearchDao`.
- Recent: `RoomRecentChannelsRepository` + `RecentChannelsDao`.
- Guide/NowNext: `RoomEpgGuideRepository` + `EpgGuideDao`.
- Catalog publication: `RoomSourceRevisionStore`.
- EPG match publication: `EpgMatchingDao`.

Low-level profile-agnostic catalog helpers are permitted only behind a profile-aware public boundary. In particular, `getActiveVariants(channelId)` must never make a hidden/stale channel directly playable because `RoomPlaybackCatalog` must first resolve the profile-aware active summary.

## Task 1 — Cross-surface instrumentation contract

**File:** `core/database/src/androidTest/kotlin/app/muxtv/database/ActiveChannelTruthContractTest.kt`

Create one in-memory `MuxTvDatabase` and two profiles.

Seed:

- source revision 1 active with canonical A/B;
- source revision 2 staged with canonical B/C;
- primary profile hides B;
- secondary profile leaves B visible;
- Search documents are produced by normal source staging/activation;
- Recent history contains A/B/C for both profiles;
- one active EPG revision contains programmes for A/B/C;
- EPG matches initially publish A/B against catalog revision 1.

Before revision swap assert membership sets:

- primary Playback/Search/Recent/Guide READY = `{A}`;
- secondary Playback/Search/Recent/Guide READY = `{A, B}`;
- primary direct playback resolves A but rejects hidden B and staged C;
- Recent retains all three logical history rows internally for each profile.

Activate source revision 2, publish B/C matches against catalog revision 2, then assert:

- primary Playback/Search/Recent/Guide READY = `{C}`;
- secondary Playback/Search/Recent/Guide READY = `{B, C}`;
- stale A disappears from every user-facing projection;
- hidden B remains unavailable for the primary profile;
- direct playback resolves C only where profile visibility permits;
- Recent still retains A/B/C internally while projecting current active/visible identities only.

Assertions compare semantic ID sets, not presentation order.

## Task 2 — RED first, minimal production correction

Run the new instrumentation class first.

If it fails:

1. preserve the exact failing surface and seeded identity;
2. change only that DAO/repository owner;
3. add a local regression assertion beside the existing owner tests;
4. rerun the focused contract;
5. do not centralize SQL unless two real owners cannot express the invariant without duplication-induced drift.

If the contract passes immediately, keep it as a characterization/integration guard and do not manufacture a production refactor.

## Task 3 — Bounded-result semantics

Review only affected user-facing copy/contracts:

- bounded lists may say `Показано N`;
- do not claim exact totals from `rows.size`;
- do not infer `hasMore` from an unrelated cap;
- exact COUNT SQL is out of scope unless a current product interaction needs it.

Search already carries explicit `isTruncated`; Channels/Recent already use `Показано` after #107.

## Task 4 — Guide gate specification

This PR does not implement full Guide UI. It records the next database contract:

- bounded canonical-channel slice;
- bounded time interval with a small prefetch margin;
- one programme-overlap predicate;
- active revision + selected-profile visibility before time projection;
- deterministic stable key/tie-break;
- explicit window completeness/truncation where useful;
- no exact total derived from viewport rows;
- no full-guide materialization in Compose.

## Verification

Focused development command:

```powershell
.\gradlew.bat :core:database:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.ActiveChannelTruthContractTest
```

Acceptance:

1. focused test passes on exact head;
2. Full host validation passes;
3. old-edge/current database device path executes non-zero database tests;
4. active revision swap and profile isolation are proven;
5. no new schema version unless a RED proves storage is required;
6. no secret-bearing fixture/log content;
7. zero unresolved review threads.

## Non-goals

- Guide UI/grid;
- Paging3;
- a generic membership table;
- a giant shared SQL fragment;
- provider pseudo-item heuristics without a committed real fixture;
- Room schema v11;
- performance claims;
- Rust/UniFFI or alternate player work.
