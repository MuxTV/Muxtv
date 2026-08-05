# Guide TV Route Implementation Plan

**Goal:** Replace the existing `AppDestination.Guide` placeholder with a destination-scoped, bounded, D-pad-first TV Guide backed only by `GuideWindowRepository`.

**Base:** child work from #128 exact head. This branch must not be merged before the bounded Guide data layer is accepted on `main`.

## Invariants

- Never materialize the full catalog or full EPG in Compose/state.
- One active viewport generation owns channel + programme results; stale generations cannot publish.
- Initial active channel window is 30 rows and never exceeds `GuideChannelWindowQuery.MAX_LIMIT`.
- Default time window is 6 hours and never exceeds the repository 12-hour maximum.
- Programme `isTruncated=true` is not a valid complete UI snapshot. Retry with a narrower channel/time slice; if it cannot be made complete within bounded attempts, expose a typed incomplete/error state rather than hiding truncation.
- `GuideWindowRepository.observeDataChanges()` invalidates only the current generation and reloads the bounded viewport.
- Focus identity is canonical channel ID plus optional stable `GuideProgrammeKey`; list position is not identity.
- Player navigation carries only canonical channel ID. No locator/header/EPG source detail enters Navigation3 state.
- Existing `AppDestination.Guide` remains the route key.

## Task 1 — state owner contracts

- [ ] Add `feature:guide` module.
- [ ] Add unit RED for initial 30-channel / 6-hour bounded request.
- [ ] Add RED proving stale generation cannot publish after a reload.
- [ ] Add RED proving invalidation reloads only current viewport.
- [ ] Add RED proving truncated programme response is narrowed/retried and never published as complete.
- [ ] Add RED for `READY / NO_GUIDE / SOURCE_CONFLICT` preservation.
- [ ] Add RED for focus anchor resolution when a focused programme disappears.

## Task 2 — bounded GuideViewModel

- [ ] Implement destination-scoped `GuideViewModel` constructed with repository/profile/time source.
- [ ] Load first bounded channel window.
- [ ] Load programmes only for the active channel window.
- [ ] Conflate invalidation events and cancel stale work by generation.
- [ ] Keep bounded retry budget for truncation reduction.
- [ ] Expose loading/empty/content/failed/incomplete states without raw exception text.

## Task 3 — TV UI

- [ ] Add `GuideRoute` and `GuideScreen`.
- [ ] Sticky time header and fixed channel identity rail.
- [ ] Horizontal programme timeline with current-time indicator.
- [ ] Render `NO_GUIDE` / `SOURCE_CONFLICT` as explicit non-programme cells.
- [ ] D-pad: Up/Down channel, Left/Right time/cell, OK opens canonical channel.
- [ ] Do not use focus scale that changes row/column geometry.
- [ ] Stable semantics tags contain indices/states only, never profile/canonical/EPG IDs.

## Task 4 — Navigation3 integration

- [ ] Inject `GuideWindowRepository` into `MainActivity` composition.
- [ ] Pass repository to `AppNavigation`.
- [ ] Replace `PlaceholderRoute("Телепрограмма")` with `GuideRoute`.
- [ ] Player -> Back restores focused canonical Guide cell when still present; deterministic same-channel/nearest-valid fallback otherwise.

## Task 5 — acceptance

- [ ] JVM ViewModel/focus tests green.
- [ ] App compile + Hilt graph green.
- [ ] API26/API36 D-pad journey: Guide -> Player -> Back.
- [ ] Large fixture demonstrates state/Compose remains bounded.
- [ ] No locator/header/credential values in state, semantics, route keys or diagnostics.
- [ ] Update #29 truth and close only when Guide route/journeys are accepted.
