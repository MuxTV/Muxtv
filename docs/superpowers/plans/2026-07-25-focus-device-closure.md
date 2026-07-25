# Focus and Device Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace visual channel selection with deterministic Android TV focus, complete touch-free source-entry and Player-return journeys, execute the deferred Room contracts, and close issues #24 and #25 with evidence.

**Architecture:** Stable catalog channel IDs are the primary focus identity. `LazyListState` preserves the viewport independently, while `FocusAnchor(itemKey, previousIndex, scrollOffset)` provides deterministic fallback when the catalog is reordered or an item disappears. Focus restoration always makes the target visible before calling `FocusRequester.requestFocus()` and never stores locators, credentials, headers, or stream URLs.

**Tech Stack:** Kotlin 2.4.10, Compose BOM 2026.06.00, Android TV Material 1.1.0, Navigation 3 1.1.4, Room 3.0.0, AndroidX Test 1.7.0, Espresso 3.7.0, self-hosted Windows Android TV emulator harness.

## Global Constraints

- Preserve `minSdk = 26`.
- Use stable channel/source identities only; never use stream locators as focus keys or test tags.
- No raw locator, query token, cookie, authorization value, referrer value, preparation token, or exception text in navigation, saved state, semantics, screenshots, logs, traces, or evidence.
- Use Compose Foundation `LazyColumn`; do not add deprecated TV lazy layouts.
- Scroll or restore the viewport before `FocusRequester.requestFocus()`.
- Do not repeatedly request focus during ordinary recomposition.
- Run emulator images sequentially on the self-hosted Windows runner.
- Do not block code implementation on the full release matrix; use current-TV first, then the old supported edge.

---

### Task 1: Pure focus resolution and saved state

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/FocusAnchor.kt`
- Test: `feature/channels/src/test/kotlin/app/muxtv/feature/channels/FocusAnchorTest.kt`

**Interfaces:**
- Produces: `FocusAnchor(itemKey: String, previousIndex: Int, scrollOffset: Int)`
- Produces: `FocusAnchor.resolveAgainst(itemKeys: List<String>): FocusTarget?`

- [x] **Step 1: Add failing resolution contracts**

Cover exact identity after reorder, removed identity, removed first item, shrinking list, and empty list.

- [x] **Step 2: Run the focused JVM test**

```powershell
gradlew.bat :feature:channels:testDebugUnitTest --tests app.muxtv.feature.channels.FocusAnchorTest
```

Expected before implementation: failing assertions or missing types.

- [x] **Step 3: Implement the minimal deterministic policy**

```kotlin
internal fun FocusAnchor.resolveAgainst(itemKeys: List<String>): FocusTarget? {
    if (itemKeys.isEmpty()) return null
    val exactIndex = itemKeys.indexOf(itemKey)
    val targetIndex = when {
        exactIndex >= 0 -> exactIndex
        previousIndex > 0 -> minOf(previousIndex - 1, itemKeys.lastIndex)
        previousIndex <= itemKeys.lastIndex -> previousIndex
        else -> 0
    }
    return FocusTarget(itemKeys[targetIndex], targetIndex, scrollOffset)
}
```

- [x] **Step 4: Verify JVM contracts and Full #195**

Exact-head `5eef28ce5fe4b03d07711b002f13c96fcfa1cfff` passed self-hosted Full #195.

---

### Task 2: Real Channels focus ownership

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsFocusRestorationTest.kt`

**Interfaces:**
- Consumes: `FocusAnchor.resolveAgainst(...)`
- Produces: deterministic initial focus and Player-return restoration for channel rows.
- Test tags: `channel-row-<visible-index>`; tags contain no channel ID or provider data.

- [x] **Step 1: Define the failing Compose restoration contract**

The contract must:

1. assert the first channel initially owns focus;
2. send `Key.DirectionDown`;
3. assert the second channel owns focus;
4. execute `StateRestorationTester.emulateSavedInstanceStateRestore()`;
5. assert the second channel regains actual focus.

- [x] **Step 2: Hoist saved focus state above loading/content branches**

Persist only:

```kotlin
focusedChannelId: String?
focusedChannelIndex: Int
focusedChannelScrollOffset: Int
```

Keep `rememberLazyListState()` independent from focused identity.

- [x] **Step 3: Attach one requester per composed stable channel key**

Use `FocusRequester`, `Modifier.focusRequester(...)`, and a composition-owned map. Remove requesters in `DisposableEffect.onDispose` so removed/reordered rows cannot leave stale requesters.

- [x] **Step 4: Restore once per Channels composition**

Resolve the saved anchor, scroll only when the target is not visible, wait until the target requester exists through `snapshotFlow`, then call `requestFocus()`. Guard the operation with a non-saveable `restorationCompleted` flag.

- [x] **Step 5: Remove the visual selection bullet**

`buttonLabel()` must contain only channel number, display name, group, and variant count.

- [ ] **Step 6: Compile unit and instrumentation sources**

```powershell
gradlew.bat :feature:channels:testDebugUnitTest :app:tv:compileDebugAndroidTestKotlin
```

Expected: zero compilation failures.

- [ ] **Step 7: Execute the Compose focus contract on the current TV image**

```powershell
gradlew.bat :app:tv:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.ChannelsFocusRestorationTest
```

Expected: one non-zero executed test, zero failures.

---

### Task 3: Channels → Player → Back integration

