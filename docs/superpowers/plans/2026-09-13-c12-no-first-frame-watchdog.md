# C12 No-First-Frame Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove whether MuxTV should add a READY/video/surface-gated per-attempt no-first-frame watchdog that hands a stuck attempt to the existing bounded candidate recovery ladder before the total 20 second recovery deadline.

**Architecture:** Keep `PlaybackRecoveryOrchestrator` as the only candidate-switching authority and keep the existing 20 second setup deadline as the only total recovery budget. Add a small platform-neutral `PlaybackNoFirstFrameWatchdog` state machine that owns eligibility and one-shot expiry for one `PlaybackAttemptToken`; Media3/service code only translates READY/video/surface/frame/error events into that state machine and schedules/cancels one per-attempt coroutine job. Compare current A against 10 second and 5 second READY-relative candidates before any production selection.

**Tech Stack:** Kotlin, JUnit4, Truth, AndroidX Media3 1.11.0, coroutines already used by `MuxTvPlaybackService`, existing API26/API36 Android TV evidence infrastructure.

**Spec:** GitHub issue #382 — C12 no-first-frame watchdog A/B/C.

## Global Constraints

- Exact baseline is `main@7b35040e1c08d484548619b2b75a573790b5880c`.
- Preserve one `MuxTvPlaybackService`-owned player/session.
- Preserve `PlaybackRecoveryOrchestrator` candidate order, max attempts, generation ownership and absolute recovery deadline.
- C11 production policy remains `B_NARROW_TYPED_STOP`; C12 expiry is not a synthetic Media3 error.
- Do not change C09 renderer policy, C10 LoadControl policy, C13 freeze recovery, C14 audio rescue, #132 seek ownership, or external-player architecture.
- A watchdog may count only continuous `READY + video expected + surface available + first frame absent` time for the current attempt token.
- BUFFERING/IDLE/ENDED, surface loss, first frame, player error, attempt replacement and cancellation disarm the per-attempt clock.
- Audio-only/no-video-track content never arms.
- Existing 20,000 ms total deadline remains authoritative.
- B timeout = 10,000 ms; C timeout = 5,000 ms. These are 50%/25% of MuxTV's existing total recovery budget, not imported reference constants.
- No raw URL/header/credential/exception text may enter watchdog state or evidence.

---

### Task 1: TEST-ONLY RED for eligibility and one-shot ownership

**Files:**
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdogTest.kt`
- Production target after RED: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdog.kt`

**Interfaces:**
- Consumes: existing `PlaybackAttemptToken`.
- Produces after GREEN:
  - `PlaybackNoFirstFrameWatchdogVariant { A_CURRENT_GLOBAL_ONLY, B_READY_GATED_10S, C_READY_GATED_5S }`;
  - `PlaybackNoFirstFrameWatchdogAction { None, Arm(token, timeoutMillis), Disarm, Expired(token) }`;
  - `PlaybackNoFirstFrameWatchdog.activate(token)`;
  - `onReadyChanged(token, ready)`;
  - `onVideoExpectedChanged(token, expected)`;
  - `onSurfaceAvailabilityChanged(token, available)`;
  - `onRenderedFirstFrame(token)`;
  - `onPlayerError(token)`;
  - `onTimerFired(token)`;
  - `cancel()`.

- [ ] **Step 1: Write failing gate tests.** Assert B does not arm after READY alone, arms only once all READY/video/surface gates are true, disarms on BUFFERING, and re-arms only on a new continuous READY interval.
- [ ] **Step 2: Write first-frame-before-READY test.** Call `onRenderedFirstFrame(token)` before READY; later READY/video/surface events must never arm that attempt.
- [ ] **Step 3: Write stale/one-shot expiry test.** After `Expired(token)`, repeated timer callbacks are `None`; after `activate(newToken)`, the old token timer is `None`.
- [ ] **Step 4: Run RED.** Run `./gradlew :player:media3:testDebugUnitTest --tests '*PlaybackNoFirstFrameWatchdogTest*'`. Expected failure: missing watchdog variant/action/class symbols.
- [ ] **Step 5: Commit only the tests after the expected RED is observed.**

### Task 2: Minimal pure GREEN state machine

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdog.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackNoFirstFrameWatchdogTest.kt`

**Interfaces:**
- `activate(token)` replaces all prior attempt state and returns `Disarm` if an old timer could exist, otherwise `None`.
- B/C `Arm` only on transition from ineligible to eligible.
- Any eligibility loss while armed returns `Disarm`.
- `onTimerFired(token)` returns `Expired(token)` at most once and only while the same token is still armed and eligible.
- A never returns `Arm`/`Expired`.

- [ ] **Step 1: Implement only the state described by Task 1.** Internal state contains token, ready, videoExpected, surfaceAvailable, firstFrameReported, armed and expired.
- [ ] **Step 2: Map variant timeout exactly.** A -> `null`, B -> `10_000L`, C -> `5_000L`.
- [ ] **Step 3: Run focused tests until GREEN.**
- [ ] **Step 4: Run `PlaybackAttemptToken` / callback-gate regression tests together with the watchdog test.**
- [ ] **Step 5: Commit minimal GREEN.**

### Task 3: Deterministic A/B/C correctness corpus

**Files:**
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/C12NoFirstFrameWatchdogEvidenceTest.kt`
- Extend only if required: `PlaybackNoFirstFrameWatchdogTest.kt`.

