# TV Focus Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining #168 TV focus blockers by making rail geometry fit low-height TV viewports and making Channels restoration operate on the correct Paging generation without destroying stable focus identity.

**Architecture:** Keep ownership local. `MuxTvNavigationRail` selects normal or compact geometry from an actual fit calculation; `ChannelsViewModel` exposes lazy filter-owned Paging streams; `ChannelsRoute` keeps a stable focus subtree across projections and records restoration complete only after successful focus acquisition. The 720p device contract constrains the production Compose tree directly instead of mutating Activity display state. Shell-level focus restoration stays unchanged unless fresh evidence directly implicates it.

**Tech Stack:** Kotlin, Jetpack Compose, Compose for TV Material3, AndroidX Paging Compose, JUnit4, Truth, Android instrumentation tests.

## Global Constraints

- Keep Media3 and playback code untouched in this plan.
- Do not add sleeps, retry loops, or timeout inflation.
- Do not add a global focus engine.
- Preserve 88dp collapsed and 248dp expanded rail widths.
- Preserve original normal Lounge vertical geometry whenever it fits.
- Compact rail must fit all five top-level destinations inside a 360dp-high viewport.
- Exact-head Fast and Android TV focused device must both be green before merge.

---

### Task 1: Make navigation-rail geometry adaptive

**Files:**
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`
- Create: `core/designsystem/src/test/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRailMetricsTest.kt`

**Interfaces:**
- Produces: `internal data class NavigationRailMetrics(...)`
- Produces: `internal fun navigationRailMetrics(availableHeight: Dp, itemCount: Int): NavigationRailMetrics`
- Produces: `internal fun NavigationRailMetrics.requiredHeight(itemCount: Int): Dp`

- [x] **Step 1: Use the failing 720p device journey as RED evidence**

Evidence showed `nav-settings` with zero height at the bottom of a 720p viewport, proving layout invalidity before focus traversal.

- [x] **Step 2: Add host regression tests for the geometry contract**

Tests cover:

- compact five-item rail fits 360dp;
- original normal geometry requires and preserves 428dp;
- 428dp exact fit stays normal;
- 427dp selects compact.

- [x] **Step 3: Implement fit-driven adaptive metrics**

Normal profile preserves the old geometry exactly. Compact profile uses 12dp vertical padding, 40dp brand, 4dp brand-to-items gap, 48dp items, and 4dp inter-item gaps. Selection is based on `normal.requiredHeight(itemCount) <= availableHeight`, not a magic threshold.

- [x] **Step 4: Apply metrics through `BoxWithConstraints`**

The rail uses the actual available height and current item count. Width/focus visuals are unchanged.

### Task 2: Give each Channels filter its own Paging generation without resetting focus identity

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsViewModel.kt`
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- Modify: `feature/channels/src/test/kotlin/app/muxtv/feature/channels/ChannelsViewModelTest.kt`
- Test: existing `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsFocusRestorationTest.kt`

**Interfaces:**
- Produces: `fun rowsFor(filter: ChannelsFilter): Flow<PagingData<ChannelRowUiModel>>`

- [x] **Step 1: Use current cross-filter focus journey as RED evidence**

`favoritesFilterKeepsFocusedFavoriteChannel` rendered the expected surviving row but lost focus after `ALL -> FAVORITES`.

- [x] **Step 2: Replace the mutable-filter-driven single `rows` stream**

`rowsFor(filter)` lazily creates and caches one stream per filter. Inactive filters are not queried.

- [x] **Step 3: Select Paging flow in Compose by filter identity**

`ChannelsRoute` remembers `screenViewModel.rowsFor(filter)` and collects that exact stream as `LazyPagingItems`.

- [x] **Step 4: Preserve stable focus subtree across filter changes**

Do not wrap `ChannelsContent` in `key(filter)`: that would destroy the stable-key row we are trying to preserve. Reset only `restorationCompleted` with `remember(filter)`.

- [x] **Step 5: Make restoration completion reflect actual focus success**

Restoration waits until the target stable key is placed and its `FocusRequester` is registered, crosses a frame boundary, and sets completion only from `requestFocus()` success.

### Task 3: Make the 720p journey deterministic

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SettingsJourneyTest.kt`

- [x] **Step 1: Treat the repeated Activity hierarchy loss as harness evidence**

Fresh DeviceCurrent again failed with `No compose hierarchies found` after `wm size`/Activity recreation, before rail focus could be evaluated.

- [x] **Step 2: Remove display mutation from this layout contract**

Constrain the real `AppNavigation` tree to 640x360dp, equivalent to 1280x720 at the deterministic API36 TV image density. This tests the same Compose constraints without an asynchronous Activity replacement race.

- [x] **Step 3: Keep the existing D-pad journey unchanged after setup**

The test still navigates Home -> rail -> Settings -> Sources -> details and proves the first and last modal actions remain reachable by D-pad.

### Task 4: Exact-head verification

- [ ] **Step 1: Exact-head Self-hosted validation / Fast**

Expected: green.

- [ ] **Step 2: Exact-head Android TV focused device**

Expected: all app TV tests green, including the two prior failures.

- [ ] **Step 3: Stop on new evidence rather than widening architecture**

Do not remove `NavDisplay.focusRestorer()` speculatively. The direct Channels test cannot be affected by it. Any remaining RED must be localized from fresh exact-head evidence before another production change.

- [ ] **Step 4: Update PR stabilization metadata**

Record only the final exact SHA and exact gate results. Do not carry forward predecessor evidence as current acceptance.
