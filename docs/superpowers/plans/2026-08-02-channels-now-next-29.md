# Channels Now/Next Vertical Slice Implementation Plan

> **Execution policy:** YAGNI here means *do not build unused product subsystems*, not *avoid sound architecture*. Stable ownership, explicit extension seams, screen-level state holders, immutable UI state and destination-scoped lifecycle are implemented now because upcoming Favorites/Search/Guide work will reuse them.

**Goal:** Turn the bounded EPG projection from PR #80 into a user-visible Channels experience while establishing a scalable screen-state boundary for the daily-use #29 work.

**Architecture:**

- `PlaybackCatalog` remains the channel/catalog source of truth.
- `EpgGuideRepository` remains the guide projection source of truth.
- `ChannelsViewModel` is the screen-level orchestration owner: channel Flow, bounded Now/Next reads, EPG invalidation and one-shot programme-boundary timing.
- `ChannelsRoute` is a renderer plus TV-specific UI-element state owner: focus, list position and `FocusAnchor`.
- Navigation 3 supplies a `ViewModelStoreOwner` per `NavEntry`, so the Channels state holder survives `Channels → Player → Back` while the entry remains in the back stack and clears when that destination is actually removed.
- The feature remains DI-framework agnostic. App/Hilt supplies repositories to the route; the route creates the ViewModel through the AndroidX factory API.

This follows current Android guidance: UDF, screen-level ViewModels, immutable `StateFlow` UI state, lifecycle-aware collection and Navigation 3 entry-scoped ViewModel ownership.

## Global constraints

- Branch remains stacked on PR #80 until #80 is merged; afterwards rebuild/rebase so Room v7/matching files disappear from #81's diff.
- `NowNextQuery.MAX_CHANNEL_IDS = 200`; Channels uses the same bound.
- Existing canonical channel identity, `FocusAnchor`, nearest-previous fallback and Player → Back restoration remain authoritative.
- Guide failure degrades to channel-only rows and never makes the catalog unavailable.
- `NO_GUIDE` and `SOURCE_CONFLICT` never fabricate programme content.
- Provider-controlled programme/channel values are product content only; diagnostic strings/test tags stay payload-free.
- No global EPG scheduler/ticker. The screen state owner keeps exactly one one-shot boundary job for the current guide snapshot.
- No Favorites/Recent/Search/Guide implementation in this PR, but their future state/actions must fit the same screen-state/UDF boundary without moving repository logic back into composables.
- Do not create a reusable design-system channel row until another real consumer exists.

---

## File / boundary map

- `feature/channels/.../ChannelRowProjection.kt` — pure projection and earliest-boundary calculation.
- `feature/channels/.../ChannelsViewModel.kt` — screen state owner and bounded orchestration.
- `feature/channels/.../ChannelsRoute.kt` — lifecycle-aware state collection, rendering and D-pad/focus state only.
- `feature/channels/.../ChannelsViewModelTest.kt` — screen-owner contracts.
- `app/tv/.../navigation/AppNavigation.kt` — Navigation 3 saveable + ViewModel-store decorators.
- `app/tv/.../di/AppModule.kt` / `MainActivity.kt` — expose/pass existing repositories.
- `app/tv/src/androidTest/...` — existing TV journey coverage; no second harness.

---

## Task 1 — Pure row projection and clock contract

**Implemented in tree:**

- [x] `ChannelRowProjection` joins only by canonical channel ID.
- [x] Catalog order is preserved.
- [x] `READY`, `NO_GUIDE` and `SOURCE_CONFLICT` remain explicit.
- [x] Current/next titles are never invented.
- [x] Earliest future boundary ignores null/past boundaries and is order-independent.
- [x] Focus identity is not part of the projection model.

**Verification still required on exact final head:** focused unit test + existing `FocusAnchorTest`.

---

## Task 2 — App graph and Navigation 3 lifecycle ownership

**Implemented in tree:**

- [x] Existing `EpgGuideRepository` is exposed from `MuxTvDatabaseComponents` through the app graph.
- [x] `MainActivity` / `AppNavigation` pass the existing repository interfaces into Channels.
- [x] Added `androidx.lifecycle:lifecycle-viewmodel-navigation3` aligned with lifecycle `2.11.0`.
- [x] `NavDisplay` explicitly installs both `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`.
- [x] No Hilt dependency is introduced into `feature:channels`; the feature remains interface-driven.

**Why this is not speculative:** upcoming Channels actions/filters and real Guide/Search routes need destination-scoped screen state. Activity-scoped ViewModels would retain unrelated destinations too long; composition-owned orchestration would be destroyed/restarted at the wrong boundary.

---

## Task 3 — Screen-level UDF state owner

**Implemented in tree:**

