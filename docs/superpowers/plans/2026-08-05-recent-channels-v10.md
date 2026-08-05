# Recent Channels v10 Implementation Plan

> **For agentic workers:** execute task-by-task from accepted `main@8fced4dc282eaf07e8160f463c8276d7e48ba01b`; do not stack on historical branches. Use TDD for behavior changes and keep this PR draft until exact-head validation is available.

**Goal:** Add bounded, profile-scoped Recent channels whose only write trigger is the accepted service-owned first-rendered-frame signal.

**Architecture:** Media3 owns successful-playback detection and emits exact profile/channel identity. The app layer converts that event into a wall-clock Recent write through `RecentChannelsRepository`; Room owns bounded history and joins it back to current active/non-hidden catalog truth. Recent never owns stream URLs, headers, credentials, provider locators or playback readiness.

**Tech stack:** Kotlin 2.4.10, Room 3.0.0, Hilt, Coroutines/Flow, Compose TV/Foundation, Media3 1.10.1.

## Global constraints

- One service-owned ExoPlayer/MediaSession remains authoritative.
- Only `onRenderedFirstFrame` from the accepted playback generation may create Recent history.
- No player → Room dependency.
- Persist only profile ID, logical canonical channel ID and successful wall-clock timestamp.
- Default Recent query limit is 20; hard repository/history bound is 50 rows per profile.
- Reads expose only current active source revisions and non-hidden channels.
- `canonicalChannelId` is a logical history identity, intentionally not a physical FK to `canonical_channels`.
- No Paging3, speculative indexes, Rust/UniFFI, alternate player, #100 validators, Guide, fallback/Doctor or #101 CI redesign in this PR.
- Self-hosted CI is currently disabled: source/static work may continue, but the PR must remain draft/unmerged until the exact final head passes the required gates.

---

## Task 1 — Catalog API

**Files:**
- `catalog/api/src/main/kotlin/app/muxtv/catalog/RecentChannelsRepository.kt`
- `catalog/api/src/test/kotlin/app/muxtv/catalog/RecentChannelsRepositoryContractTest.kt`

**Produces:**
- `RecentChannelsQuery(profileId: String, limit: Int = 20)` with hard max 50.
- `RecentChannel(channel: PlayableChannelSummary, lastSuccessfulPlaybackAtEpochMillis: Long)`.
- `RecentChannelWriteResult { Applied, IgnoredOlderOrDuplicate, ProfileUnavailable }`.
- `RecentChannelsRepository.observeRecent(...)` and `recordSuccessfulPlayback(...)`.

- [x] Define RED contracts for blank profile, invalid bounds and negative timestamps.
- [x] Implement bounded public API.
- [x] Rename the unavailable result to `ProfileUnavailable`: a missing logical channel is valid after an accepted first-frame/catalog-cleanup race; only missing profile lifetime rejects the write.
- [x] Keep diagnostic `toString()` output identity-redacted.

## Task 2 — Room v10 history owner

**Files:**
- `core/database/src/main/kotlin/app/muxtv/database/RecentChannelEntity.kt`
- `core/database/src/main/kotlin/app/muxtv/database/RecentChannelsDao.kt`
- `core/database/src/main/kotlin/app/muxtv/database/RoomRecentChannelsRepository.kt`
- `core/database/src/main/kotlin/app/muxtv/database/RecentMigration.kt`
- `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- `core/database/src/androidTest/kotlin/app/muxtv/database/RecentChannelsRepositoryTest.kt`
- `core/database/src/androidTest/kotlin/app/muxtv/database/RecentMigration9To10ContractTest.kt`
- generated: `core/database/schemas/app.muxtv.database.MuxTvDatabase/10.json`

**Schema contract:**

```sql
CREATE TABLE recent_channels (
    profileId TEXT NOT NULL,
    canonicalChannelId TEXT NOT NULL,
    lastSuccessfulPlaybackAtEpochMillis INTEGER NOT NULL,
    PRIMARY KEY(profileId, canonicalChannelId),
    FOREIGN KEY(profileId) REFERENCES profiles(id)
        ON UPDATE NO ACTION ON DELETE CASCADE
)
```

**Lifecycle rationale:** A trusted first-frame event may race catalog cleanup. The write therefore verifies only profile lifetime and stores the logical canonical ID supplied by the accepted playback generation. The read JOIN hides an inactive/deleted ID without deleting bounded history; if the same canonical identity returns, the history becomes visible again.

- [x] RED: newer timestamp wins; equal/older delivery is idempotent.
- [x] RED: profile isolation.
- [x] RED: hard 50-row retention with deterministic `(timestamp DESC, canonicalChannelId ASC)` tie-break.
- [x] RED: hidden/inactive channels are suppressed from reads without deleting history.
- [x] RED: disappearance → catalog cleanup → same canonical ID return restores history visibility.
- [x] RED: first-frame write after catalog cleanup is accepted for an existing profile.
- [x] Implement entity/DAO/repository semantics.
- [x] Register Room v10 and `MIGRATION_9_10`.
- [x] Register `recentChannelsRepository` in `MuxTvDatabaseComponents`.
- [x] Static comparison: entity, migration and historical generated v10 artifact agree on the intended profile-only FK model.
- [ ] Commit the exact generated Room `10.json`; historical generated identity hash is `f6625d546ddfbad62e4e33340b17f490`.
- [ ] Re-run migration validation on the final exact head when build environment/self-hosted is available.

## Task 3 — First-frame persistence bridge

**Files:**
- `app/tv/src/main/kotlin/app/muxtv/di/RecentPlaybackObserver.kt`
- `app/tv/src/main/kotlin/app/muxtv/di/RecentPlaybackModule.kt`
- `app/tv/src/test/kotlin/app/muxtv/di/RecentPlaybackObserverTest.kt`

**Consumes:** `PlaybackFirstFrameEvent(profileId, channelId, activationElapsedMillis)` from the accepted service-owned playback path.

**Produces:** one app-layer observer contribution that asynchronously records wall-clock Recent history without coupling Media3 to Room.

- [x] RED: first frame forwards exact profile/channel identity and captures wall-clock time at this boundary.
- [x] RED: persistence failure cannot fail playback callback.
- [x] Implement `RecentPlaybackObserver`.
- [x] Contribute it through Hilt `@IntoSet`.
- [x] Use process-lifetime `SupervisorJob + Dispatchers.IO`; preserve cancellation semantics inside the child write.
- [x] Static privacy review: no locator/header/title/query/exception content crosses into Recent persistence.
- [ ] Verify Hilt graph and observer test on final exact head when build environment is available.

## Task 4 — Channels ALL / FAVORITES / RECENT TV surface

**Files:**
- `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsViewModel.kt`
- `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- `feature/channels/src/test/kotlin/app/muxtv/feature/channels/ChannelsViewModelTest.kt`
- `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsFocusRestorationTest.kt`

