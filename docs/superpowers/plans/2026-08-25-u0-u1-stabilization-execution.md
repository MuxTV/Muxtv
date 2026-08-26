# MuxTV U0 -> U1 -> M0 Stabilization Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` or `superpowers:executing-plans` task-by-task. Production changes require observed RED -> minimal GREEN -> exact-head verification.

**Goal:** finish trustworthy U0 runtime characterization, convert only observed TV UI regressions into permanent RED contracts, apply the smallest owner-scoped U1 corrections, accept the corrected UI baseline, then restack M0/#178 before returning to dependency modernization.

**Authority:** issue #179 is the execution-order authority. The critical path is:

```text
U0/#188 / PR #189 runtime characterization
    ↓
U1 evidence-driven minimal UI correction
    ↓
M0/#178 measurement correctness
    ↓
accepted stabilization baseline
    ↓
#190 combined compatibility diagnosis only
    ↓
isolated dependency owners
```

PR #190 must not move the product/runtime baseline while U0/U1/M0 is unresolved and must never be merged as one combined dependency bundle.

**Architecture:** U0 remains characterization-only and executes immutable A/B/C through one byte-identical probe on the canonical API36 AVD. U1 begins only after runtime evidence proves or falsifies H1-H4. Shared-shell defects stay owned by `AppNavigation`; rail visual behavior stays owned by `MuxTvNavigationRail`; token changes remain separately evidence-gated. Focus restoration stays Compose-owned through `focusRestorer()` unless runtime evidence proves a distinct focus defect.

**Tech stack:** Kotlin 2.4.10, Compose for TV, Navigation3, PowerShell 7, Android instrumentation/Compose UI test, Gradle, self-hosted Windows GitHub Actions.

**Primary sources:** issue #179; issue #188; PR #189; issue #180; issue #178. Live Git/GitHub state overrides stale SHAs in this document.

## Current U0 checkpoint

The original characterization source `6d26ca89b8ea3404c8d766d790c28133c9a481d1` is historical, not the current acceptance source.

Observed runtime sequence:

- historical runtime failed at A/1080p because the probe searched Channels title `Все каналы`;
- source-contract analysis proved the historical screen title is `Эфир`;
- minimal probe-only correction `279e318ee1f031ad84dc60b0b3816cc71b2698a3` passed repository validation and the canonical API26/API36 Product Matrix workloads, but U0 runtime still failed at A/1080p because `Эфир` was absent from the observed semantics query;
- therefore the narrow wrong-string hypothesis is falsified as a complete explanation;
- diagnostic-only head `33d166b1a3b046f3998e3bff421c8528a7fe3bae` verifies the actual rail focus owner before framework ENTER and emits the unmerged semantics tree on navigation/title failure;
- temporary self-hosted executor pins an exact PR source SHA per run. A source becomes accepted U0 evidence only after its own static compatibility, runtime and analyzer gates succeed.

No production UI change is admitted from these probe failures.

## Global constraints

- Repository-owned Android TV AVD identities are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- U0 runtime uses only `MuxTV_TV_CURRENT_API36`; 1080p/720p/stress are display overrides on that same AVD.
- Representative 1080p is `1920x1080 @ 320dpi`.
- Representative TV 720p is `1280x720 @ 213dpi`.
- `1280x720 @ 320dpi` is compact stress evidence only.
- Do not modify product UI before complete U0 runtime evidence exists.
- Do not modify seek ownership, player architecture, Room schema, dependency versions, buffering/cache policy or measurement semantics in U1.
- Preserve `Modifier.focusRestorer()` and the current Back -> content-group model unless U0 proves a concrete focus defect.
- A failed artifact upload does not erase a successful substantive workload, but publication acceptance is not weakened: classify workload and artifact transport separately.
- No third/low-RAM/720p/benchmark AVD identity may be created.
- Live exact-head provenance is mandatory for every acceptance claim.

---

## Task 1: Finish U0 probe/runtime correctness

**Files:**
- PR #189 characterization-only branch.
- `tools/ui-characterization/probe/UiCharacterizationProbeTest.kt`
- `tools/ui-characterization/Invoke-TvUiCharacterization.ps1`
- `tools/ui-characterization/Analyze-TvUiCharacterization.ps1`
- characterization static/source-fact/admission/recovery contracts.
- temporary executor `.github/workflows/u0-self-hosted-executor.yml` on `exec/u0-self-hosted-20260824`.

**Immutable comparison refs:**
- A `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`
- B `515072022d11b218fcb20f43079f94098b3ea973`
- C `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9`

