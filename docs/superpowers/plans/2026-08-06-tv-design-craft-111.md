# TV design craft alignment plan — #111

**Goal:** align MuxTV's shared TV interaction primitives with the accepted craft source in `.work/design/craft-principles.md` without mixing the change into Guide #29.

**Base:** accepted `main` `ec2b7743183b227ef54c16989d061ae5d4775dee`.

**Primary craft reference:** https://github.com/emilkowalski/skills (`emil-design-eng` first; animation-specific skills only where motion is actually justified).

**Secondary visual exploration:** Open Design.

**Execution status:** Package A implementation and test contracts are authored while the self-hosted runner is unavailable. No RED, GREEN, compile-green, instrumentation-green or device-green claim is made. The branch must remain unmerged until the acceptance section has fresh execution evidence.

## Findings

The previous `MuxTvFocusSurface` applied `animateFloatAsState` on every focus transition, targeting global `TvTokens.Focus.scale = 1.06f` over `TvTokens.Motion.focusDurationMillis = 140` ms. The previous `TvTokensTest` also required `scale >= 1.04`, so the old test suite encoded the same high-frequency D-pad animation as a desired invariant.

That conflicts with the accepted TV adaptation of the primary craft reference: frequent keyboard/D-pad interaction should not queue decorative motion. #111 already owns remote-interaction, focus contrast and reachability, so the correction lives here rather than in Guide #29.

## Non-negotiable behavior

- no synthesized activation from preview-key handlers;
- native short/long press semantics remain reachable;
- dense D-pad focus transitions do not animate scale/position/geometry;
- focused state remains immediately visible with outline/tone/luminance;
- selected/focused/playing/disabled remain distinct;
- no change may reduce 720p/1080p reachability;
- reduced-motion removes positional/scale motion;
- route/overlay motion is out of scope unless it has an explicit purpose and device evidence.

## Package A — shared dense-focus primitive

### 1. Test-first contract

- [x] Replaced the obsolete token contract with `scale == 1.0`, full focused/unfocused alpha and zero dense-focus duration.
- [x] Test-only historical commit exists before production token/component changes: `fc8a0861f1291ca05a8b182a48907d34810cfe3d`.
- [x] Authored app instrumentation contract: DPAD_CENTER short press -> exactly one click.
- [x] Authored rapid DirectionRight journey: focus reaches the latest surface and directional input does not activate actions.
- [ ] Long-press/repeat contract with real key timing/injection.
- [ ] Selected/focused/playing state screenshot/semantic fixtures.
- [ ] 720p/1080p long-label reachability fixture.

The test-only commit has **not** been executed, so historical ordering is not an observed RED.

### 2. Production dense-focus behavior

- [x] Removed `animateFloatAsState`/focus tween from `MuxTvFocusSurface`.
- [x] Removed geometric scale from the shared dense-TV surface.
- [x] Dense global focus scale is now `1.0`.
- [x] Dense focus duration token is now `0`.
- [x] Focused and unfocused content alpha are both `1.0`; visibility is not created by dimming unfocused text.
- [x] Focus remains immediate through a 3dp outline plus neutral surface-tone change.
- [x] Activation remains normal Compose `clickable` ownership; no `onPreviewKeyEvent` handler was added.
- [x] Removed the now-unused direct `compose.animation` dependency from `:core:designsystem` after auditing its complete production source surface.

Sparse hero/poster focus scale remains a future explicit opt-in with reserved geometry; this package does not introduce it.

### 3. Documentation truth-sync

- [x] Added `.work/design/craft-principles.md` with source priority and TV adaptation.
- [x] Updated `.work/design/focus-navigation.md` away from normal dense-focus animation guidance.
- [x] Updated `.work/design/design-system.md` motion/focus wording.
- [x] Guide #29 plan references the same craft hierarchy.

## Static review completed offline

- [x] Branch remains based on accepted `main`, independent of #128/#29/#127/#129.
- [x] No Room/schema/database path changed.
- [x] No playback/network/source-security path changed.
- [x] `core/designsystem` production source contains only theme/tokens and two shared components; after removing focus animation there is no remaining direct animation API consumer in that module.
- [x] Corrected an authored instrumentation-test bug that mutated Compose state during composition before recording branch status.
- [x] No preview-key activation wrapper was introduced.

Static review is not compilation or runtime evidence.

## Acceptance still open

- [ ] Run `./gradlew.bat :core:designsystem:testDebugUnitTest --no-daemon` on the exact head.
- [ ] Run `./gradlew.bat :core:designsystem:compileDebugKotlin :app:tv:compileDebugAndroidTestKotlin --no-daemon` on the same exact head.
- [ ] Execute `MuxTvFocusSurfaceInteractionTest` with real Android TV key injection on API26 and API36.
- [ ] Prove short press fires exactly one action.
- [ ] Add and execute long-press/repeat coverage before claiming full #111 key ownership.
- [ ] Rapid key repeat produces no delayed focus-animation queue.
- [ ] Focused/selected/playing contrast reviewed at 720p/1080p.
- [ ] Long Russian labels remain reachable.
- [ ] No layout movement from focus.
- [ ] Existing Channels focus restoration and Player/Back journeys remain green.
- [ ] Zero unresolved review threads.

## Validation order when execution returns

1. Preserve the product critical path: rerun #128 Product DeviceMatrix on unchanged `985fbda8bd90ebde0f29fc1adc0632a8a05704a2` first.
2. In parallel after that gate is scheduled/complete, run the focused #111 JVM token tests.
3. Compile `:core:designsystem` and app instrumentation sources.
4. Execute shared focus short-press/rapid-focus tests on API26/API36.
5. Add/execute real long-press and repeat timing coverage.
6. Run existing Channels/Search/source focus journeys to detect shared-component regressions.
7. Perform 720p/1080p screenshot/contrast review using the accepted craft checklist.
8. Only then open or mark a compact #111 PR merge-ready.

## Merge boundary

Package A is **authored and statically reviewed, not accepted/green**. Do not merge it while the runner is unavailable or before fresh unit/compile/device evidence exists.
