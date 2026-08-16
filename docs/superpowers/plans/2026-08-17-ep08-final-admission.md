# EP-08 final admission sequence

**Date:** 2026-08-17  
**PR:** #167  
**Accepted base when this plan was written:** `main@6ebb8f408b2609ad4509f108d1773bf9d3cfb067`

This plan replaces the transient debugging sequence in the 2026-08-16 native-input diagnostic plan. It records only the durable final admission order; GitHub remains authoritative for the current head, run IDs and merge state.

## Completed causal findings

1. The MediaSession callback result-code defect was fixed and accepted through #173.
2. The external surface reaches the real service-gated first frame.
3. Native Android DPAD reaches `ExternalPlaybackActivity`, `PlayerRemoteInputHost` and one active player-surface handler.
4. Recomposition-driven detach windows were removed by keeping one registration per host/content lifetime and reading current handler state.
5. Synchronous semantic diagnostics proved the remaining rejection was `live-content`, not transport, registration, overlay, unknown duration or command availability.
6. The finite progressive fixture was incorrectly classified as live because capability projection used `MediaItem.liveConfiguration != null`.
7. The live predicate is now Media3 timeline state (`Player.isCurrentMediaItemLive`), with a post-subscribe capability resnapshot.
8. The old 4 s evidence fixture was too short for the 10 s production seek step and is replaced by a 20 s fixture plus a pre-input non-terminal readiness assertion.

## Final exact-head gate

The final PR head must contain code, tests and durable docs before admission CI is evaluated.

Required gates on that exact head:

```text
Self-hosted validation -> SUCCESS
Android TV DeviceCurrent -> SUCCESS
```

Within DeviceCurrent the required causal journey is:

```text
external ACTION_VIEW
 -> exact-origin HTTP approval
 -> surface attached
 -> first-frame confirmed
 -> controller says seek command available
 -> controller says current item is not live
 -> duration/current position leave > one 10 s seek step before EOF
 -> real DPAD_RIGHT
 -> semantic outcome accepted
 -> seek HUD visible
 -> real Android Back
 -> Activity destroyed
 -> upstream request count stable after destroy
```

Player-level progressive resilience tests must remain green in the same device run.

## Failure routing

Do not add sleeps, timeout inflation or generic retries.

- `live-content`: recheck actual timeline live projection; do not special-case MP4/TorrServer.
- `command-unavailable`: inspect MediaSession available-command projection and setup lifecycle.
- `unknown-duration`: inspect timeline readiness and source metadata propagation.
- `controls-visible` / `sheet-open`: inspect focus/overlay preconditions.
- no host dispatch: inspect Activity/native-input bridge registration/lifetime.
- accepted but no HUD: inspect seek-controller state publication.
- Back does not terminate/stop: inspect Activity/service external-session ownership.
- malformed SessionResult/surface timeout: regression of #173; block merge immediately.

## Merge procedure

When both exact-head gates are green:

1. Re-fetch PR #167 info and verify expected head SHA, open state and mergeability.
2. Update PR body with final head and exact run IDs/results; no code/doc change after evidence.
3. Merge #167 only with the expected final head SHA.
4. Fetch the resulting accepted `main` SHA.
5. Update #168 from that main and manually preserve both sides of the shared player surface:
   - #167 native remote/seek boundary and corrected capability projection;
   - #168 Lounge overlay presentation/motion.
6. Preserve both `app/tv` androidTest dependencies.
7. Rerun #168 exact-head host + DeviceCurrent after integration.

## Post-#167 scope

- #168: final Lounge integration/evidence, then merge.
- #132: consolidate all relative/absolute seek callers behind one generation-aware service-owned mutation authority.
- #109: only after #132, consume measured buffer policy/back-buffer work.
- #31: physical Android/Google/Fire TV acceptance; emulator evidence is not vendor codec/HDR/passthrough evidence.
- repository reviewed snapshot: update separately after accepted merges if the repository-truth contract requires a new reviewed checkpoint; never encode speculative future merge SHAs.
