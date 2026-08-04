# Recent Channels v10 Implementation Plan

> Execute task-by-task with TDD from accepted `main@8fced4dc282eaf07e8160f463c8276d7e48ba01b`; do not stack on historical branches.

## Goal

Add bounded, profile-scoped Recent channels whose only write trigger is the accepted service-owned first-rendered-frame signal. Persist only profile ID, logical canonical channel ID and successful wall-clock time. Read only current active/non-hidden catalog truth while retaining bounded history across temporary source disappearance.

## Non-goals

No click/command/BUFFERING/READY/`isPlaying` success inference; no player→Room dependency; no provider URLs/headers/titles/query text in Recent persistence; no Paging3/speculative indexes/new state framework; no #100 validators, Guide, fallback/Doctor or #101 CI redesign in this PR.

## Task 1 — Catalog API

Create `RecentChannelsRepository` with:
- `RecentChannelsQuery(profileId, limit)` default 20 / hard max 50;
- `RecentChannel(PlayableChannelSummary, timestamp)`;
- typed write result: `Applied`, `IgnoredOlderOrDuplicate`, `TargetUnavailable`;
- `observeRecent` and `recordSuccessfulPlayback`.

RED first for bounds, blank profile and negative timestamps.

## Task 2 — Room v10 owner

Create `recent_channels` with composite PK `(profileId, canonicalChannelId)`, profile FK `ON DELETE CASCADE`, and `lastSuccessfulPlaybackAtEpochMillis`.

**Intentional lifecycle rule:** `canonicalChannelId` is a bounded logical identity, not a physical FK. The DAO verifies that the canonical target exists when success is recorded. Later catalog cleanup may remove an inactive canonical row without cascading away Recent history. The active-truth read JOIN then hides that history; if the same canonical ID returns, it becomes visible again. The hard 50-row/profile cap bounds orphaned history without retaining catalog tombstones or polluting user overlays.

Write transaction:
1. validate IDs/timestamp;
2. verify profile + canonical target exists;
3. update only if stored timestamp is older;
4. otherwise insert-if-absent;
5. older/equal delivery is idempotent;
6. trim to 50 newest rows using `(timestamp DESC, canonicalChannelId ASC)`.

Read:
- recent → canonical → active stream/provider/source truth;
- exclude hidden overlays;
- newest first with canonical ID tie-break;
- bounded limit.

Files include entity, DAO, Room repository, migration 9→10, DB registration/factory, schema 10 export and instrumentation contracts.

## Task 3 — Durable first-frame observer

Create an app-layer `PlaybackFirstFrameObserver`, never a player/database coupling:
- wall clock captured at this boundary;
- async repository write on process-lifetime IO scope;
- Hilt `@IntoSet` contribution;
- persistence exception isolated from playback and other observers.

Tests cover exact profile/channel, wall-clock capture and failure isolation.

## Task 4 — Channels TV surface

Replace boolean filter state with `ChannelsFilter { ALL, FAVORITES, RECENT }`.
- ALL/FAVORITES remain PlaybackCatalog-backed;
- RECENT uses the Recent repository;
- preserve stable canonical keys and existing FocusAnchor restoration;
- D-pad All ↔ Favorites ↔ Recent;
- Up returns to selected filter;
- bounded Recent empty action returns to All;
- Recent→Player→Back restores exact channel focus.

## Task 5 — Home bounded Recent surface

After Tasks 1–4 are stable, add a small real-repository-backed Recent surface to Home. No duplicate cache/identity model. Split to clean R2B only if Home materially enlarges review scope.

## Task 6 — Acceptance

Exact-head gates:
1. API/Room tests;
2. migration 9→10 + generated schema validation;
3. Full validation;
4. database API26/current matrix;
5. product API26/current matrix including Recent→Player→Back;
6. zero unresolved review threads;
7. privacy review confirms no provider secrets, URLs, headers, programme text or query text persisted.

After merge: truth-sync if needed; only then let #100 own a later DB migration; start bounded Guide from accepted main.
