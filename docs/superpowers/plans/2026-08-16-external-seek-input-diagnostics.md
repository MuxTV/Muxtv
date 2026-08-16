# External Seek Input Diagnostics Implementation Plan

**Goal:** Turn the remaining #167 EP-08 seek/HUD timeout into exact boundary evidence, then fix only the proven boundary.

**Architecture:** Keep `PlaybackSeekController` as the sole seek-policy owner. `PlayerSurfaceContent` classifies native D-pad seek attempts and exposes a non-visual test tag. The app journey requires a typed result before checking HUD. Activity-level routing is a conditional second phase only if the real key never reaches Compose.

**Tech Stack:** Kotlin, Jetpack Compose, Android instrumentation, Media3 1.10.1.

### Task 1: Make the existing failing journey distinguish the boundary

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/external/ExternalPlaybackRangeJourneyTest.kt`

- [ ] After first-frame and surface-focus proof, send one real `KEYCODE_DPAD_RIGHT`.
- [ ] Wait briefly for one of the known `external-seek-input-*` tags.
- [ ] If none appears, fail with `KEY_NOT_RECEIVED`.
- [ ] If a rejection tag appears, fail with that exact outcome.
- [ ] Only `external-seek-input-accepted` proceeds to the existing seek-HUD assertion.
- [ ] Keep network/first-frame/back/teardown assertions unchanged.

### Task 2: Add typed outcome at the current Compose ingress

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`

- [ ] Introduce an internal `SeekInputOutcome` enum with stable lowercase diagnostic tags.
- [ ] Change local `requestSeek` from `Boolean` to typed outcome.
- [ ] Classify capability and timeline rejection separately before calling `PlaybackSeekController`.
- [ ] Treat `PlaybackSeekController.onDirectionRequested() == false` as `CONTROLLER_REJECTED`.
- [ ] Key handlers consume only `ACCEPTED`.
- [ ] Store the latest outcome and expose a zero-size/non-visual test-tag node.

### Task 3: Exact-head evidence decides the next implementation

- [ ] Run Fast + Android TV focused device.
- [ ] If `KEY_NOT_RECEIVED`: introduce one playback-boundary native input router from Activity dispatch to the existing seek request path.
- [ ] If a typed rejection appears: fix only that eligibility/projection cause.
- [ ] If `ACCEPTED` appears but HUD still fails: trace Compose `SeekControllerState` collection/rendering.
- [ ] Re-run exact-head gates after the single proven fix.
