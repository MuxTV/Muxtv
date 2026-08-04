# Recent Channels v10 Implementation Plan

> Execute task-by-task with TDD. Start from accepted `main@8fced4dc282eaf07e8160f463c8276d7e48ba01b`; do not stack on historical branches.

## Goal

Add bounded, profile-scoped Recent channels whose only write trigger is the accepted service-owned first-rendered-frame signal. Persist only profile identity, canonical channel identity, and successful wall-clock time. Read only current active/non-hidden catalog truth while retaining bounded history across temporary source disappearance.

## Non-goals

- no playback success inference from click, command success, BUFFERING, READY or `isPlaying`;
- no player/media3 dependency on Room/database;
- no provider URL/header/title/query text in Recent persistence or diagnostics;
- no unbounded history, Paging3, speculative indexes or new state framework;
- no M3U conditional validators (#100), Guide, fallback/Doctor or CI Phase 2 (#101) in this branch.

## Task 1 — Catalog API contract

Files:
- create `catalog/api/src/main/kotlin/app/muxtv/catalog/RecentChannelsRepository.kt`
- create focused API tests under `catalog/api/src/test/...`

Contract:
- `RecentChannel(channel: PlayableChannelSummary, lastSuccessfulPlaybackAtEpochMillis: Long)`;
- `RecentChannelsQuery(profileId, limit)` with default 20 and hard max 50;
- `RecentChannelWriteResult { Applied, IgnoredOlderOrDuplicate, TargetUnavailable }`;
- `observeRecent(query): Flow<List<RecentChannel>>`;
- `recordSuccessfulPlayback(profileId, channelId, successfulAtEpochMillis)`.

RED first: invalid blank profile, invalid bounds, negative timestamps/result model.

## Task 2 — Room v10 schema and migration

Files:
- create `core/database/src/main/kotlin/app/muxtv/database/RecentChannelEntity.kt`
- create `RecentChannelsDao.kt`
- create `RoomRecentChannelsRepository.kt`
- create `RecentMigration.kt`
- modify `MuxTvDatabase.kt`
- modify `MuxTvDatabaseFactory.kt`
- export `schemas/app.muxtv.database.MuxTvDatabase/10.json`
- add migration/DAO instrumentation tests.

Schema:
- table `recent_channels`;
- composite PK `(profileId, canonicalChannelId)`;
- FK profile -> profiles(id) ON DELETE CASCADE;
- FK canonical -> canonical_channels(id) ON DELETE CASCADE;
- index on `canonicalChannelId` only for FK child lookup;
- `lastSuccessfulPlaybackAtEpochMillis INTEGER NOT NULL`.

Write transaction:
1. require nonblank IDs and nonnegative timestamp;
2. ensure profile + canonical target exists, else `TargetUnavailable`;
3. update only when stored timestamp is older;
4. otherwise insert-if-absent;
5. older/equal delivery is idempotent;
6. after applied write trim to 50 newest rows for that profile using deterministic `(timestamp DESC, canonicalChannelId ASC)` ordering.

Read:
- join recent -> canonical -> stream variants -> provider channels -> sources;
- require provider revision equals source activeRevision;
- LEFT JOIN overlay and exclude hidden channels;
- deterministic newest-first + canonical ID tie-break;
- bounded query limit.

## Task 3 — Preserve canonical identity while Recent references it

Modify `SourceRevisionDao.deleteUnreferencedCanonicalChannels()` so canonical rows referenced by `recent_channels` are not deleted during source activation cleanup.

Tests:
- temporary source disappearance hides channel from Recent read but keeps Recent row/canonical identity;
- after Recent row ages out/deletes, later cleanup can remove truly unreferenced canonical row.

## Task 4 — Database component wiring

Modify `MuxTvDatabaseComponents` / factory to expose `RecentChannelsRepository`; add DAO accessor to `MuxTvDatabase` and register migration 9→10.

Acceptance:
- schema export matches migration;
- migration matrix can open an actual v9 database and preserve existing data.

## Task 5 — Durable first-frame observer in app layer

Files:
- create app-layer singleton observer/module, not in `player/media3`;
- add focused unit tests with fake Recent repository, controlled clock and test scope.

Behavior:
- implement `PlaybackFirstFrameObserver`;
- capture `System.currentTimeMillis()` at Recent boundary;
- asynchronously call `recordSuccessfulPlayback(event.profileId, event.channelId, now)` on process-lifetime IO scope;
- contribute observer via Hilt `@IntoSet`;
- persistence failure must not fail playback or other observers.

Tests:
- exact profile/channel propagated;
- wall-clock captured only at observer boundary;
- duplicate/stale ordering delegated to repository semantics;
- persistence exception isolated.

## Task 6 — Channels TV surface

Files:
- modify `ChannelsViewModel.kt` + tests;
- modify `ChannelsRoute.kt` + D-pad instrumentation tests;
- app wiring as needed.

Replace boolean Favorites filter with explicit `ChannelsFilter { ALL, FAVORITES, RECENT }`.

Behavior:
- ALL/FAVORITES continue using PlaybackCatalog;
- RECENT observes Recent repository and maps to current `PlayableChannelSummary`;
- stable canonical keys and existing `FocusAnchor` restoration remain authoritative;
- D-pad filter graph All ↔ Favorites ↔ Recent;
- Up from rows returns to selected filter;
- Recent empty state has one bounded action back to All;
- OK opens Player; Player→Back restores exact recent channel focus.

## Task 7 — Home bounded Recent surface

Add a small real-repository-backed Recent rail/list to Home only after Tasks 1–6 are stable. No duplicate Recent cache or independent identity model.

Behavior:
- newest first, bounded;
- stable canonical key;
- OK opens Player;
- empty state does not add noise.

If this materially enlarges review scope after data/Channels are green, split Home into a clean R2B follow-up from accepted R2A rather than stack branches.

## Task 8 — Acceptance and merge

Required exact-head evidence:
1. focused API/Room tests;
2. migration 9→10 + schema export validation;
3. Full self-hosted validation;
4. database API26/current matrix because Room schema changes;
5. product API26/current matrix with non-zero connected tests, including Recent→Player→Back;
6. zero unresolved review threads;
7. base-to-head semantic review confirms no provider secrets, URLs, headers or programme/query text persisted.

After acceptance:
- squash merge Recent;
- truth-sync if repository status docs require it;
- only then allow #100 to own the next Room migration;
- start bounded Guide from the newly accepted main.
