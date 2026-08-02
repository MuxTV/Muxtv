# Channels Now/Next Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for each behavior change. Keep this branch stacked on PR #80 until #80 is merged, then rebase/retarget so the EPG foundation disappears from this diff.

**Goal:** Turn the new bounded EPG projection into a user-visible Channels experience by showing current/next programme data in dedicated remote-first channel rows while preserving the existing focus owner and direct `OK → playback` behavior.

**Architecture:** Reuse `PlaybackCatalog` as the channel list owner and `EpgGuideRepository` as the only guide projection source. The Channels composition owns guide refresh lifecycle: it performs one bounded query for the current channel set, reloads on the payload-free Room invalidation flow, and schedules only one lifecycle-scoped delay to the earliest `nextBoundaryEpochMillis`. No global scheduler, cache, ViewModel/MVI layer, new persistence table, fuzzy matching, or second focus model is introduced.

**Tech Stack:** Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, existing `catalog:api` contracts from PR #80.

## Global Constraints

- Branch is stacked on PR #80 head `6f05b0db27e8b8d564caffab43d372c197c157fd` until #80 merges.
- `NowNextQuery.MAX_CHANNEL_IDS = 200`; Channels already uses the same 200-row bound, so no chunking/full-guide materialization is needed in this slice.
- Existing `FocusAnchor`, stable canonical channel IDs, nearest-previous removal fallback and Player → Back restoration remain authoritative.
- Guide failure must degrade to channel-only rows; it must never make the whole Channels screen unavailable.
- `GuideProjectionState.NO_GUIDE` and `SOURCE_CONFLICT` must not fabricate programme titles or progress.
- Programme titles may be rendered as product content, but diagnostic strings/test tags must not include provider-controlled programme/channel values.
- One-shot boundary timing lives in the Channels composition and is cancelled automatically when the route leaves composition.
- No Favorites/Recent/Search/Guide screen storage or UI in this slice; those are subsequent #29 packages.
- Do not add a design-system component until the dedicated row has more than one real consumer.

---

## File/Boundary Map

- `feature/channels/.../ChannelRowProjection.kt` — pure mapping from channel + optional `ChannelNowNext` to UI-safe row content and earliest future boundary calculation.
- `feature/channels/.../ChannelRowProjectionTest.kt` — deterministic projection/clock RED→GREEN contracts.
- `feature/channels/.../ChannelsRoute.kt` — composition-owned bounded guide loading, Room invalidation subscription, one-shot boundary refresh and dedicated row rendering; existing focus code remains in place.
- `app/tv/.../di/AppModule.kt` — expose the already-created `EpgGuideRepository` from `MuxTvDatabaseComponents`; no new owner.
- `app/tv/.../MainActivity.kt` and `app/tv/.../navigation/AppNavigation.kt` — pass the existing singleton repository to Channels.
- `app/tv/src/androidTest/...` — extend the existing Channels journey only where needed to prove guide enrichment does not break D-pad/Back focus behavior; do not create a second journey harness.

---

### Task 1: Pure row projection and boundary contract

**Files:**
- Create: `feature/channels/src/test/kotlin/app/muxtv/feature/channels/ChannelRowProjectionTest.kt`
- Create: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelRowProjection.kt`

**Produces:**
- `ChannelRowProjection`
- `projectChannelRows(channels, guide)`
- `earliestFutureGuideBoundary(rows, nowEpochMillis)`

- [ ] **Step 1 — RED:** add tests proving projection joins only by canonical channel ID, preserves channel order, renders current/next only for `READY`, keeps `NO_GUIDE`/`SOURCE_CONFLICT` explicit without invented titles, and ignores past boundaries.
- [ ] **Step 2 — verify RED:** run `./gradlew :feature:channels:testDebugUnitTest --tests '*ChannelRowProjectionTest'`; expected failure is unresolved projection symbols, not infrastructure.
- [ ] **Step 3 — GREEN:** implement the smallest immutable row projection model and pure functions; copy no provider locator/source/credential fields into it.
- [ ] **Step 4 — verify GREEN:** run the focused test plus existing `FocusAnchorTest`.
- [ ] **Step 5 — commit:** `feat: add channels now-next row projection`.

### Task 2: Expose existing guide repository to the app graph

**Files:**
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`

**Consumes:** `MuxTvDatabaseComponents.epgGuideRepository : EpgGuideRepository`

- [ ] **Step 1 — RED:** update/extend the existing app navigation model/compile contract so Channels requires the repository dependency and the app graph initially fails to compile without a provider/pass-through.
- [ ] **Step 2 — verify RED:** run `:app:tv:testDebugUnitTest` or the narrowest compile/test task that demonstrates the missing graph dependency.
- [ ] **Step 3 — GREEN:** add a simple Hilt provider returning `components.epgGuideRepository`; inject in `MainActivity`; pass through `AppNavigation` to `ChannelsRoute`.
- [ ] **Step 4 — verify GREEN:** app unit tests + debug compile.
- [ ] **Step 5 — commit:** `feat: expose guide projection to channels`.