**Interfaces:**
- Pure event sequences drive the exact same watchdog implementation for A/B/C.
- Evidence summary records `falseDetections`, `wrongAbandonments`, `expiredNoFrameAttempts`, `staleActions`, and `detectionLatencyMillis`.

- [ ] **Step 1: Encode mandatory healthy sequences.** Frame-before-READY; READY->frame; READY->BUFFERING->READY->frame; READY while surface unavailable then surface arrives; surface loss; audio-only/no-video-track.
- [ ] **Step 2: Encode failure sequences.** READY+video+surface with no frame; player error while armed; stale generation; replacement attempt; repeated timer; global deadline earlier than watchdog.
- [ ] **Step 3: Encode recovery sequence.** Candidate A expires no-frame, existing orchestrator receives explicit `TRY_NEXT_CANDIDATE`, candidate B renders first frame and succeeds without preferred-variant mutation.
- [ ] **Step 4: Assert A/B/C invariants.** All variants: zero false detections/wrong abandonment/stale mutation; B deterministic expiry at 10,000 ms; C at 5,000 ms; A has no per-attempt expiry.
- [ ] **Step 5: Commit corpus only after focused GREEN.**

### Task 4: Android surface/video/READY evidence seam

**Files:**
- Create: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/C12NoFirstFrameWatchdogEvidenceInstrumentedTest.kt`
- Create: `.github/workflows/c12-no-first-frame-watchdog.yml`
- Create: `tools/ci/Run-C12NoFirstFrameWatchdogEvidence.sh`
- Reuse: C09/C10 deterministic media corpus, `C10Media3EvidenceActivity`, canonical API26/API36 runner identity.

**Interfaces:**
- Production watchdog is still not wired into `MuxTvPlaybackService` in this task.
- Instrumentation feeds real Media3 `STATE_READY`, `onRenderedFirstFrame`, selected-video-track and surface-lifecycle facts into the pure watchdog.

- [ ] **Step 1: Normal playback guardrail.** Reuse deterministic raw TS/HLS playback and assert no watchdog expiry on successful first-frame paths.
- [ ] **Step 2: Surface lifecycle case.** Start without an output surface, allow player state to progress, assert no expiry while surface eligibility is false, then attach a valid surface and complete playback.
- [ ] **Step 3: Buffering case.** Inject delayed first segment/throttling and assert BUFFERING intervals do not accumulate READY watchdog time.
- [ ] **Step 4: No-video case.** Use deterministic track state with no selected video and assert no arm/expiry.
- [ ] **Step 5: Deterministic no-frame fault.** With selected video and valid surface, suppress the harness's first-frame completion signal while preserving READY eligibility; assert B/C expiry times and one-shot ownership without fabricating a Media3 exception.
- [ ] **Step 6: Run API26 and API36 correctness.** Record exact source SHA, corpus hashes, environment fingerprint and raw secret-free event evidence.

### Task 5: Decision before production wiring

**Files:**
- No `MuxTvPlaybackService` modification unless a candidate clears #382.
- Update #382 and PR body with exact evidence.

- [ ] **Step 1: Reject any candidate with one false no-frame detection, wrong abandonment, stale action or duplicate expiry.**
- [ ] **Step 2: Compare B/C rescue latency against A's total-deadline behavior.**
- [ ] **Step 3: If both B/C are correctness-safe, prefer B unless C has evidence-backed additional user benefit strong enough to justify the shorter decoder tail.**
- [ ] **Step 4: If emulator evidence cannot justify vendor-decoder tail safety, record `DEFER` and keep production A while retaining the tested seam/evidence harness.**

### Task 6: Accepted production integration only

**Files:**
- Modify only if accepted: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Extend focused tests as required.

**Interfaces:**
- Service owns one `Job?` for the currently armed per-attempt watchdog.
- Media3 listener maps playback state, tracks, surface availability, first frame and player error into the pure watchdog.
- `Arm` cancels any old per-attempt job and schedules exactly one delay.
- `Disarm` cancels the per-attempt job.
- Timer wake-up calls `onTimerFired(token)` on service ownership context; only `Expired(token)` may trigger recovery.
- Expiry records secret-safe `ATTEMPT_FAILED` with existing `PLAYER_RENDER` category / unspecified Media3 code, then calls `recovery.onPlayerError(..., TRY_NEXT_CANDIDATE)` directly.

- [ ] **Step 1: TDD service integration.** Add a failing focused test around event-to-directive mapping before modifying service behavior.
- [ ] **Step 2: Wire one job and no new total deadline.** Cancel it from `clearInstalled`, cancellation/replacement, first frame and player error paths.
- [ ] **Step 3: Preserve C11.** Actual `PlaybackException` continues through `Media3FailureClassifier` and `Media3RecoveryDispositionPolicy.productionDisposition`; watchdog expiry does not.
- [ ] **Step 4: Run host/focused tests and API26/API36 integration again on the selected production variant.**

### Task 7: Final qualification and bookkeeping

- [ ] **Step 1: Run full exact-head Hosted CI/Validation, Media3/App lint, focused device and API26/API36 product matrix.**
- [ ] **Step 2: Verify PR changed-file scope and unresolved review threads.**
- [ ] **Step 3: Record RED/GREEN/evidence run IDs and artifact digests in #382.**
- [ ] **Step 4: Record final result in #348, #109 and #30.**
- [ ] **Step 5: Re-check `main` and merge only with exact-head guard if all relevant checks are terminal GREEN.**