- [x] Added `ChannelsViewModel`.
- [x] Exposes one immutable `StateFlow<ChannelsUiState>`.
- [x] Owns `PlaybackCatalog.observeChannels(ChannelQuery(limit = 200))`.
- [x] Owns bounded `EpgGuideRepository.getNowNext(...)` reads.
- [x] Owns guide invalidation subscription.
- [x] Owns a single one-shot programme-boundary job.
- [x] Uses generation ownership to prevent a stale guide result from publishing after the visible channel set changes.
- [x] Preserves existing guide data when channel metadata/order changes but the canonical channel set is unchanged.
- [x] Serializes competing invalidation/boundary reloads through a local mutex.
- [x] Ordinary guide read failures publish channel-only rows; cancellation remains authoritative.
- [x] Catalog failure still produces explicit `Failed` state.

**RED contracts added:**

- catalog + guide combine into row state;
- ordinary guide failure degrades rather than failing Channels;
- guide invalidation reloads the bounded projection.

**Verification still required:** `:feature:channels:testDebugUnitTest --tests '*ChannelsViewModelTest'` and feature compile on exact final head.

---

## Task 4 — Lifecycle-aware Compose rendering

**Implemented in tree:**

- [x] Removed catalog/EPG orchestration from the composable.
- [x] `ChannelsRoute` obtains its state holder via AndroidX `viewModelFactory`/`viewModel()`.
- [x] UI consumes `StateFlow` via `collectAsStateWithLifecycle()`.
- [x] `rememberSaveable` continues to own focus anchor/scroll UI-element state.
- [x] Dedicated TV row renders number/name, metadata, current and next programme.
- [x] Direct `OK → Player` remains unchanged.

**Remaining acceptance check:** verify focus visuals do not produce unacceptable row geometry displacement on TV Material focused scale; solve with explicit row focus scale only if runtime evidence shows a problem.

---

## Task 5 — Stacked integration / previous issue closure

1. Freeze #80 product scope unless a factual defect is found.
2. Obtain exact-head #80 Full + API26/API-current evidence.
3. Mark #80 ready and squash-merge with expected head SHA.
4. Close #71 and #28 only after that evidence.
5. Synchronize `.work` repository truth separately: Room v7, #76/#71/#28 complete, #29 active.
6. Rebuild #81 on merged `main`; verify the remaining diff is only app/navigation/channels product work.
7. Run focused Channels unit/app compile plus existing TV focus/player-back journeys.
8. Merge #81 as the first #29 package.

---

# Next extensible product boundaries after #81

## #29A — Channel user preferences / Favorites

Do **not** bolt mutation methods onto random UI classes. The persisted aggregate already exists as `user_channel_overlays` and contains favorite/custom-name/channel-number/hidden state.

Create one public profile-scoped **channel preference/overlay repository boundary** that owns mutations and can later support:

- favorite;
- hidden;
- custom name;
- custom channel number/order semantics if required.

`PlaybackCatalog` remains optimized for active playback/catalog reads; user preference writes stay a separate responsibility. No second favorites table is needed.

## #29B — Recent

Add a dedicated bounded profile-scoped successful-playback history store only because this concept does not belong in the existing overlay aggregate.

Required semantics:

- canonical channel ID;
- successful playback timestamp;
- bounded retention / deterministic ordering;
- write only after confirmed playback start, not click/request/buffering.

Likely Room v8, but only after checking whether another existing persisted boundary can represent this correctly.

## #29C — Search

Build a `SearchQuery`/search repository contract rather than embedding increasingly complex SQL semantics in a composable.

Initial indexed/bounded fields:

- effective channel name;
- raw provider name;
- group;
- channel number;
- active programme title.

FTS/fuzzy/transliteration remain optional strategy implementations behind the same API if measured UX/performance later requires them.

## #29D — Guide

Expose a dedicated bounded time-window guide query contract:

- profile;
- canonical IDs/window;
- `from` / `to` time;
- result/page bound.

Never expose the Room programme table or an unbounded all-day/all-channel list directly to Compose.

## #33 — TV presentation layer

Continue after the real destinations exist:

- finish channel-row focus/geometry polish;
- player overlay/state holder;
- Sources simplification;
- real Search/Guide navigation;
- logo loader/cache policy behind a credential-safe repository;
- physical-device focus/readability QA.

## #30 — Playback recovery

Use an explicit playback-attempt state machine/policy boundary, not ad-hoc retries in Player UI:

- preferred variant;
- bounded fallback sequence;
- typed failure families;
- attempt/time budgets;
- cancellation/session ownership;
- diagnostic observation stream consumed later by TV Doctor.

Media3 remains the engine behind this policy. The existing process-owned `MediaSessionService` architecture already aligns with current official Media3 guidance.

## #27 / native decision

Finish the existing comparable five-run series and variance interpretation. Use the result to choose performance work, not to justify a rewrite in advance.

Rust/UniFFI remains a valid extension option behind measured hot-path interfaces, but only after:

1. repeatable CPU/memory bottleneck;
2. optimized Kotlin/Room/Media3 path still misses target;
3. FFI prototype demonstrates net improvement including crossing/ABI cost;
4. ADR records packaging/crash/debug/maintenance trade-offs.

This keeps the architecture expandable without turning a possible future implementation language into a present ownership dependency.
