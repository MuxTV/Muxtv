# External Seek Input Diagnostics Design

## Scope

This design addresses the sole remaining EP-08 failure in PR #167. Exact-head DeviceCurrent proves ACTION_VIEW launch, HTTP approval, Media3 surface attachment, first-frame confirmation, and hidden-surface focus all succeed. The failure occurs after real Android `KEYCODE_DPAD_RIGHT` input and before the seek HUD appears.

The implementation must determine the failing boundary before changing remote-input ownership. It must not add a second seek controller, change Media3 version/buffering policy, inflate timeouts, or move key handling into the Activity speculatively.

## Boundary model

The current path is:

`Android KeyEvent -> focused Compose surface -> onPreviewKeyEvent -> seek eligibility gates -> PlaybackSeekController -> Pending -> seek HUD`

Because `PlaybackSeekController` enters `Pending` synchronously when it accepts a request, absence of the HUD localizes the defect to either:

1. the native key event never reaches `PlayerSurfaceContent`; or
2. it reaches the handler but a seek eligibility/controller gate rejects it.

## Typed seek-input outcome

`PlayerSurfaceContent` will classify every handled left/right key-down into one outcome:

- `ACCEPTED`
- `COMMAND_UNAVAILABLE`
- `UNKNOWN_DURATION`
- `LIVE_CONTENT`
- `INVALID_POSITION`
- `CONTROLLER_REJECTED`

The result is presentation-layer diagnostics only. Actual seek policy remains owned by the existing `PlaybackSeekController`.

## Test observability

The surface will expose the latest seek-input outcome as a zero-visual-impact Compose test-tag node using the existing `testTagPrefix`, for example:

`external-seek-input-accepted`
`external-seek-input-unknown-duration`

No production UI is shown and no user input/content is logged.

The EP-08 journey will first require one typed input outcome after sending real system D-pad input. If no outcome appears, the failure is explicitly `KEY_NOT_RECEIVED`. If a rejection outcome appears, the test reports that exact rejection. Only an `ACCEPTED` outcome proceeds to the existing seek-HUD assertion.

## Follow-up rule

A native Activity-level remote-input router is implemented only if fresh exact-head evidence reports `KEY_NOT_RECEIVED`. If the key is received but rejected, fix the exact capability/duration/controller cause instead. If accepted but HUD is missing, investigate Compose state/presentation propagation.

## Acceptance

- exact-head Fast green;
- exact-head Android TV focused device green;
- EP-08 uses real Android D-pad input;
- no second seek authority, timeout inflation, or Media3 workaround.
