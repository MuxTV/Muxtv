# TV Focus Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining #168 TV focus blockers by making rail geometry fit 720p and making Channels restoration operate on the correct Paging generation.

**Architecture:** Keep ownership local. `MuxTvNavigationRail` derives compact geometry from available height; `ChannelsViewModel` exposes filter-owned paging streams; `ChannelsRoute` waits for the active generation's target row to be placed and records completion only after successful focus acquisition. Shell-level focus restoration stays unchanged unless fresh evidence proves it is still involved.

**Tech Stack:** Kotlin, Jetpack Compose, Compose for TV Material3, AndroidX Paging Compose, JUnit4, Truth, Android instrumentation tests.

## Global Constraints

- Keep Media3 and playback code untouched in this plan.
- Do not add sleeps, retry loops, or timeout inflation.
- Do not add a global focus engine.
- Preserve 88dp collapsed and 248dp expanded rail widths.
- Compact rail must fit all five top-level destinations inside a 360dp-high viewport.
- Exact-head Fast and Android TV focused device must both be green before merge.

---

### Task 1: Make navigation-rail geometry adaptive

**Files:**
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`
- Create: `core/designsystem/src/test/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRailMetricsTest.kt`

**Interfaces:**
- Produces: `internal data class NavigationRailMetrics(...)`
- Produces: `internal fun navigationRailMetrics(availableHeight: Dp): NavigationRailMetrics`
- Produces: `internal fun NavigationRailMetrics.requiredHeight(itemCount: Int): Dp`

- [ ] **Step 1: Use the already failing 720p device journey as RED evidence**

Current exact-head evidence shows `nav-settings` at `(l=24, t=680, r=472, b=680)px`, proving a zero-height last rail item at 720p.

- [ ] **Step 2: Add unit regression tests for the geometry contract**

```kotlin
package app.muxtv.designsystem.component

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvNavigationRailMetricsTest {
    @Test
    fun `compact rail fits five destinations inside 360dp`() {
        val metrics = navigationRailMetrics(360.dp)

        assertThat(metrics.requiredHeight(itemCount = 5)).isAtMost(360.dp)
        assertThat(metrics.itemHeight).isEqualTo(48.dp)
    }

    @Test
    fun `normal rail preserves lounge geometry above compact threshold`() {
        val metrics = navigationRailMetrics(720.dp)

        assertThat(metrics.itemHeight).isEqualTo(56.dp)
        assertThat(metrics.brandHeight).isEqualTo(48.dp)
        assertThat(metrics.verticalPadding).isEqualTo(20.dp)
        assertThat(metrics.itemGap).isEqualTo(8.dp)
    }
}
```

- [ ] **Step 3: Implement minimal adaptive metrics**

Use a 400dp threshold. Compact values are 12dp vertical padding, 40dp brand, 48dp item, 4dp gap. Normal values preserve 20dp, 48dp, 56dp, 8dp.

- [ ] **Step 4: Apply metrics through `BoxWithConstraints`**

Replace fixed rail vertical geometry with the selected metrics. Remove the separate 12dp spacer between brand and items. Pass height into brand and item composables.

- [ ] **Step 5: Verify host tests**

Run:

```powershell
./gradlew.bat :core:designsystem:testDebugUnitTest --tests "app.muxtv.designsystem.component.MuxTvNavigationRailMetricsTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt \
        core/designsystem/src/test/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRailMetricsTest.kt
git commit -m "fix(tv): fit lounge rail to compact viewports"
```

### Task 2: Give each Channels filter its own Paging generation

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsViewModel.kt`
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- Test: existing `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsFocusRestorationTest.kt`

**Interfaces:**
- Produces: `fun rowsFor(filter: ChannelsFilter): Flow<PagingData<ChannelRowUiModel>>`

- [ ] **Step 1: Use current failing cross-filter focus journey as RED evidence**

`favoritesFilterKeepsFocusedFavoriteChannel` currently renders the expected `channel-row-0` but it has `Focused=false` after `ALL -> FAVORITES`.

- [ ] **Step 2: Replace the mutable-filter-driven single `rows` stream**

Create one cached stream per `ChannelsFilter` in `ChannelsViewModel`, each querying its exact `ChannelBrowseFilter`. Keep the mutable filter only as selected UI state.

- [ ] **Step 3: Select the stream in Compose by filter identity**

In `ChannelsRoute`:

```kotlin
val rowsFlow = remember(screenViewModel, filter) {
    screenViewModel.rowsFor(filter)
}
val rows = rowsFlow.collectAsLazyPagingItems()
```

This prevents `filter = FAVORITES` from sharing one `LazyPagingItems` instance whose active generation can still be `ALL`.

- [ ] **Step 4: Make restoration completion reflect actual focus success**

After resolving the target id and scrolling, wait until both conditions are true in the current generation:

```kotlin
val requester = snapshotFlow {
    val placed = listState.layoutInfo.visibleItemsInfo.any { item ->
        item.key == targetId
    }
    if (placed) focusRequesters[targetId] else null
}.filterNotNull().first()

withFrameNanos { }
restorationCompleted = requester.requestFocus()
```

Do not set completion to true after a failed focus request.

- [ ] **Step 5: Verify focused Android tests**

Run the focused Channels journey and the 720p Settings journey on the current TV emulator/API36 harness.

Expected: both previously failing tests PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsViewModel.kt \
        feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt
git commit -m "fix(tv): restore channels focus on filter-owned paging"
```

### Task 3: Exact-head verification

**Files:** no production changes unless evidence exposes a new root cause.

- [ ] **Step 1: Run repository Fast validation**

Expected: green.

- [ ] **Step 2: Run Android TV DeviceCurrent**

Expected: all app TV tests green, including the two prior failures.

- [ ] **Step 3: Stop if shell `focusRestorer()` is still implicated**

Do not remove `NavDisplay.focusRestorer()` speculatively. If fresh evidence remains red and points to shell restoration, return to root-cause analysis and make that a separate atomic change.

- [ ] **Step 4: Update PR stabilization note with exact-head evidence**

Record the new SHA and exact gate results without carrying forward stale evidence.
