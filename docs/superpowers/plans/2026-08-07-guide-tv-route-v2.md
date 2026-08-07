# Guide TV Route v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land issue #29's remaining Guide TV route/UI as a clean, bounded, D-pad-first feature on accepted `main` without reintroducing superseded Guide-data, transport, URL-policy, Room, or CI history.

**Architecture:** `:feature:guide` is a destination-scoped Compose feature backed only by the accepted `GuideWindowRepository`. The route keeps one bounded 30-channel viewport, retries programme truncation by narrowing time only, preserves canonical channel/programme identity for focus, and navigates to the existing Player using only canonical channel ID. The v2 branch is rebuilt from accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`; the historical Guide UI branch is source material only.

**Tech Stack:** Kotlin, Android/Compose for TV, Navigation3, Hilt, coroutines/StateFlow, accepted Room-backed `GuideWindowRepository` API.

## Global Constraints

- Self-hosted runner is currently unavailable: do not claim RED/GREEN, compile-green, instrumentation-green, or device-green without fresh execution evidence.
- Do not trigger or reopen self-hosted CI merely to create queued work while the runner is unavailable.
- Never materialize the full catalog or full EPG in Compose/state.
- One visible Guide page contains at most 30 channels; paging replaces the page rather than appending unbounded state.
- Programme window starts at 6h and may narrow only `6h -> 3h -> 90m -> 45m`; persistent truncation becomes `Incomplete`.
- `READY`, `NO_GUIDE`, and `SOURCE_CONFLICT` stay explicit UI states.
- Focus identity is canonical channel ID plus optional stable `GuideProgrammeKey`; indices are fallback coordinates only.
- Player navigation carries only canonical channel ID; no locator, header, credential, provider, profile, or EPG-source detail enters route state or semantics tags.
- Dense D-pad focus has no scale/position animation and no preview-key synthesized click.
- No Room entity/migration/schema-version change, no second player, no alternate Guide repository, no Paging3.

---

### Task 1: Lock the clean v2 ownership boundary

**Files:**
- Create: `docs/superpowers/plans/2026-08-07-guide-tv-route-v2.md`
- Inspect only: historical `feat/guide-tv-route-29@1d8eb91cc13b668545b39b44992a0696f5f9362f`

**Interfaces:**
- Consumes: accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99` and historical delta `985fbda8bd90ebde0f29fc1adc0632a8a05704a2..1d8eb91cc13b668545b39b44992a0696f5f9362f`.
- Produces: one active owner branch `feat/guide-tv-route-29-v2` containing only Guide UI/app-wiring delta plus this plan.

- [x] **Step 1: Confirm the v2 branch starts exactly at accepted main**

Expected ref before implementation: `ef9f008a17e5e8fb8519d8e0bc05446ede675a99`.

- [x] **Step 2: Compare the historical Guide UI delta**

Expected scope: 17 changed paths, 35 commits, 0 behind relative to its historical Guide-data head.

- [x] **Step 3: Compare historical Guide-data head against current main**

Expected result: current-main changes do not overlap any of the 17 Guide UI paths, so whole-file porting cannot overwrite accepted transport/source/Room work.

- [x] **Step 4: Port only the historical Guide UI paths**

Mechanical port commit `4e3ea449d78fce9153ce377bf05c68adf11fec9d` was created from exact historical blobs. Net scope excludes `core/database/**`, `core/network/**`, `player/media3/**`, Room schema JSON, migrations, and workflow changes.

- [x] **Step 5: Commit the mechanical port separately**

Committed as `feat(guide): restack TV Guide route on accepted main`.

---

### Task 2: Restore the Guide feature/module boundary

