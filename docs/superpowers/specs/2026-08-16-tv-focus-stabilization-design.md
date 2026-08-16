# TV Focus Stabilization Design

## Scope

This design stabilizes the remaining Android TV behavioral failures in PR #168 without adding a global focus engine, retries, sleeps, timeout inflation, or unrelated UI polish.

The two confirmed problem areas are independent:

1. the Lounge navigation rail can exceed a low-height TV viewport, producing a zero-height bottom destination before focus traversal is evaluated;
2. Channels restoration can observe a new filter identity while `LazyPagingItems` still belongs to the previous Paging generation, and it currently marks restoration complete even when focus was not actually restored.

## Design decisions

### Adaptive rail geometry

`MuxTvNavigationRail` owns a small geometry profile derived from available Compose height. Normal TV height keeps the current Lounge dimensions. A compact profile is selected below 400dp and reduces vertical padding, brand height, item height, and inter-item gap while preserving the existing collapsed/expanded widths and focus visuals.

The rail must satisfy a deterministic fit invariant before D-pad behavior is considered valid: all top-level destinations must have non-zero height and remain inside the viewport at 1280x720 / 320dpi (~360dp Compose height).

The compact profile uses:

- vertical padding: 12dp each side;
- brand height: 40dp;
- item height: 48dp;
- item gap: 4dp;
- no extra brand/menu spacer.

For five destinations this requires 324dp, leaving margin inside a 360dp viewport.

### Channels Paging generation ownership

A filter change must select a Paging stream whose identity is owned by that filter. The UI must not combine `filter = FAVORITES` with a still-active `ALL` `LazyPagingItems` generation.

`ChannelsViewModel` therefore exposes `rowsFor(filter)` rather than one `rows` stream driven internally by the mutable filter. Each per-filter flow remains cached in `viewModelScope` and continues to combine channel data, now/next projection, and playback state.

`ChannelsRoute` selects and remembers the flow for the current filter before collecting it as `LazyPagingItems`.

### Focus restoration completion

Restoration is complete only when the target row is both present in the active Paging generation and placed in the current lazy layout, and `FocusRequester.requestFocus()` succeeds.

The algorithm remains identity-first:

1. prefer the previously focused channel id when it exists in the current generation;
2. otherwise use the deterministic nearest predecessor fallback;
3. scroll to the target;
4. wait until the target key is present in `LazyListState.layoutInfo.visibleItemsInfo` and its requester is registered;
5. request focus after a frame boundary;
6. set `restorationCompleted = true` only on successful focus acquisition.

No arbitrary delay or retry loop is introduced.

### Global `NavDisplay.focusRestorer()`

This design does not remove the shell-level `focusRestorer()` in the first implementation pass. It is broader than the two confirmed root causes. It should be changed only if fresh exact-head evidence remains red after rail geometry and filter-owned Paging restoration are fixed.

## Tests

- Unit tests cover normal vs compact rail metrics and the 360dp fit invariant.
- Existing `SettingsJourneyTest.sourceDetailsAt720pKeepsFirstAndLastActionsReachableByDpad` remains the device-level regression test for the 720p rail path.
- Existing `ChannelsFocusRestorationTest.favoritesFilterKeepsFocusedFavoriteChannel` remains the device-level regression test for cross-filter focus identity.
- Existing D-pad filter journey tests continue to protect remote traversal separately.

## Acceptance

The implementation is accepted only when the exact PR head has:

- `Self-hosted validation` / Fast green;
- `Android TV focused device` green;
- no new automatic heavy DeviceMatrix, benchmark, integration, or stress lanes;
- no sleeps, timeout inflation, global focus manager, or unrelated visual changes.
