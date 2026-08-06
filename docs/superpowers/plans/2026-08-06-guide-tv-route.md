# Guide TV Route Implementation Plan

**Goal:** Replace the existing `AppDestination.Guide` placeholder with a destination-scoped, bounded, D-pad-first TV Guide backed only by `GuideWindowRepository`.

**Base:** stacked child work from PR #128 exact data-layer head `985fbda8bd90ebde0f29fc1adc0632a8a05704a2`. This branch must not be merged before the bounded Guide data layer is accepted on `main`.

**Status:** implementation and test contracts are authored. Build/unit/instrumentation/device validation has **not** been executed on the current Guide head because the self-hosted runner is unavailable. No RED/GREEN, compile-green or device-green claim is made by this document.

## Invariants

- Never materialize the full catalog or full EPG in Compose/state.
- One active viewport generation owns channel + programme results; stale generations cannot publish.
- One visible channel page contains at most 30 rows. Channel paging replaces the bounded viewport; it never appends an unbounded catalog to Compose state.
- Default programme window is 6 hours and never exceeds the repository 12-hour maximum.
- Programme `isTruncated=true` is not a valid complete UI snapshot.
- Truncation retry narrows **time only** (`6h -> 3h -> 90m -> 45m`) so channel membership remains stable. If all four attempts are truncated, publish `Incomplete`; never silently drop channels to manufacture completeness.
- `GuideWindowRepository.observeDataChanges()` reloads the current keyset page. Generation checks suppress stale completion.
- Focus identity is canonical channel ID plus optional stable `GuideProgrammeKey`; list/cell indices are fallback coordinates only.
- Same-page EPG invalidation keeps exact surviving programme identity through stable Compose keys. If the identity disappears, request deterministic same-channel/nearest-valid fallback.
- Player navigation carries only canonical channel ID. No locator/header/credential/EPG source detail enters Navigation3 state.
- Existing `AppDestination.Guide` remains the route key.
- Focus styling does not scale/move neighboring rows or programme geometry.
- Do not synthesize OK clicks from `onPreviewKeyEvent`; the normal focusable/clickable control owns activation semantics.
- Empty/failed/incomplete later pages always expose a TV-operable recovery path back to page one.

## Authored implementation

### 1. Feature/module boundary

- [x] Added `:feature:guide` Android Compose library.
- [x] Registered module in `settings.gradle.kts`.
- [x] Wired `:feature:guide` into `:app:tv`.
- [x] Reused #128 `GuideWindowRepository`; no second database/repository owner.
- [x] No Room entity, migration, schema JSON or database-version change in this child branch.

### 2. Test-first state contracts

Authored before the corresponding production behavior; execution is still pending.

- [x] Initial request: 30-channel bounded page + 6-hour programme window.
- [x] Stale generation cannot publish after newer reload.
- [x] Current-page invalidation reloads the same keyset start cursor.
- [x] Truncated programme data narrows time and is never published as complete.
- [x] Persistent truncation stops after four attempts and becomes `Incomplete`.
- [x] `READY / NO_GUIDE / SOURCE_CONFLICT` remain explicit per-channel states.
- [x] Programme/channel identity mismatch becomes `Incomplete` rather than partial publication.
- [x] Repository failure produces secret-free `Failed` state and is retryable.
- [x] Exact focus survival and removed-programme fallback contracts.
- [x] Keyset next/previous/reset page replacement contracts.
- [x] Empty later page can reset to the first bounded page.

### 3. Bounded Guide state owner

- [x] Destination-scoped `GuideViewModel` with repository/profile/time source.
- [x] `StateFlow<GuideUiState>` with Loading/Empty/Content/Failed/Incomplete.
- [x] Generation counter plus active Job cancellation and post-await generation checks.
- [x] Conflated repository invalidation.
- [x] Strict projection identity equality before Content publication.
- [x] Four-attempt time-window truncation policy: 6h, 3h, 90m, 45m.
- [x] Keyset page replacement using `GuideChannelCursor`.
- [x] Previous-page start history bounded to 32 entries; `resetToFirstPage()` remains available when older history is discarded.
- [x] Page switches enter Loading before request so the old page cannot receive repeated navigation actions while the new page is unresolved.
- [x] Focus anchor remains in destination-scoped ViewModel memory, not Navigation3/saveable route state.

### 4. TV Guide UI