**Behavior contract:**
- ALL/FAVORITES remain `PlaybackCatalog` projections.
- RECENT consumes repository newest-first order without re-sorting in UI.
- Stable canonical channel IDs remain lazy-list/focus identity.
- `Up` from first row returns to the currently selected filter.
- Empty Favorites/Recent exposes one D-pad-reachable “Показать все каналы” recovery action.
- Recent → Player → Back restores the canonical channel, not an index-only position.

- [x] Replace boolean Favorites state with `ChannelsFilter { ALL, FAVORITES, RECENT }`.
- [x] Preserve guide projection by canonical ID and reuse guide snapshot across pure row reorder/metadata changes.
- [x] Add explicit filter left/right focus graph.
- [x] Add Recent empty/failure/loading copy.
- [x] Add ViewModel test for repository order and bounded default query.
- [x] Add D-pad reachability, first-row Up, empty recovery and Player/Back focus contracts.
- [x] Static review found no additional runtime semantic change required.
- [ ] Execute current/old-edge TV key journeys on the final exact head when runner is available.

## Task 5 — App integration and stale-call-site cleanup

**Files:**
- `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- `app/tv/src/androidTest/kotlin/app/muxtv/AppNavigationSourceJourneyTest.kt`

- [x] Inject the required `RecentChannelsRepository` in `MainActivity`.
- [x] Pass the same repository through `AppNavigation` to `ChannelsRoute`.
- [x] Keep Player/Search/Source ownership unchanged.
- [x] Fix stale navigation fixture reference `TargetUnavailable → ProfileUnavailable`.
- [x] Fix stale Room repository fixture reference `TargetUnavailable → ProfileUnavailable`.
- [ ] Compile all instrumentation call sites on final exact head when runner/build environment is available.

## Task 6 — Scope boundary: Home is R2B, not #107

Home is intentionally split out of this PR. The first accepted Recent slice is persistence + Channels; adding a Home shelf now would enlarge review/TV layout scope while Room v10 is still awaiting final validation.

After #107 is accepted and merged, create a fresh branch from accepted `main` for a bounded Home Recent surface. Reuse `RecentChannelsRepository`; do not create a second cache/history model.

## Task 7 — Runner-off static gate

The following work is allowed while self-hosted CI is unavailable:

- [x] fix known compiler call-site mismatches from historical logs;
- [x] compare Room entity/DAO/migration against the historical generated schema;
- [x] review Hilt/navigation/focus/data-flow ownership;
- [x] review privacy and boundedness;
- [x] keep PR draft and document unverified gates.

The following **must not** be claimed complete without a build environment:

- [ ] JVM/Android compile on the final exact head;
- [ ] exact generated Room `10.json` commit/regeneration;
- [ ] migration validation;
- [ ] API old-edge/current database device matrix;
- [ ] API old-edge/current product/TV matrix;
- [ ] release/lint/assembly checks.

## Task 8 — Final acceptance after runner returns

Exact final-head gates, in order:

1. Full host validation: compile, unit tests, lint, instrumentation compile and release assembly.
2. Generated schema export must reproduce Room v10 and agree with `MIGRATION_9_10`.
3. Database device matrix: old supported Android TV edge + current API, non-zero migration/repository tests.
4. Product device matrix: non-zero Recent D-pad and Player/Back journeys on both profiles.
5. Final semantic/privacy review and zero unresolved review threads.
6. Only then mark #107 ready and SHA-guarded squash-merge.
7. Truth-sync accepted main if repository status docs need it.
8. Create clean R2B Home Recent branch, then proceed to #110 → #114 → bounded Guide from accepted main.
