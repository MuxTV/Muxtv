# C11 Typed Bounded Recovery Ladder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove whether MuxTV should replace unconditional next-candidate handling for fatal Media3 errors with a typed recovery disposition while preserving all existing attempt, deadline, generation and single-player invariants.

**Architecture:** Keep `Media3FailureClassifier` as the observation boundary and `PlaybackRecoveryOrchestrator` as the sole candidate-switching state machine. Add a tiny policy-only `PlaybackRecoveryDisposition` seam so the orchestrator consumes only `TRY_NEXT_CANDIDATE` or `STOP_RECOVERY`; map Media3 observations to that disposition outside the orchestrator. Evaluate A/B/C on a deterministic JVM corpus before wiring any selected policy into `MuxTvPlaybackService`.

**Tech Stack:** Kotlin, JUnit4, Truth, AndroidX Media3 1.11.0, existing Gradle/Hosted Validation workflows.

**Spec:** GitHub issue #380 — C11: typed bounded recovery ladder A/B/C.

## Global Constraints

- Exact baseline is `main@e44eb268b5acf78d5aff4059abb28a03c6197cc3`.
- Keep one `MuxTvPlaybackService`-owned Media3 player/session.
- Do not add an application same-candidate retry/reprepare loop.
- Do not modify Media3 `LoadErrorHandlingPolicy` in C11.
- Keep C09 renderer and C10 buffer production policies unchanged.
- Do not modify #132 semantic seek ownership.
- Typed observation/failure family and recovery disposition remain separate concepts.
- No raw locator, query token, Cookie, Authorization value, provider password or exception message may enter recovery policy/evidence.
- No alternate engine/libmpv, C12 no-frame watchdog, C13 freeze watchdog or C14 audio rescue in this change.
- Production wiring is forbidden until A/B/C evidence accepts a candidate.

---

### Task 1: RED disposition contract

**Files:**
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackRecoveryDispositionTest.kt`
- Production target after RED: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackRecoveryOrchestrator.kt`

**Interfaces:**
- Consumes: existing `PlaybackRecoveryOrchestrator`, `PlaybackRecoveryAction`, `PlaybackRecoveryFailure`.
- Produces after GREEN: `PlaybackRecoveryDisposition { TRY_NEXT_CANDIDATE, STOP_RECOVERY }` and disposition-aware `onPlayerError(...)`.

- [ ] Add a failing JVM test proving `STOP_RECOVERY` terminates an installed candidate without resolving the next candidate.
- [ ] Add a failing JVM test proving `TRY_NEXT_CANDIDATE` preserves the current one-step advance behavior.
- [ ] Run `./gradlew :player:media3:testDebugUnitTest --tests '*PlaybackRecoveryDispositionTest*'` and record the expected missing-symbol RED.
- [ ] Commit the RED-only contract before production code.

### Task 2: Minimal pure-policy GREEN

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackRecoveryOrchestrator.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackRecoveryDispositionTest.kt`
- Existing regression test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackRecoveryOrchestratorTest.kt`

**Interfaces:**
- `PlaybackRecoveryDisposition.TRY_NEXT_CANDIDATE`
- `PlaybackRecoveryDisposition.STOP_RECOVERY`
- `PlaybackRecoveryFailure.TerminalFailure`
- `onPlayerError(generation, candidate, disposition = TRY_NEXT_CANDIDATE)`

- [ ] Implement only the two dispositions in the orchestrator.
- [ ] Preserve the two-argument/current call path through a default parameter so production remains A until evidence.
- [ ] Ensure deadline expiry still wins before any disposition action.
- [ ] Ensure stale generation/candidate callbacks still return `Ignored`.
- [ ] Run focused recovery tests and full `:player:media3:testDebugUnitTest`.
- [ ] Commit the minimal GREEN.

### Task 3: RED/GREEN Media3 disposition policy

**Files:**
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/Media3RecoveryDispositionPolicyTest.kt`
- Create after RED: `player/media3/src/main/kotlin/app/muxtv/player/media3/Media3RecoveryDispositionPolicy.kt`

**Interfaces:**
- Consumes: secret-safe `Media3Failure` from `Media3FailureClassifier`.
- Produces: `media3RecoveryDisposition(variant, failure): PlaybackRecoveryDisposition`.
- Variant A: `A_CURRENT_GENERIC_NEXT`.
- Variant B: `B_NARROW_TYPED_STOP`.
- Variant C: `C_BROAD_RENDER_STOP`.

- [ ] RED: A always returns `TRY_NEXT_CANDIDATE`.
- [ ] RED: B returns `STOP_RECOVERY` only for exact `ERROR_CODE_FAILED_RUNTIME_CHECK`; all candidate/source/codec/access families remain next-candidate.
- [ ] RED: C stops `PLAYER_RENDER` category plus exact runtime-check.
- [ ] Verify RED before creating production policy.
- [ ] Implement the smallest pure mapping with no exception text or locator input.
- [ ] Run policy/classifier/recovery tests and commit GREEN.

### Task 4: Deterministic A/B/C correctness corpus

**Files:**
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/C11TypedRecoveryEvidenceTest.kt`

**Interfaces:**
- Evaluates identical deterministic failure sequences under A/B/C.
- Emits/records per-variant counts in test output or assertions: useful recovery, false stop, extra action, bound violation.

- [ ] Encode timeout, DNS/TLS/network, HTTP 401/403, malformed manifest, decoder, behind-live-window/player-render, failed-runtime-check, unknown, deadline, cancellation and exhaustion sequences.
- [ ] Define expected useful-terminal outcome independently from variant policy.
- [ ] Require zero stale-callback/deadline/attempt-bound violations for every variant.
- [ ] Prove whether C has any false-stop counterexample.
- [ ] Prove whether B removes at least one futile candidate action without losing a useful recovery.
- [ ] Run the corpus repeatedly in deterministic/randomized variant order if existing competitive planner fits without coupling Android-only infrastructure.

### Task 5: Evidence decision before product wiring

**Files:**
- No production file change unless B or C clears the #380 gate.
- Update issue #380 / PR body with exact run IDs and result.

- [ ] If B/C fails correctness, record `REJECT`/`ADAPT` and leave service on A.
- [ ] If B clears correctness and efficiency gate, proceed to Task 6 with B only.
- [ ] Do not wire C merely because it uses fewer attempts; any false stop rejects C.

### Task 6: Accepted service integration only

**Files:**
- Modify only if accepted: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Extend focused service/recovery tests as needed.

**Interfaces:**
- Existing `onPlayerError` choke point classifies the `PlaybackException` once with `Media3FailureClassifier`, records the observation, derives the accepted disposition, and passes it to `PlaybackRecoveryOrchestrator`.

- [ ] Preserve one classification call/result for diagnostics and policy.
- [ ] Do not introduce a second retry loop, coroutine owner, player or timeout.
- [ ] Run host validation, lint, focused device tests and API26/API36 product matrix.
- [ ] Self-review exact diff and verify `main` has not moved before merge.

### Task 7: Final evidence bookkeeping

- [ ] Record exact RED head/run.
- [ ] Record exact GREEN/evidence head/run/artifacts.
- [ ] Record final `ADOPT | ADAPT | REJECT | DEFER` in #380.
- [ ] Update #348 C11 and #30 with the final result.
- [ ] Merge only with exact-head guard after all relevant checks are terminal GREEN.
