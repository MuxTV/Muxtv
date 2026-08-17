# Lounge Light review-fix implementation plan

> **Execution contract:** implement on `feat/lounge-light-tv-redesign` with RED -> GREEN evidence. Do not create a new focus/state framework. Keep changes inside the accepted #33/#93/#111 contracts and the existing Navigation3/ViewModel ownership.

**Goal:** close the blocking architecture/TV-UX findings from the 2026-08-15 review and bring #168 closer to the approved Lounge Light reference, including polished navigation/brand icons.

**Architecture:** keep the collapsed rail as the only layout-width owner; expansion is a visual overlay and never remeasures `NavDisplay`. Focus provenance is explicit: content -> rail records a content return target; Back/right from rail returns focus to content. Now/Next becomes an immediately refreshed, cancellation-safe bounded flow instead of independent 60-second jobs. Variable-height settings details become a bounded D-pad-scrollable modal surface with focus containment.

**Tech stack:** Kotlin, Compose for TV / tv-material3, Navigation3, Coroutines/Flow, Paging3, existing `core:designsystem`, existing repositories.

## Global constraints

- No new global focus engine, MVI/Redux layer, state owner, theme engine, blur/parallax, network font or artwork pipeline.
- Dense D-pad focus keeps geometry stable: scale `1f`, zero geometric focus animation.
- Home card scale may be `1.03` only in normal motion; reduced-motion policy must keep it at `1f`.
- Rail collapsed slot remains `88dp`; expanded visual width is `248dp` and must not shrink/reflow destination content.
- Focused/selected/playing/disabled remain independent and visible without color alone.
- Now/Next queries remain bounded by `NowNextQuery.MAX_CHANNEL_IDS` and cancel stale work.
- Source errors must not be rendered as "no sources" onboarding.
- Details content must remain D-pad reachable at 720p + fontScale 1.3.
- Use a repo-owned consistent outlined icon family and a real MuxTV bow-tie mark; no new icon library dependency.
- No timed/repeated 50k benchmark in this change.

---

### Task 1: Lock navigation/focus regressions with RED journeys

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/RailNavigationJourneyTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SettingsJourneyTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/AccessibilityJourneyTest.kt`

- [ ] Add a rail Back test that expects focus to return to the originating Home content target, not remain on a collapsed rail item.
- [ ] Add bidirectional Channels filter traversal: `All -> Favorites -> Recent -> Favorites -> All`; only `All.Left` may enter global rail.
- [ ] Add Settings `Doctor -> Back -> Doctor focused` restoration evidence.
- [ ] Add a 720p/fontScale 1.3 Sources details journey that reaches the final `Готово` action by D-pad and verifies focus restoration to the originating `Настроить` action.
- [ ] Push RED and observe the intended failures before production changes.

### Task 2: Make rail expansion overlay-only and restore spatial provenance

**Files:**
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`

- [ ] Keep an `88dp` layout slot in `AppNavigation`; render the rail in a `Box` whose expanded panel draws to `248dp` over destination content rather than changing Row constraints.
- [ ] Remove the `SideEffect` feedback loop between child focus state and parent `railExpanded` boolean.
- [ ] Treat rail expansion as a derivation of rail focus; Back/right requests the remembered content focus target so loss of rail focus collapses it naturally.
- [ ] Use one effective `expanded` value for brand and item labels.
- [ ] Preserve selected destination separately from focused item.

### Task 3: Fix local D-pad graph and route restoration ownership

**Files:**
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsRoute.kt`
- Modify: `feature/settings/src/main/kotlin/app/muxtv/feature/settings/SettingsRoute.kt`

- [ ] Set filter focus graph to `All.Left=rail`, `All.Right=Favorites`, `Favorites.Left=All`, `Favorites.Right=Recent`, `Recent.Left=Favorites`.
- [ ] Persist `SettingsSection` last focus with `rememberSaveable`; request initial focus only from the saved section.
- [ ] Update the saved section from each row's `onFocusChanged` so Navigation3 restoration and route initial focus do not compete.

### Task 4: Make Home/Channels Now-Next immediate and cancellation-safe

**Files:**
- Create: `feature/home/src/test/kotlin/app/muxtv/feature/home/HomeViewModelTest.kt`
- Modify: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeViewModel.kt`
- Modify: `feature/channels/src/test/kotlin/app/muxtv/feature/channels/ChannelsViewModelTest.kt`
- Modify: `feature/channels/src/main/kotlin/app/muxtv/feature/channels/ChannelsViewModel.kt`

