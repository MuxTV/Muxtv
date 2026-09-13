# C12 No-First-Frame Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prove whether MuxTV should add a READY/video/surface-gated per-attempt no-first-frame watchdog before the existing 20 second total recovery deadline, without changing production playback unless evidence is strong enough.

**Architecture:** `PlaybackRecoveryOrchestrator` remains the only candidate-switching authority and the existing 20 second deadline remains the only total recovery budget. C12 adds a small platform-neutral watchdog state machine plus production-neutral JVM/Android evidence. Production `MuxTvPlaybackService` integration is intentionally withheld because the evidence gate does not justify enabling a new per-attempt timeout on physical vendor TVs.

**Tech Stack:** Kotlin, JUnit4, Truth, AndroidX Media3 1.11.0, existing API26/API36 Android TV emulator evidence infrastructure.

**Spec:** GitHub issue #382.

## Global Constraints

- Exact starting baseline: `main@7b35040e1c08d484548619b2b75a573790b5880c`.
- Preserve one `MuxTvPlaybackService`-owned player/session.
- Preserve candidate ordering, max attempts, generation ownership and the absolute 20,000 ms recovery deadline.
- C11 `B_NARROW_TYPED_STOP` remains unchanged; C12 expiry is not a synthetic `PlaybackException`.
- Eligibility requires continuous `READY + selected/expected video + rendering surface available + first frame absent` for the same attempt token.
- BUFFERING/IDLE/ENDED, surface loss, first frame, attempt replacement and cancellation make an armed deadline ineligible/stale.
- Audio-only/no-selected-video never arms.
- B timeout is 10,000 ms; C timeout is 5,000 ms. These are MuxTV experiment values, not copied reference constants.
- No external IPTV, raw URL/header/credential logging, alternate engine, C09 renderer change, C10 LoadControl change, C13 freeze recovery, C14 audio rescue or #132 seek changes.

---

### Task 1: TEST-ONLY RED

**File:** `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdogTest.kt`

- [x] Define the missing variant/action/watchdog contract in tests before production code.
- [x] Cover READY/video/surface eligibility, BUFFERING reset, first-frame-before-READY and stale/one-shot expiry.
- [x] Observe exact RED at `2962fb14245ce12997c9e45276ec2b3e554f9b73`, workflow run `34750168069`, job `103705167151`.
- [x] Confirm failure is missing C12 symbols in `compileDebugUnitTestKotlin`, not infrastructure failure.

### Task 2: Minimal pure GREEN state machine