**Files:**
- Create/port: `feature/guide/build.gradle.kts`
- Create/port: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideFocus.kt`
- Create/port: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideRoute.kt`
- Create/port: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideUiState.kt`
- Create/port: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideViewModel.kt`
- Create/port: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideViewportPolicy.kt`
- Modify/port: `settings.gradle.kts`
- Modify/port: `app/tv/build.gradle.kts`

**Interfaces:**
- Consumes: `GuideWindowRepository`, `GuideChannelCursor`, programme/window API already accepted via #128.
- Produces: `GuideRoute(...)`, destination-scoped `GuideViewModel`, bounded viewport/focus policies.

- [x] **Step 1: Port the module and production feature files byte-for-byte from the historical UI branch**

The mechanical port remains review-separable from later integration corrections.

- [x] **Step 2: Port module registration/dependency wiring**

`settings.gradle.kts` includes `:feature:guide`; `app/tv` depends on the module exactly once in the authored diff.

- [x] **Step 3: Static contract review while runner is offline**

Source review confirmed the authored `GuideViewModel` keeps a 30-channel page, four time-only truncation attempts, generation cancellation/suppression, bounded previous-page history, secret-free failure state, and explicit `Incomplete` on identity mismatch/persistent truncation. These are source-level findings only until executed.

---

### Task 3: Integrate Guide into the current app navigation tree

**Files:**
- Modify/port: `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- Modify/port: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Modify/port: `app/tv/src/androidTest/kotlin/app/muxtv/AppNavigationSourceJourneyTest.kt`
- Create/port: `app/tv/src/androidTest/kotlin/app/muxtv/TestGuideWindowRepository.kt`
- Create/port: `app/tv/src/androidTest/kotlin/app/muxtv/GuideFocusJourneyTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/MainActivitySmokeTest.kt`

**Interfaces:**
- Consumes: Hilt-provided accepted `GuideWindowRepository` and existing `AppDestination.Guide` / `AppDestination.Player(channelId)`.
- Produces: real Guide destination and canonical Guide -> Player -> Back journey.

- [x] **Step 1: Port app wiring and instrumentation fixtures unchanged**

- [x] **Step 2: Isolate the navigation symbol risk and add app-level integration coverage**

Static review found the ported `NavigationRow` referenced `initialNavigationFocusRequester` outside its scope even though the function owns an `initialFocusRequester` parameter. A separate MainActivity D-pad smoke contract now covers real Hilt/MainActivity/Navigation3 entry into `Программа`; it is authored but not yet executed.

- [x] **Step 3: Correct the known symbol defect minimally and separately**

Commit `1ab86b106d3590fd06d0cedf74fcf1662ad731e4` changes only:

```kotlin
.focusRequester(initialFocusRequester)
```

No compile-GREEN or RED/GREEN claim is made because the runner is unavailable.

- [x] **Step 4: Preserve canonical-only Player navigation**

Guide activation still opens `AppDestination.Player(channelId)` with canonical channel ID only. No locator or source metadata is added to navigation state.

---

### Task 4: Preserve bounded Guide state and deterministic focus

**Files:**
- Test/port: `feature/guide/src/test/kotlin/app/muxtv/feature/guide/GuideFocusTest.kt`
- Test/port: `feature/guide/src/test/kotlin/app/muxtv/feature/guide/GuidePagingTest.kt`
- Test/port: `feature/guide/src/test/kotlin/app/muxtv/feature/guide/GuideViewModelTest.kt`
- Production: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideFocus.kt`
- Production: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideViewModel.kt`

**Interfaces:**
- Consumes: stable canonical channel IDs and `GuideProgrammeKey` from accepted data API.
- Produces: deterministic exact-focus survival and fallback after EPG/page changes.

- [x] **Step 1: Port the existing test-first contracts**

Authored contracts cover stale generation suppression, current-page invalidation, truncation retries, persistent truncation -> `Incomplete`, keyset next/previous/reset, exact programme focus survival, removed-programme fallback, and later-page recovery to page one.

- [x] **Step 2: Keep production state behavior frozen until executable evidence exists**

No paging/focus/ViewModel behavioral rewrite has been added after the mechanical port. Runner-off review only identified evidence-gated UI/timeline edge cases.

- [ ] **Step 3: After runner returns, execute focused RED/GREEN cycles before any behavioral correction**

Run:

```powershell
./gradlew.bat :feature:guide:testDebugUnitTest --no-daemon
```

Any failing contract gets one minimal production correction and an immediate rerun before refactor.

---

### Task 5: TV layout/focus implementation review