- [ ] **Step 1:** For every runtime failure, identify the failing boundary before modifying the probe. Do not replace anchors by guesswork.
- [ ] **Step 2:** For the current Channels failure, distinguish `focus acquisition`, `framework ENTER/navigation`, and `post-navigation semantics` using exact runtime diagnostics.
- [ ] **Step 3:** If the diagnostic proves a probe defect, create the smallest probe/harness correction only; do not alter immutable A/B/C product source.
- [ ] **Step 4:** Executor checkout must resolve exactly to the selected PR source SHA, never the executor branch SHA.
- [ ] **Step 5:** Run self-hosted preflight with required labels `muxtv-android,muxtv-device`, no pre-connected device, and API26/API36 system-image ownership.
- [ ] **Step 6:** Run static contracts, including probe/source compatibility, startup recovery, admission, source facts and analyzer fixtures.
- [ ] **Step 7:** Compile the byte-identical probe against A/B/C. All three outputs must report one identical `probeSha256`.
- [ ] **Step 8:** Run A/B/C sequentially at all three display profiles on `MuxTV_TV_CURRENT_API36` only.
- [ ] **Step 9:** Permit only the existing bounded one-time retry for the exact early QEMU modem transport signature when no characterization case has passed.
- [ ] **Step 10:** Run `Analyze-TvUiCharacterization.ps1` only after the full runtime corpus exists.
- [ ] **Step 11:** Persist evidence outside the runner workspace under `%USERPROFILE%\MuxTV-Evidence\U0\<run-id>-<source-short-sha>` with SHA-256 manifest.
- [ ] **Step 12:** Reset Android state in `always()` and verify canonical AVD inventory.

**U0 acceptance predicates:**

- `failedCaseCount == 0`;
- one byte-identical `probeSha256` across A/B/C;
- exactly 3 refs × 3 display profiles represented;
- `avdName == "MuxTV_TV_CURRENT_API36"`;
- representative comparisons are present;
- focus conclusions are used only where `focusContractEligible == true`;
- requested display profile and observed display state are attributable;
- no production UI source changed as part of U0.

---

## Task 2: Classify H1-H4 from runtime evidence

**Output:** create `docs/superpowers/reports/2026-08-25-lounge-ui-regression-forensics.md` on the future U1 branch after U0 evidence is complete.

- [ ] **H1 — shared reservation:** prove only when representative rows across multiple destinations satisfy the expected A→B shared-shell shift and C matches B. Expected source-level candidate delta is `+50dp ±2dp`; runtime must confirm it.
- [ ] **H2 — rail visual semantics:** compare rail widths/state and screenshots independently from H1. A permanent/narrow rail may be a visual regression even if content origin is stable.
- [ ] **Focus:** evaluate Back and Right only for focus-contract-eligible anchors. Title-only geometry anchors do not support focus correctness claims.
- [ ] **H3 — global tokens:** compare focus outline, section gap, typography and Home-card geometry against representative runtime evidence. Never bulk-revert A tokens from source diffs alone.
- [ ] **H4/#180:** treat `1280x720 @ 213dpi` as representative 720p evidence. If the defect exists only at 320dpi stress, classify #180 as stress hardening rather than a TV-720 fix.
- [ ] Assign exactly one owner to each proven defect: `AppNavigation`, `MuxTvNavigationRail`, shared `TvTokens`, or a local surface.
- [ ] Explicitly record rejected hypotheses so later work does not reintroduce them.

---

## Task 3: Create the isolated U1 branch

- [ ] Re-read live `main`, #179, #189, #188, #180 and #178.
- [ ] Branch from accepted current `main`, never from #190.
- [ ] Carry only durable U0 report/regression contracts needed by U1; do not carry the temporary executor or dependency probe.
- [ ] Keep each proven defect independently revertible.

---

## Task 4: H1 permanent RED contract

**Execute only if Task 2 proves H1.**

**Create:** `app/tv/src/androidTest/kotlin/app/muxtv/AppNavigationShellGeometryTest.kt`

- [ ] Use the real `MainActivity`/application shell with `createAndroidComposeRule<MainActivity>()`.
- [ ] Use the same real D-pad path proven by product integration tests; assert actual focus ownership at each transition.
- [ ] For deterministic Home and Settings anchors, record `boundsInRoot.left` before rail entry, while rail owns focus and after Right/Back restoration.
- [ ] Assert `beforeLeft == duringRailLeft == afterRightLeft` and the corresponding Back restoration predicate.
- [ ] Derive the expected content origin from accepted U0 runtime evidence/shared-shell token, not screenshot literals.
- [ ] Run the new test against the uncorrected U1 base and observe RED specifically on the proven reservation defect.
- [ ] If it passes before production change, stop: the test does not represent H1 and no production edit is admitted from it.
- [ ] Commit RED separately.

---

## Task 5: Minimal H1 GREEN

**Execute only after Task 4 has an observed RED.**

**Owner:** `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`

- [ ] If U0 proves the source-level candidate, change only the shared `NavDisplay` reservation from `TvTokens.Size.railExpanded` to `TvTokens.Size.railCollapsed`.
- [ ] Do not add compensating destination-specific padding.
- [ ] Run the exact RED test and observe GREEN.
- [ ] Run Home, Channels, Guide, Search/Settings and source-navigation focus journeys plus Android test compilation.
- [ ] Do not restore historical rail width or typography in this commit.
- [ ] Commit one-owner GREEN.

---

## Task 6: H2 rail RED/GREEN

**Execute only if U0 proves a distinct rail visual-state regression.**

**Owner:** `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`