**Files:**
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsPlayerBackJourneyTest.kt`

**Interfaces:**
- Consumes: saved `ChannelsRoute` focus anchor.
- Produces: opening `AppDestination.Player(channelId)` and returning with Back restores the same channel or the documented fallback.

- [ ] **Step 1: Add an integration test fixture**

Provide a fake `PlaybackCatalog`, fake controller boundary, and three stable channels. Navigate to Channels, focus the second row, open Player, press Back, and assert `channel-row-1` is focused.

- [ ] **Step 2: Verify the test fails before navigation integration is corrected**

```powershell
gradlew.bat :app:tv:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.ChannelsPlayerBackJourneyTest
```

- [ ] **Step 3: Keep Channels NavEntry saveable state alive**

Do not place channel ID, locator, headers, or playback request inside focus state. Adjust only Navigation 3 entry/state ownership if the integration test proves the current entry disposal loses `rememberSaveable` state.

- [ ] **Step 4: Verify exact and removed-channel fallback journeys**

Add a second case where the selected channel disappears while Player is open; Back must focus the nearest preceding valid row.

---

### Task 4: Sources and Add Source safe initial focus

**Files:**
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourcesRoute.kt`
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/AddSourceRoute.kt`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/SourceEntryDpadJourneyTest.kt`

**Interfaces:**
- Sources initial target: `Добавить источник` in loading, empty, failed, and content states.
- Add Source Editing target: source-name field.
- HTTP warning target: `Отмена`, not the approval action.
- Confirming target: source-name field when blank; `Добавить` only when the name is already valid.
- Cleanup-pending target: `Повторить очистку`.

- [ ] **Step 1: Add stable secret-free tags**

Use fixed tags such as `sources-add`, `source-name`, `source-locator`, `source-http-cancel`, `source-confirm`, and `source-cleanup-retry`. Never include a source ID or locator.

- [ ] **Step 2: Add state-specific focus tests**

Cover empty Sources, Editing, HTTP approval, Confirming, and cleanup-pending states.

- [ ] **Step 3: Add requesters and one-shot state transitions**

Each UI state owns an explicit safe requester. Request focus only when the state type changes; do not request it on every text edit or recomposition.

- [ ] **Step 4: Execute the touch-free source-entry journey**

Use D-pad/IME to enter name and locator, verify masked semantics, prepare HTTPS, confirm sanitized endpoint, activate, return to Sources, and verify the source appears.

---

### Task 5: Deferred Room contracts and sequential TV matrix

**Files:**
- Reuse: repository-owned PowerShell Android TV harness.
- Reuse: Room 3→4 migration instrumentation test from PR #20.
- Reuse: `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogStagingAtomicityTest.kt`.

- [ ] **Step 1: Execute current-TV tests first**

Run only focus/source-entry journeys and the two Room contracts. Record image package, API level, emulator version, platform-tools version, branch, and exact head SHA.

- [ ] **Step 2: Execute the old supported edge**

Prefer API 26 Android TV. If the updated SDK manager does not provide that image, use the nearest available old TV image and record the exact fallback rather than claiming API 26.

- [ ] **Step 3: Reject zero-test success**

The harness must fail when the requested instrumentation class executes zero tests.

- [ ] **Step 4: Inspect secret boundaries**

Review screenshots, logcat, test reports, and evidence manifests for URL queries, user-info, tokens, cookies, headers, preparation IDs, and raw exception text.

- [ ] **Step 5: Defer API 30 and low-RAM until journeys are stable**

Add those profiles after issues #24 and #25 are closed; do not make them a blocking loop for ordinary implementation commits.

---

### Task 6: Close old tasks and finish PR #34

**Files:**
- Modify: PR #34 description.
- Modify: issue #24 status comment/state.
- Modify: issue #25 status comment/state.
- Modify: `docs/superpowers/plans/2026-07-25-next-execution.md`.

- [ ] **Step 1: Run exact-head Full**

Require unit tests, lint, application build, and instrumentation compilation on the final head.

- [ ] **Step 2: Confirm no unresolved review threads**

Do not mark ready while actionable review comments remain.

- [ ] **Step 3: Mark PR #34 ready and squash merge**

Use an expected head SHA. The squash commit must describe actual focus restoration and executable TV journeys; it must not claim unsupported hardware coverage.

- [ ] **Step 4: Close issue #24**

Close only after source-entry production code, masked semantics, current/old-TV journey, and Room migration execution are evidenced.

- [ ] **Step 5: Close issue #25**

Close only after Channels → Player → Back, removed-channel fallback, Sources/Add Source safe focus, and current/old-TV D-pad journeys are evidenced.

---

### Task 7: Begin Media3 transport hardening after focus merge

**Files:**
- Create a new branch from the PR #34 squash commit.
- Modify: `gradle/libs.versions.toml`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt`
- Add focused transport/reconnect tests.

- [ ] **Step 1: Add `media3-datasource-okhttp` aligned to Media3 1.10.1**
- [ ] **Step 2: Write A→B header-isolation tests before changing production transport**
- [ ] **Step 3: Replace the shared mutable default-header factory with request-scoped immutable configuration**
- [ ] **Step 4: Add cancellation-aware controller connection and disconnect eviction**
- [ ] **Step 5: Keep one process-owned player/session**

## Completion Order

1. Compile and execute the new Channels focus contract.
2. Prove Channels → Player → Back.
3. Add Sources/Add Source safe focus.
4. Run current-TV, then old-edge matrix with Room contracts.
5. Merge PR #34 and close issues #24/#25.
6. Start issue #26 in a separate PR.