**Files:**
- Production: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideRoute.kt`
- Instrumentation: `app/tv/src/androidTest/kotlin/app/muxtv/GuideFocusJourneyTest.kt`

**Interfaces:**
- Consumes: `GuideUiState.Content`, stable focus anchor, bounded programme geometry.
- Produces: fixed channel rail, absolute-time programme lane, current-time marker, focusable status cells, bounded pager/recovery controls.

- [x] **Step 1: Static review of geometry ownership**

The authored route uses one hoisted horizontal timeline offset for header and rows; there is no per-row horizontal `ScrollState`.

- [x] **Step 2: Static review of focus craft**

Programme-cell focus uses immediate outline/tone state with no scale/translation animation and no `onPreviewKeyEvent` synthesized activation.

- [x] **Step 3: Static review of accessibility/privacy**

Authored test tags are static names or row/cell indices; navigation remains canonical-ID only and error state does not surface repository exception text.

- [x] **Step 4: Keep status/recovery states TV-operable**

`NO_GUIDE` and `SOURCE_CONFLICT` use focusable status cells; Empty/Failed/Incomplete expose retry/reset actions, including a first-page recovery path for a later-page failure.

### Evidence-gated observations

Do not patch these without an executable contract/device observation:

1. **Very short programmes:** programme width is proportional to real time and can become very narrow. Do not add a fake minimum width that overlaps adjacent absolute-time cells. Validate focus outline/detail-strip behavior at 720p/1080p first.
2. **Local half-hour grid:** timeline tick alignment currently derives from epoch modulo 30 minutes while labels use the system `ZoneId`. A non-30-minute UTC offset (for example `+05:45`) can expose `:15/:45` local labels. Add a focused pure timeline-math contract before changing production tick alignment.

---

### Task 6: Runner-return acceptance sequence

**Files:**
- No production change unless a focused RED identifies one.

**Interfaces:**
- Consumes: exact v2 head produced by Tasks 1-5.
- Produces: claim-eligible Guide UI evidence for #29 and a merge-ready PR.

- [ ] **Step 1: Focused unit gate**

```powershell
./gradlew.bat :feature:guide:testDebugUnitTest --no-daemon
```

Record exact head, test count, failures, and exit code.

- [ ] **Step 2: Kotlin/Hilt/app integration compile**

```powershell
./gradlew.bat :feature:guide:compileDebugKotlin :app:tv:compileDebugKotlin :app:tv:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 3: Full host acceptance on the same exact head**

Use the repository's existing Full gate; do not mutate the branch while interpreting evidence.

- [ ] **Step 4: Product TV journeys**

Run exact Android TV old-edge/current profiles required by the repository, covering Guide entry, row/cell traversal, paging, `NO_GUIDE`, `SOURCE_CONFLICT`, Empty/Failed/Incomplete recovery, Guide -> Player -> Back exact focus and deterministic fallback.

- [ ] **Step 5: Visual/runtime checks**

Inspect 720p and 1080p for long Russian labels, short programme cells, focus contrast, no geometry shift, and rapid D-pad operation without animation queue.

- [ ] **Step 6: Final scope/privacy review**

Compare `main...head`; reject any accidental Room/player/network/CI changes. Verify diagnostics/semantics contain no locator/header/credential/provider/profile/EPG-source values.

- [ ] **Step 7: PR/issue closure**

Open or mark one compact Guide UI PR ready only after exact-head evidence is green and review threads are zero. Then update #29 with accepted evidence; close #29 only when its full original acceptance surface is satisfied, not merely because Guide compiles.

## Current execution state

Runner-off implementation completed:
- hygiene PR/issue ownership cleanup for accepted #127/#128/#129;
- v2 branch rebuilt from accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`;
- failed restack probe #130 closed without merge;
- historical Guide UI delta proven to be exactly 17 paths and non-overlapping with accepted post-#128 main work;
- mechanical Guide UI restack committed as `4e3ea449d78fce9153ce377bf05c68adf11fec9d`;
- navigation requester symbol correction isolated in `1ab86b106d3590fd06d0cedf74fcf1662ad731e4`;
- real MainActivity D-pad -> Guide integration contract authored in `bc67a55eba394c2e29607a346b3a39fd453995cd`;
- bounded state/focus/paging/privacy/layout contracts reviewed statically;
- original #29 slices already accepted on main were revalidated from history: Now/Next, Favorites, Search TV, Recent, cross-surface active truth, and bounded Guide data.

Current blocker is executable evidence, not missing Guide architecture:
- focused Guide unit execution;
- Kotlin/Hilt/androidTest compile;
- exact-head Full acceptance;
- API old-edge/current Guide product journeys;
- 720p/1080p visual/runtime evidence;
- final PR review/merge and #29 closure.

The current branch must not be called GREEN or merge-ready until those gates execute on one exact head.