- [ ] RED: setting non-empty ids immediately invokes bounded Now/Next without waiting 60s.
- [ ] RED: changing ids cancels/invalidates stale query results so older completion cannot overwrite newer ids.
- [ ] GREEN: replace independent periodic child jobs with `distinctUntilChanged + flatMapLatest`/single-owner scheduling.
- [ ] After each result, delay only until the nearest returned `nextBoundaryEpochMillis` (bounded fallback <=60s) then refresh; changing ids cancels the delay/query.
- [ ] Keep repository exceptions local to presentation and preserve cancellation.

### Task 5: Distinguish Home source loading/error/empty truth

**Files:**
- Modify: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeRoute.kt`
- Modify: `feature/home/src/test/kotlin/app/muxtv/feature/home/HomeViewModelTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/HomeJourneyTest.kt`

- [ ] Introduce a small `HomeSourceState` (`Loading`, `Present`, `Empty`, `Failed`) derived from `SourceRefreshStore` flow; do not add a repository/state framework.
- [ ] Render `Failed` as a neutral retry/diagnostic-safe state, never as `Добавить источник` onboarding.
- [ ] Preserve current empty-source CTA only for confirmed `Empty`.

### Task 6: Make Source details bounded, scrollable and focus-contained

**Files:**
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourcesRoute.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SettingsJourneyTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/AccessibilityJourneyTest.kt`

- [ ] Replace the unbounded modal `Column` with a max-height TV surface and D-pad-scrollable `LazyColumn`/vertical scroll content.
- [ ] Keep the scrim non-focusable; activation/back dismissal remains explicit but focus cannot escape behind the modal.
- [ ] Ensure first and last actions are reachable with large Russian strings at 720p/fontScale 1.3.
- [ ] Keep Back/`Готово` restoration to the source card's `Настроить` requester.

### Task 7: Add real reduced-motion policy and reference-level Home hierarchy

**Files:**
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt`
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvFocusSurface.kt`
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvActionButton.kt`
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvScreenScaffold.kt`
- Modify: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeRoute.kt`
- Modify tests under `core/designsystem` and Home journeys as needed.

- [ ] Add semantic action style (`Primary`, `Secondary`) so hero primary CTA is solid bronze at rest; ordinary controls remain neutral.
- [ ] Add dedicated `heroTitle` typography token in the accepted 44–52sp band.
- [ ] Allow Home scaffold to omit its redundant large `Главная` title while retaining the top-right clock.
- [ ] Give hero a deterministic local-only warm bronze/neutral gradient/abstract treatment; no network artwork/dominant-color pipeline.
- [ ] Read the platform animation duration scale/reduced-motion signal in the design-system surface and force optional Home card scale to `1f` when motion is disabled/reduced.

### Task 8: Polish brand and navigation icons with one repo-owned vector family

**Files:**
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/icon/MuxTvIcons.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvBrandMark.kt`
- Modify: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Add focused unit tests for icon/brand construction if testable without screenshot coupling.

- [ ] Replace mixed filled glyphs with one consistent outlined 24dp family: Home, TV/Live, Guide/calendar, Search, Settings.
- [ ] Keep stroke/visual mass consistent and no filled-play icon for the `Эфир` destination.
- [ ] Replace the temporary bronze square `M` with the MuxTV bow-tie mark matching the approved reference silhouette; render `MuxTV` wordmark only in expanded rail.
- [ ] Keep collapsed icon semantics and selected marker unchanged.

### Task 9: Repository truth + exact-head evidence

**Files:**
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml` if current content still contradicts accepted/main vs active #168 state.
- Update the PR body/comment with final evidence.

- [ ] Synchronize implementation source/accepted main/active package statements so they do not simultaneously claim #160/#166/future M6-R.
- [ ] Run/observe targeted unit + instrumentation suites, `assembleDebug`, lint, Full and product DeviceCurrent/Matrix on the exact final #168 head.
- [ ] Do not run timed/repeated 50k stress.
- [ ] Compare captured 1080p Home/Guide/Settings/Player screenshots against `docs/design/assets/muxtv-lounge-light-reference.jpg`; run 720p reachability separately.
