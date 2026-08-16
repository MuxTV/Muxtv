# Lounge Light final admission sequence

**Date:** 2026-08-17  
**PR:** #168  
**Accepted prerequisite:** PR #167 merged as `b40145ed5756a03ac52130e321fd05325c1a0836`.

This plan is the final admission order for the integrated Lounge branch. Dynamic head SHAs, workflow run IDs and mergeability remain Git/GitHub control-plane truth.

## Completed stabilization before final integration

The branch-local Lounge evidence already established:

1. removed-channel focus fallback delegates to the repository `FocusAnchor.resolveAgainst()` policy;
2. Favorites filter journeys use real D-pad navigation rather than unfocused touch/click surrogates;
3. Source Details puts its terminal `Готово` action in the same lazy focus container as operational actions;
4. compact-height navigation is asserted step by step rather than by repeated blind Down presses;
5. prior Activity/Compose 720p recreation and source-details precondition failures were removed without sleeps or inflated timeouts.

Those results are not sufficient by themselves after #167 because the shared player surface changed upstream.

## Integration step

The branch must contain accepted main, not merely be mergeable against it.

The checked integration tree must preserve:

- all accepted #167 EP-08 files and tests;
- `Player.isCurrentMediaItemLive` capability projection and post-subscribe resnapshot;
- `PlayerRemoteInputHost` native boundary and semantic outcome diagnostics;
- Lounge overlay reveal animation and raised surface styling;
- `:feature:settings` implementation dependency;
- `:core:testing` androidTest dependency.

A synthetic PR merge tree may be used for inspection, but final CI must run on a materialized branch commit whose history includes accepted main.

## Final exact-head gates

On the same integrated head:

```text
Self-hosted validation -> SUCCESS
Android TV DeviceCurrent -> SUCCESS
```

DeviceCurrent must keep both families of evidence green:

### Lounge behavior

- rail/navigation journeys;
- Home/Channels/Guide/Search/Settings;
- Channels focus restoration/filter journeys;
- compact-height Source Details focus chain;
- player overlay/Back/focus behavior.

### Accepted EP-08 behavior

- Media3 progressive-resilience suite;
- external ACTION_VIEW setup;
- service-gated first frame;
- finite/non-live/seekable readiness;
- real native DPAD_RIGHT accepted with seek HUD;
- real Android Back and clean external stop.

Any failure in an accepted #167 path is an integration regression and blocks Lounge merge even if all visual/focus tests are green.

## Failure routing

- player/native seek regression: compare integrated shared surface/capability projection against accepted #167 before changing policy;
- Channels focus regression: diagnose paging generation / stable-key restoration, not generic global focus workarounds;
- compact Source Details regression: identify the exact D-pad transition and focus container; do not add sleeps;
- compile/dependency failure: verify both shared `app/tv` dependencies survived reconciliation;
- animation-only assertion failure: keep behavior/focus authority unchanged and fix presentation locally;
- SessionResult/surface timeout signature: treat as #173 regression and block immediately.

## Documentation and issue checkpoint after green evidence

After exact-head host/device are green, update PR #168, #93 and #33 with the exact accepted evidence. Issue text must distinguish:

- implemented + emulator-proven behavior;
- residual visual/product work;
- physical-TV/vendor-codec acceptance under #31;
- seek ownership consolidation under #132.

Do not close a broader issue solely because Lounge implementation merged if its remaining acceptance items are not proven.

## Merge procedure

1. Re-fetch #168 and confirm open, mergeable, exact expected head.
2. Confirm exact-head host and DeviceCurrent are successful.
3. Update PR body/issues only; do not mutate code/docs after those evidence runs.
4. Merge #168 with `expected_head_sha`.
5. Fetch accepted main SHA.
6. Reassess `.work/meta/status.yaml` reviewed snapshot separately under repository-truth semantics; do not treat it as live state.
7. Begin #132 only after the accepted Lounge main is stable.