### Task 3: Composition-owned guide reload lifecycle

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- Test: `feature/channels/src/test/kotlin/app/muxtv/feature/channels/ChannelRowProjectionTest.kt`

**Behavior:**
1. channel list still comes only from `PlaybackCatalog.observeChannels(ChannelQuery(limit=200))`;
2. once channel IDs are available, query `EpgGuideRepository.getNowNext` with those IDs and current wall clock;
3. collect `observeDataChanges()` and refresh the same bounded projection;
4. schedule exactly one `delay` to the earliest future boundary returned by the current rows, then refresh;
5. cancellation exits immediately; ordinary guide read failure clears guide enrichment but leaves channel content usable.

- [ ] **Step 1 — RED:** add pure boundary tests for future/past/null boundaries and order-independent minimum selection.
- [ ] **Step 2 — verify RED.**
- [ ] **Step 3 — GREEN:** wire lifecycle effects using existing Compose state; no `GlobalScope`, WorkManager, ViewModel or repeating ticker.
- [ ] **Step 4 — verify GREEN:** focused unit tests + `:feature:channels:compileDebugKotlin`.
- [ ] **Step 5 — commit:** `feat: refresh channels guide at data and time boundaries`.

### Task 4: Dedicated TV channel row over existing focus ownership

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`

**Presentation:**
- primary line: number (when present) + channel display name;
- secondary metadata: group and useful variant count only when present;
- guide line for `READY`: current title, then compact next title when present;
- `NO_GUIDE`: omit programme content rather than inventing a placeholder programme;
- `SOURCE_CONFLICT`: show a generic `Программа недоступна` status without provider/source details;
- keep one stable row geometry across focus states; focus changes styling, not size;
- same stable test tag `channel-row-{index}` and same `FocusRequester`/`FocusAnchor` capture.

- [ ] **Step 1 — RED:** extend existing application/Compose journey only enough to assert direct row activation still navigates to Player and focus restoration still uses canonical identity after the rendering change.
- [ ] **Step 2 — verify RED against the pre-row implementation if the assertion targets the new semantics/presentation contract.
- [ ] **Step 3 — GREEN:** replace `MuxTvActionButton` usage for channel rows with one feature-local TV Material surface/click target; do not modify the focus algorithm.
- [ ] **Step 4 — verify GREEN:** feature/app unit tests and instrumentation compile.
- [ ] **Step 5 — commit:** `feat: show now-next in dedicated channel rows`.

### Task 5: Stacked integration and handoff

**Files:**
- Update this plan status.
- Update issue #29 with exact delivered/deferred scope.
- Do not change #33 D1/D3/D4/D5/D6 in this PR.

- [ ] Wait only for factual #80 outcome; do not merge this branch while its base work is unmerged.
- [ ] After #80 merges, rebase/retarget this branch so Room v7/matching files are no longer part of this diff.
- [ ] Run ordinary Full and the existing old-edge/current app device journey; inspect product failures before changing harness configuration.
- [ ] Final diff/redaction/review-thread check.
- [ ] Merge this vertical slice as the first #29 package.
- [ ] Next package after merge: Favorites mutation using existing `user_channel_overlays.isFavorite` and `ChannelQuery.favoritesOnly`; no new favorites table/repository.

---

## Subsequent #29 Packages (separate plans/PRs)

1. **Favorites:** one mutation boundary over `UserChannelOverlay`; survive source refresh by canonical ID.
2. **Recent:** Room v8 only if no existing persistence boundary can represent profile-scoped successful playback starts; bounded retention, update only after confirmed playback start.
3. **Search:** extend the existing bounded SQL search to channel number and active programme metadata before considering FTS; no fuzzy/embeddings.
4. **Guide:** bounded lazy time window over canonical channel IDs; never materialize the complete guide in Compose.
5. **Home integration:** Favorites/Recent rails only after those real stores exist.

## Parallel/Following Project Roadmap

- Finish #27 evidence series in parallel; use it to decide hard/warning/descriptive thresholds, not to redesign runtime pre-emptively.
- Continue #33 D3/D4 and later D1 after real Guide/Search destinations exist.
- Implement #30 bounded variant fallback before TV Doctor presentation.
- Execute #31 release/Baseline Profile/physical-device alpha gate last.
- Rust/UniFFI remains deferred unless #27 or physical evidence identifies a reproducible CPU/memory hot path that optimized Kotlin cannot satisfy.