**Files:**
- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdog.kt`
- `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdogTest.kt`

- [x] Implement active attempt token, READY/video/surface/first-frame state, arm/disarm and one-shot expiry only.
- [x] Map A -> no per-attempt timer, B -> 10,000 ms, C -> 5,000 ms.
- [x] Keep stale previous-attempt timer callbacks inert through token ownership.
- [x] Verify pure GREEN at `94e25084bc3aecaa29932db2f878c934eaa61af3`, run `34750376815`, job `103705834818`.
- [x] Do not add service-only APIs solely to satisfy the experiment; service integration remains outside the pure seam until accepted.

### Task 3: Deterministic A/B/C correctness corpus

**File:** `player/media3/src/test/kotlin/app/muxtv/player/media3/C12NoFirstFrameWatchdogEvidenceTest.kt`

- [x] Compare A/B/C with the same deterministic event sequences.
- [x] Include a healthy first frame 7,000 ms after READY, still inside MuxTV's existing 20,000 ms total budget.
- [x] Verify stale timers and duplicate expiry remain zero.
- [x] Verify corpus GREEN at `409563e8b27de30a5be67160085e225495ce2037`, run `34758816912`, job `103727866807`.
- [x] Record result:
  - A: 20,000 ms stuck detection, 0 useful early fallback, 0 false/wrong/stale/duplicate actions.
  - B: 10,000 ms stuck detection, 1 useful early fallback, 0 false/wrong/stale/duplicate actions.
  - C: 5,000 ms stuck detection, 1 useful early fallback, 1 false stop and 1 wrong abandonment.
- [x] **REJECT `C_READY_GATED_5S`** on correctness evidence.

### Task 4: Android Media3 event-mapping evidence

**Files:**
- `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/C12Media3NoFirstFrameEvidence.kt`
- `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/C12Media3NoFirstFrameEvidenceInstrumentedTest.kt`
- `player/media3/src/debug/kotlin/app/muxtv/player/media3/C12Media3EvidenceActivity.kt`
- `player/media3/src/debug/AndroidManifest.xml`
- `player/media3/build.gradle.kts`
- `tools/ci/Run-C12Media3NoFirstFrameEvidence.sh`
- `.github/workflows/c12-media3-no-first-frame-evidence.yml`

- [x] Add an opt-in C12 instrumentation selector mutually exclusive with C09/C10 selectors.
- [x] Use only local pinned C09 AVC media and MockWebServer; no remote provider dependency.
- [x] Healthy RAW_TS and HLS: observe BUFFERING, selected video and a real `SurfaceHolder`; require first frame and forbid B/C expiry.
- [x] Surface lifecycle: reach READY + selected video without an output surface, prove B/C arm count remains zero, then attach surface and render first frame.
- [x] No-video projection: READY + surface with `videoExpected=false` never arms/expires.
- [x] Baseline API26/API36 run `34759294813` passed both device jobs and the aggregate gate.
- [x] Baseline artifacts:
  - API26 `10318890843`, SHA256 `30479a159020d7e2bfcd8e9c3e86beb7684510b60061ab70980a5a8008c0e185`.
  - API36 `10317869621`, SHA256 `2ff33011d0119fb26d0c837eadfcedef71dd726e6ccca927964d856a8364d2ee`.
- [x] Inspect baseline raw evidence: healthy RAW_TS/HLS first frame arrived before the first recorded READY on both APIs (`ready_ms=null`), so B/C arm count remained zero; surface/no-video guards behaved as required.

#### Rejected synthetic Android fault oracle

A follow-up experiment attempted to force a real ExoPlayer path into `READY + selected video + valid surface` while deliberately withholding `onRenderedFirstFrame()` from the watchdog. Two exact-head runs (`34759821329` and `34769439312`) failed consistently on API26/API36 because the synthetic finite-stream playback could not prove the required *continuous* READY eligibility interval for both timers. Pausing after physical first frame did not turn that construction into a trustworthy oracle.

This is evidence about the **test construction**, not evidence that the pure watchdog timer is broken: its arm/expiry/stale semantics are already deterministic and GREEN in the JVM corpus. The invalid fault harness is intentionally removed rather than weakening eligibility rules, extending timeouts, fabricating Media3 state, or treating transient playback-state transitions as a production no-frame failure.

### Task 5: Evidence decision

- [x] `C_READY_GATED_5S` = **REJECT**. It violates the deterministic correctness boundary with a false stop/wrong abandonment on a healthy 7s decoder tail.
- [x] `B_READY_GATED_10S` = **DEFER for production**. Pure evidence shows the desired bounded 10s rescue with zero corpus regressions, and Android evidence validates healthy Media3 event/surface/video gating on API26/API36. However emulator evidence does not establish the decoder-tail distribution of weak/vendor Android TV devices, and the attempted synthetic fault path was not a valid continuous-READY oracle.
- [x] Production remains **A_CURRENT_GLOBAL_ONLY**: the existing bounded 20s recovery deadline remains authoritative.
- [x] Keep the isolated pure seam and evidence harness for future physical-device evidence; do not wire it into `MuxTvPlaybackService` in C12.

### Task 6: Production integration gate

`MuxTvPlaybackService` remains unchanged in C12.

- [x] No C12 watchdog job is scheduled by production playback.
- [x] C11 `Media3FailureClassifier` / recovery disposition semantics are unchanged.
- [x] C09 renderer and C10 LoadControl policies are unchanged.
- [x] No #132 seek, C13 freeze, C14 audio-rescue or alternate-engine scope is mixed into C12.
- [x] Because the final disposition is `REJECT C / DEFER B`, service integration is intentionally skipped.

### Task 7: Final qualification and bookkeeping

The repository files stop changing before final qualification. Exact-head workflow run IDs, artifact IDs/digests, current-main reconciliation, PR review-thread state and merge evidence are recorded in GitHub issue/PR comments after terminal qualification. They are intentionally not committed afterward, because a bookkeeping-only commit would create a new head and invalidate the exact-head qualification it was documenting.

Final merge requirements:

- terminal GREEN for C12 focused policy/corpus and the retained API26/API36 healthy/lifecycle evidence;
- terminal GREEN for Hosted CI/Validation, Media3/App lint and relevant Android device matrices on the exact merge head;
- no production-service/recovery/renderer/LoadControl scope leakage;
- zero unresolved review threads;
- current `main` rechecked immediately before merge;
- exact-head SHA guard on merge;
- final disposition recorded as **REJECT C / DEFER B / production A** in #382, #348, #109 and #30.