- [ ] Write a RED design-system/Compose test for the exact observed defect.
- [ ] Keep content reservation owned by `AppNavigation` and constant.
- [ ] Keep selected state independent from focus state.
- [ ] Preserve reduced-motion snap behavior.
- [ ] Do not introduce a global focus state machine.
- [ ] Apply the smallest rail visual-state change and verify the shell geometry test remains GREEN.
- [ ] Commit separately from H1.

---

## Task 7: H3 token restoration by owner

**Execute only for representative-mode regressions proven by U0.**

Candidate source-level deltas include focus outline, section gap, Home-card dimensions and hero/section/card/metadata typography, but none is an automatic rollback target.

- [ ] Group only values sharing one visual owner and one RED contract.
- [ ] Observe RED on representative 1080p/320 or 720p/213 evidence-derived behavior.
- [ ] Restore/adjust the minimum token set needed for GREEN.
- [ ] Re-verify both representative modes.
- [ ] Stress-only differences must not force representative-TV shrinkage.
- [ ] Commit each owner independently.

---

## Task 8: Decide #180 using real 720p evidence

- [ ] Compare B and C specifically at `1280x720 @ 213dpi`.
- [ ] If CTA containment is broken in B and corrected in C at 213dpi without 1080p regression, restack the one-line policy into U1 as its own commit.
- [ ] If C helps only `1280x720 @ 320dpi`, do not describe it as a representative 720p-TV correction; record stress-hardening disposition instead.

---

## Task 9: U1 exact-head acceptance

- [ ] Run focused tests for every changed owner.
- [ ] Run exact-head self-hosted host validation.
- [ ] Run API36 device acceptance and capture both representative display profiles on the same canonical API36 AVD.
- [ ] If shared navigation/design-system code changed, run the sequential API26 → API36 Product Matrix.
- [ ] Separate substantive workload verdict from artifact-transport quota failures.
- [ ] Verify final AVD inventory contains no third MuxTV AVD.
- [ ] Merge U1 only with fresh exact-head substantive evidence.

---

## Task 10: Synchronize durable repository truth after U1

**Modify:**
- `.work/CURRENT-STATE.md`
- `.work/ROADMAP.md`
- `.work/meta/status.yaml`
- U0/U1 forensics report

- [ ] Remove stale dual-seek debt already closed by #175.
- [ ] Record accepted D0/two-AVD infrastructure truth.
- [ ] Record U0 proven causes, rejected hypotheses, U1 corrections and exact evidence SHAs/paths.
- [ ] Do not describe #190 combined dependency branch as accepted baseline.

---

## Task 11: Restack and accept M0/#178

- [ ] Restack/rebuild #178 from accepted post-U1 `main`, not its old divergent history.
- [ ] Preserve product Search behavior while correcting measurement expectation to published-result boundaries.
- [ ] Extend measurement variance ownership/static contract as required by #178.
- [ ] Obtain exact-head host + measurement-variance substantive GREEN.
- [ ] Accept M0 before any DB-query/performance conclusions are trusted.

---

## Task 12: Run #190 as compatibility diagnosis only

**This task is intentionally after U0/U1/M0.**

- [ ] Re-read live isolated dependency versions/issues before using the old combined probe.
- [ ] Rebase/recreate diagnostic probe if accepted main moved materially.
- [ ] Run host validation first, then API26/API36 matrix only if host is substantive GREEN.
- [ ] Classify substantive failures by dependency owner.
- [ ] Classify artifact quota failures separately as transport failures.
- [ ] Freeze findings as diagnostic evidence.
- [ ] Do **not** merge the combined #190 bundle.

---

## Task 13: Cut isolated dependency owners

Only after the stabilization baseline is accepted:

- #146 Room3 `3.0.0 -> 3.0.1`;
- #197 Navigation3 `1.1.4 -> 1.1.6`;
- #198 Paging `3.5.0 -> 3.5.1`;
- #199 Media3 `1.10.1 -> 1.11.0`;
- #200 Compose August 2026 line;
- #192 Tracing 2.0 as separately owned observability infrastructure;
- #27/#31 Benchmark 1.5 tooling;
- #195 post-alpha Gradle isolated-project experiments.

Each owner gets its own RED/compatibility evidence, rollback boundary and merge decision.

---

## Stop conditions

Stop production implementation and classify the blocker when any of the following occurs:

- U0 has an unexplained failed case;
- the probe is guessing around a failure instead of identifying its boundary;
- A/B/C probe hashes differ;
- any non-canonical AVD identity appears;
- the 3×3 evidence corpus is incomplete;
- H1 runtime predicates do not support a cross-destination representative-mode shift;
- a proposed U1 RED passes before the production fix;
- an H1 correction needs destination-specific padding;
- focus restoration regresses after shell correction;
- API26 regresses after shared navigation/design-system changes;
- measurement or dependency changes become necessary merely to make U1 pass;
- a combined dependency probe is about to become an accepted product baseline.

## Completion definition

The stabilization train reaches its accepted baseline only when U0 evidence is reproducible and complete, every U1 production change has an observed RED and minimal GREEN, representative 1080p/720p geometry and D-pad restoration are verified, the exact two-AVD contract remains intact, durable repository truth is synchronized, and M0/#178 is accepted. Only then may #190 and isolated dependency modernization resume.