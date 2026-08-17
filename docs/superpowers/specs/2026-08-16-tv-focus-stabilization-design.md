# TV Focus Stabilization Design

## Scope

This design stabilizes the remaining Android TV behavioral failures in PR #168 without adding a global focus engine, retries, sleeps, timeout inflation, or unrelated UI polish.

The two confirmed problem areas are independent:

1. the Lounge navigation rail can exceed a low-height TV viewport, producing a zero-height bottom destination before focus traversal is evaluated;
2. Channels restoration can observe a new filter identity while `LazyPagingItems` still belongs to the previous Paging generation, while focus identity must remain stable for a surviving channel row.

## Design decisions

### Adaptive rail geometry

`MuxTvNavigationRail` owns two vertical geometry profiles. The normal profile preserves the existing Lounge geometry exactly; the compact profile changes only vertical dimensions and is selected only when the normal profile cannot fit the actual destination count inside the available Compose height.

There is no magic viewport threshold. Selection is based on a deterministic fit invariant:

`normal.requiredHeight(itemCount) <= availableHeight`

For five destinations the normal geometry requires 428dp. It preserves:

- vertical padding: 20dp each side;
- brand height: 48dp;
- effective brand-to-first-item gap: 28dp;
- item height: 56dp;
- inter-item gap: 8dp.

The compact profile uses:

- vertical padding: 12dp each side;
- brand height: 40dp;
- brand-to-first-item gap: 4dp;
- item height: 48dp;
- inter-item gap: 4dp.

For five destinations compact geometry requires 324dp, so every top-level destination has positive height inside a 360dp Compose viewport while the original Lounge geometry remains unchanged whenever it fits. Collapsed/expanded widths and focus visuals are unchanged.

### Deterministic 720p contract

The previous device test mutated `wm size` and then explicitly recreated `ActivityScenario`. On API36 that still allowed a second asynchronous Activity replacement after the Compose rule had attached, producing `No compose hierarchies found` before the rail contract was exercised.

The production layout in this path is constraint-driven and does not read `LocalConfiguration`, so the 720p journey now constrains the real `AppNavigation` tree directly to the equivalent 640x360dp viewport used by the deterministic API36 TV image. This tests the actual Compose geometry/focus contract without introducing an Activity-lifecycle race.

No sleep or timeout inflation is used.

### Channels Paging generation ownership

A filter change must select a Paging stream whose identity is owned by that filter. The UI must not combine `filter = FAVORITES` with a still-active `ALL` `LazyPagingItems` generation.

`ChannelsViewModel` exposes `rowsFor(filter)` rather than one `rows` stream driven internally by the mutable filter. Streams are created lazily and cached per filter in `viewModelScope`, so inactive filters are not queried and re-entering a filter reuses its stable stream identity.

`ChannelsRoute` selects and remembers the flow for the current filter before collecting it as `LazyPagingItems`.

### Stable focus subtree across projections

Paging generation identity and Compose focus identity are different concerns. A filter switch must not force-destroy the entire `ChannelsContent` subtree when the surviving channel has the same stable key.

Therefore `ChannelsContent` is not wrapped in `key(filter)`. The focus requester map and lazy-list subtree stay stable across projection changes, while only `restorationCompleted` is reset with `remember(filter)`.

This lets Compose preserve a surviving stable-key row when possible and keeps explicit restoration available when its index changes or the previous row disappears.

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

This design does not remove the shell-level `focusRestorer()`. It is broader than the confirmed failures, and the Channels regression test composes `ChannelsRoute` directly, so shell restoration cannot explain that failure. It should be changed only if future exact-head evidence directly implicates it.

## Tests

- Unit tests prove the 360dp compact fit, exact preservation of the 428dp normal geometry, and the exact fit boundary between normal and compact profiles.
- `SettingsJourneyTest.sourceDetailsAt720pKeepsFirstAndLastActionsReachableByDpad` uses deterministic 640x360dp Compose constraints and remains the device-level regression test for low-height rail plus modal reachability.
- `ChannelsFocusRestorationTest.favoritesFilterKeepsFocusedFavoriteChannel` remains the device-level regression test for cross-filter surviving-channel identity.
- Existing D-pad filter, save/restore, Player Back, removed-row fallback, Recent, and empty-state journeys remain independent focus contracts.

## Acceptance

The implementation is accepted only when the exact PR head has:

- `Self-hosted validation` / Fast green;
- `Android TV focused device` green;
- no new automatic heavy DeviceMatrix, benchmark, integration, or stress lanes;
- no sleeps, timeout inflation, global focus manager, or unrelated visual changes.
