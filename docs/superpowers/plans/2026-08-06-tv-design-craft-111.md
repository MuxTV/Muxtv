# TV design craft alignment plan — #111

**Goal:** align MuxTV's shared TV interaction primitives with the accepted craft source in `.work/design/craft-principles.md` without mixing the change into Guide #29.

**Base:** accepted `main` `ec2b7743183b227ef54c16989d061ae5d4775dee`.

**Primary craft reference:** https://github.com/emilkowalski/skills (`emil-design-eng` first; animation-specific skills only where motion is actually justified).

**Secondary visual exploration:** Open Design.

## Findings

Current `MuxTvFocusSurface` applies `animateFloatAsState` on every focus transition, targeting `TvTokens.Focus.scale = 1.06f` over `TvTokens.Motion.focusDurationMillis = 140` ms. That is a high-frequency D-pad animation and conflicts with the accepted TV adaptation of the primary craft reference.

The current project docs also permit scale/motion as a normal focus cue. #111 already owns remote-interaction, focus contrast and reachability, so this should be corrected there rather than in Guide #29.

## Non-negotiable behavior

- no synthesized activation from preview-key handlers;
- native short/long press semantics remain reachable;
- dense D-pad focus transitions do not animate scale/position/geometry;
- focused state remains immediately visible with outline/tone/luminance;
- selected/focused/playing/disabled remain distinct;
- no change may reduce 720p/1080p reachability;
- reduced-motion removes positional/scale motion;
- route/overlay motion is out of scope unless it has an explicit purpose and device evidence.

## Task 1 — contract tests first

- [ ] add design-system instrumentation contract proving DPAD_CENTER short press fires one click;
- [ ] add long-press/repeat contract where supported by the primitive contract;
- [ ] add rapid directional focus journey with no queued/blocked focus ownership;
- [ ] add screenshot/semantic fixtures for default, focused, selected and focused+selected states;
- [ ] add 720p/1080p long-label reachability fixture;
- [ ] document which evidence cannot be proven without real key injection/device execution.

No RED claim until those tests actually run.

## Task 2 — remove high-frequency focus animation

After actual RED/behavior evidence:

- [ ] remove `animateFloatAsState`/focus tween from `MuxTvFocusSurface` for D-pad focus;
- [ ] default focus scale to 1.0 for dense/navigation/list/grid surfaces;
- [ ] keep focused outline/tone/luminance immediate;
- [ ] avoid alpha changes that make unfocused text illegible from 10-foot distance;
- [ ] if sparse poster/hero cards later need scale, expose an explicit opt-in visual treatment rather than a global default.

## Task 3 — token cleanup

- [ ] replace misleading global `TvTokens.Focus.scale = 1.06f` default with explicit dense-TV defaults;
- [ ] separate focus-state tokens from rare transition-motion tokens;
- [ ] keep shapes/spacing semantic and calibrated by screenshot evidence;
- [ ] do not create a large animation framework.

## Task 4 — documentation truth-sync

- [x] add `.work/design/craft-principles.md` with source priority and TV adaptation;
- [ ] update `.work/design/focus-navigation.md` to remove normal focus-animation guidance;
- [ ] update `.work/design/design-system.md` motion/focus wording;
- [ ] link #111 acceptance to the new craft contract.

## Task 5 — acceptance

- [ ] exact-head app/design-system compile green;
- [ ] API26/API36 remote journeys green;
- [ ] rapid key repeat produces no delayed focus animation queue;
- [ ] focused/selected/playing contrast reviewed at 720p/1080p;
- [ ] long Russian labels remain reachable;
- [ ] no layout movement from focus;
- [ ] no regression in Channels focus restoration;
- [ ] no unresolved review threads.

## Merge boundary

This branch currently carries documentation/planning only. Shared focus primitive production code should not be changed or called accepted until the test-first package is executed with fresh runner/device evidence.