- [x] Added real `GuideRoute` / Guide screen; removed the Guide placeholder.
- [x] Fixed 260dp channel identity rail plus vertically lazy channel rows.
- [x] Absolute time geometry for programme cells with half-hour header ticks.
- [x] Current-time marker refreshed once per minute.
- [x] One hoisted horizontal timeline offset shared as render input; no ambiguous shared `ScrollState` ownership across multiple scroll containers.
- [x] Programme focus moves the timeline offset while preserving programme time geometry.
- [x] `NO_GUIDE` and `SOURCE_CONFLICT` render as explicit focusable status cells.
- [x] Focus uses outline/container tone instead of geometric scale.
- [x] Short programme cells retain their actual focus bounds; no gap modifier shrinks a tiny event to near-zero focus width.
- [x] Stable Compose `key()` is programme identity (or a non-secret status key) rather than cell index.
- [x] Same-page invalidation requests fallback only when the previous exact identity disappeared.
- [x] Semantics tags use row/cell indices or static action names; no profile/channel/EPG IDs in tags.
- [x] Focused detail strip exposes the visible channel/programme label without storing it in diagnostics.
- [x] Bounded pager exposes previous / first / next controls as applicable.
- [x] Empty, failed and incomplete states expose Retry + First-page recovery controls.

### 5. Navigation3 / app integration

- [x] Injected `GuideWindowRepository` into `MainActivity` from #128 Hilt provider.
- [x] Passed repository into `AppNavigation` as a required dependency.
- [x] Replaced `PlaceholderRoute("Телепрограмма")` with `GuideRoute`.
- [x] OK on a Guide cell opens only `AppDestination.Player(channelId)`.
- [x] Added explicit empty Guide repository to the app navigation instrumentation harness rather than making the production dependency nullable/defaulted.
- [x] Authored Guide instrumentation journey for canonical OK routing, Player/Back focus restoration, and focusable `NO_GUIDE / SOURCE_CONFLICT` rows.

## Static audit completed in this work session

- [x] Branch is stacked directly on #128 exact head and is not behind that head.
- [x] Changed paths are limited to Guide feature, TV app wiring/tests, this plan and module registration.
- [x] No `core/database/**`, Room schema JSON, migration or database-version path changed.
- [x] No playback transport implementation was modified.
- [x] No raw locator/header/credential field was added to Guide state, semantics or route keys.
- [x] Removed shared-scroll-state ambiguity: header and rows render from one hoisted bounded timeline offset.
- [x] Corrected static compile-risk findings discovered during review (Compose imports/test APIs/enum typo/short-cell focus bounds).

Static audit is **not** compilation or runtime evidence.

## Acceptance still open

- [ ] Re-run PR #128 Product DeviceMatrix on exact head `985fbda8bd90ebde0f29fc1adc0632a8a05704a2`; establish whether the previous interrupted run was infrastructure-only.
- [ ] Merge #128 only after its exact-head acceptance is complete.
- [ ] Refresh/rebase this child branch onto the accepted post-#128 `main` without changing Guide behavior accidentally.
- [ ] Run `:feature:guide:testDebugUnitTest` on the refreshed exact head and record test count/failures.
- [ ] Run app/Hilt/KSP compile/assemble on the same exact head.
- [ ] Run existing TV app instrumentation compile so direct `AppNavigation` callers are proven compatible.
- [ ] Execute Guide D-pad journey on old-edge API26 and current/API36: enter Guide, navigate rows/cells, OK -> Player, Back -> exact/fallback focus.
- [ ] Exercise `NO_GUIDE`, `SOURCE_CONFLICT`, Empty, Failed, Incomplete and paging recovery with injected fixtures.
- [ ] Large fixture demonstrates repository/state/Compose stay within one 30-row page and bounded programme payload.
- [ ] Verify short events and long Russian labels remain reachable at 720p and 1080p.
- [ ] Verify no focus stealing on ordinary same-page EPG invalidation when the exact programme survives.
- [ ] Verify deterministic fallback when the focused programme/channel disappears during Player or invalidation.
- [ ] Final secret/diagnostic scan on the validated exact head.
- [ ] Zero unresolved review threads after PR review.

## Validation order when execution is available

1. Validate and accept #128 on its unchanged exact head first.
2. Refresh this stacked branch onto accepted `main`.
3. Run focused host checks:
   - `./gradlew.bat :feature:guide:testDebugUnitTest --no-daemon`
   - `./gradlew.bat :feature:guide:compileDebugKotlin :app:tv:compileDebugKotlin --no-daemon`
4. Run TV app unit/instrumentation compile and the repository's existing full host verification gate.
5. Run Guide journey on API26 and API36, including Player/Back and paging/error fixtures.
6. Inspect artifacts/test counts and changed paths on that exact head.
7. Only then open/mark a compact Guide UI PR as merge-ready and update #29 acceptance truth.

## Merge boundary

This branch is **implementation-ready for validation, not accepted/green**. Do not merge it before #128 is accepted and the checks above produce fresh exit-0/device evidence.
