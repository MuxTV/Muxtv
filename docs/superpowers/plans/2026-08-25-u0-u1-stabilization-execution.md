# MuxTV U0 -> U1 Stabilization Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish U0 runtime characterization on the frozen #189 implementation, convert only observed UI regressions into RED contracts, apply the smallest shared-layout correction, and hand a stable UI baseline to M0/#178 without contaminating evidence with dependency or player changes.

**Architecture:** U0 remains characterization-only and executes immutable A/B/C through one byte-identical probe on the canonical API36 AVD. U1 begins only after `ui-characterization-analysis.json` proves or falsifies H1-H4. The preferred H1 correction separates destination content reservation from the rail's visual width; focus restoration remains Compose-owned through `focusRestorer()` unless runtime evidence proves a separate defect.

**Tech Stack:** Kotlin 2.4.10, Compose for TV, Navigation3, PowerShell 7, Android instrumentation/Compose UI test, Gradle, self-hosted Windows GitHub Actions.

**Spec:** `docs/superpowers/plans/2026-08-22-muxtv-stabilization-master-plan.md`; issue #188; PR #189 frozen source `6d26ca89b8ea3404c8d766d790c28133c9a481d1`.

## Global Constraints

- Repository-owned Android TV AVD identities are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- U0 runtime uses only `MuxTV_TV_CURRENT_API36`; 1080p/720p/stress are display overrides on that same AVD.
- Do not use GitHub-hosted CI while hosted quota is unavailable.
- Do not advance #189 `.github/ui-characterization/run.request` while the hosted admission design remains in that PR.
- Do not modify product UI before U0 runtime evidence exists.
- Do not modify seek ownership, player architecture, Room schema, dependency versions, buffering/cache policy or measurement semantics in U1.
- Every production change follows observed RED -> minimal GREEN -> exact-head verification.
- Compact `1280x720 @ 320dpi` is stress evidence only; representative 720p TV evidence is `1280x720 @ 213dpi`.
- Preserve `Modifier.focusRestorer()` and the current Back -> content-group focus model unless U0 proves a concrete focus defect.

---

## Task 1: Finish #190 compatibility probe without contaminating U0

**Files:**
- Existing diagnostic branch only: `chore/stack-update-20260824`
- No U0/U1 production files.

**Interfaces:**
- Consumes: exact head `1ab773f49f28602890ab067439f3e5a3fb6204da`.
- Produces: classified host/device compatibility evidence for later isolated dependency PRs.

- [ ] **Step 1:** Let current self-hosted `App TV lint` finish; do not rerun while it is active.
- [ ] **Step 2:** Inspect job steps. Classify workload success separately from `Upload ... evidence` storage-quota failure.
- [ ] **Step 3:** Let exact-head `Self-hosted validation` execute. Required substantive gates: build-logic, configuration-cache create/reuse, JVM/unit suites, Android test compilation, Room schema check, lint/release assembly.
- [ ] **Step 4:** Let exact-head `Android TV product device matrix` execute only after host gate. It must sequentially use `MuxTV_TV_OLD_API26` then `MuxTV_TV_CURRENT_API36` and require non-empty `TEST-*.xml` with zero failures/errors.
- [ ] **Step 5:** If workload succeeds and only evidence upload fails, record `WORKLOAD_PASS / ARTIFACT_TRANSPORT_BLOCKED`; do not change product code.
- [ ] **Step 6:** If a substantive step fails, stop stack expansion and debug that exact step before any further version changes.
- [ ] **Step 7:** Freeze #190 as diagnostic evidence only. Do not merge the combined dependency bundle.

---

## Task 2: Execute frozen U0 without hosted runners

**Files:**
- Frozen source: PR #189 at `6d26ca89b8ea3404c8d766d790c28133c9a481d1`.
- Temporary executor: `.github/workflows/u0-self-hosted-executor.yml` on `exec/u0-self-hosted-20260824`.
- Probe: `tools/ui-characterization/probe/UiCharacterizationProbeTest.kt`.
- Harness: `tools/ui-characterization/Invoke-TvUiCharacterization.ps1`.
- Analyzer: `tools/ui-characterization/Analyze-TvUiCharacterization.ps1`.

**Interfaces:**
- Consumes immutable refs:
  - A `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`
  - B `515072022d11b218fcb20f43079f94098b3ea973`
  - C `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9`
- Produces `ui-characterization-analysis.json`, `ui-characterization-analysis.md`, screenshots, semantics tree, per-case manifests, focus traces and SHA-256 evidence manifest.

- [ ] **Step 1:** Executor checkout must resolve exactly to frozen source SHA, not executor branch SHA.
- [ ] **Step 2:** Run self-hosted preflight with required labels `muxtv-android,muxtv-device`, no pre-connected device, and only API26/API36 system-image ownership.
- [ ] **Step 3:** Run U0 static contracts:
  - `tools/android/Test-TvHarnessSyntax.ps1`
  - `tools/ui-characterization/Test-TvUiCharacterizationHarness.ps1`
  - `tools/ui-characterization/Test-TvUiStartupRecovery.ps1`
  - `tools/ui-characterization/Test-TvUiDeviceAdmission.ps1`
  - `tools/ui-characterization/Test-TvUiSourceFacts.ps1`
  - `tools/ui-characterization/Test-TvUiCharacterizationAnalyzer.ps1`
- [ ] **Step 4:** Compile the byte-identical probe against A, B and C with `Compile-TvUiCharacterizationProbe.ps1`; all three outputs must report one identical `probeSha256`.
- [ ] **Step 5:** Run A/B/C sequentially at:
  - `1920x1080 @ 320dpi`
  - `1280x720 @ 213dpi`
  - `1280x720 @ 320dpi` stress
- [ ] **Step 6:** Permit only the existing bounded one-time retry when the exact early QEMU modem transport signature is present and no characterization case has passed.
- [ ] **Step 7:** Run `Analyze-TvUiCharacterization.ps1`.
- [ ] **Step 8:** Persist complete evidence outside the runner workspace under `%USERPROFILE%\MuxTV-Evidence\U0\<run-id>-6d26ca89b8ea` and generate SHA-256 manifest.
- [ ] **Step 9:** Reset Android runner state in `always()` and verify final AVD inventory still contains only canonical identities.

**U0 acceptance predicates from analyzer:**

- `failedCaseCount == 0`
- exactly one `probeSha256`
- `avdName == "MuxTV_TV_CURRENT_API36"`
- `representativeComparisonCount > 0`
- focus conclusions are used only where `focusContractEligible == true`

---

## Task 3: Classify H1-H4 from runtime evidence

**Files:**
- Read: U0 `ui-characterization-analysis.json` and `.md`.
- Create after runtime: `docs/superpowers/reports/2026-08-25-lounge-ui-regression-forensics.md` on the future U1 branch.

**Interfaces:**
- Consumes analyzer fields.
- Produces explicit U1 admission decision; no product mutation.

- [ ] **Step 1: H1 shared reservation.** Mark H1 proven only when representative rows across multiple destinations satisfy `abMatchesExpectedSharedShellShift == true` and `cMatchesBContentOrigin == true`. Expected A->B delta is `+50dp ±2dp`.
- [ ] **Step 2: H2 rail visual semantics.** Compare `aRailItemWidthDp`, `bRailItemWidthDp`, `cRailItemWidthDp` plus before/during-rail screenshots. H2 is independent from H1: permanent/narrow B rail can be visually wrong even if content origin is stable while focused.
- [ ] **Step 3: Focus.** Use `allEligibleFocusRowsReachRailForBack`, `allEligibleFocusRowsMoveAwayFromRailOnBack`, `allEligibleFocusRowsReachRailForRight`, and `allEligibleFocusRowsMoveAwayFromRailOnRight`. Do not infer focus correctness from title-only geometry anchors.
- [ ] **Step 4: H3 global tokens.** Compare runtime screenshots against source facts for focus outline, section gap, hero/section/card/metadata typography and Home card size. A token rollback is admissible only for regressions visible in representative TV modes, not stress-only differences.
- [ ] **Step 5: H4 #180.** Keep one-line CTA containment only if `1280x720 @ 213dpi` demonstrates actual wrapping/clipping/overflow. If the failure exists only at `1280x720 @ 320dpi`, classify #180 as stress hardening rather than representative-TV fix.
- [ ] **Step 6:** Write one owner per proven defect: `AppNavigation` shared shell, `MuxTvNavigationRail`, shared `TvTokens`, or Home-local surface. Reject compensating per-screen padding when common-shell evidence explains the shift.

---

## Task 4: Create U1 branch only after U0 evidence

**Files:**
- Branch from accepted current `main` after U0 report is frozen.
- Do not branch from #190 dependency probe.

**Interfaces:**
- Consumes U0 report and exact current main.
- Produces isolated U1 history.

- [ ] **Step 1:** Re-read live `main`, #189, #180 and #178 before branching.
- [ ] **Step 2:** Create one U1 branch from current main.
- [ ] **Step 3:** Copy only the durable regression test/report needed from U0; do not carry executor workflow or dependency changes.

---

## Task 5: H1 RED regression contract

**Execute this task only if Task 3 proves H1.**

**Files:**
- Create: `app/tv/src/androidTest/kotlin/app/muxtv/AppNavigationShellGeometryTest.kt`
- Reuse behavior patterns from: `tools/ui-characterization/probe/UiCharacterizationProbeTest.kt`
- Existing source under test: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes stable product test tags `nav-home`, `nav-settings`, `home-hero`/`home-add-source`, `settings-section-sources`.
- Produces a permanent behavioral regression proving content reservation is the collapsed rail slot and does not change during rail focus.

- [ ] **Step 1:** Build the test around the real `MainActivity`/application shell using `createAndroidComposeRule<MainActivity>()`, matching U0 rather than a fake shell.
- [ ] **Step 2:** For Home and Settings, capture anchor `boundsInRoot.left` before rail entry.
- [ ] **Step 3:** Send native `KEYCODE_DPAD_LEFT`, assert expected rail item owns focus, capture anchor left while rail owns focus.
- [ ] **Step 4:** Send `KEYCODE_DPAD_RIGHT`, assert focus moved away from rail and capture anchor left again.
- [ ] **Step 5:** Assert `beforeLeft == duringRailLeft == afterRightLeft`.
- [ ] **Step 6:** Assert expected content origin in dp equals the U0 accepted A contract within the same rounding tolerance. The expected value must be derived from U0 evidence and shared shell token, not screenshot pixels.
- [ ] **Step 7:** Run only the new test on the uncorrected U1 base and observe RED specifically on content reservation/origin. If it passes before production change, H1 is not represented by this test and production code must not be edited from it.
- [ ] **Step 8:** Commit RED alone.

---

## Task 6: Minimal H1 production GREEN

**Execute only after Task 5 observed RED.**

**Files:**
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Do not modify destination-specific Home/Channels/Guide/Search/Settings padding for H1.

**Interfaces:**
- Current regressed expression: `Modifier.padding(start = TvTokens.Size.railExpanded)`.
- Required shared-shell expression when H1 is proven: `Modifier.padding(start = TvTokens.Size.railCollapsed)`.

- [ ] **Step 1:** Change only the shared NavDisplay reservation from `railExpanded` to `railCollapsed`.
- [ ] **Step 2:** Run the exact RED test; it must turn GREEN.
- [ ] **Step 3:** Run `HomeJourneyTest`, `ChannelsFocusRestorationTest`, `GuideFocusJourneyTest`, `AppNavigationSourceJourneyTest`, Search/Settings focus journeys present on the branch, and Android test compilation.
- [ ] **Step 4:** Do not restore the historical 248dp rail or typography in the same commit. H2/H3 are separate evidence owners.
- [ ] **Step 5:** Commit the one-owner GREEN.

---

## Task 7: H2 rail behavior RED/GREEN

**Execute only if Task 3 proves H2 as a user-visible regression after H1 is separated.**

**Files:**
- Modify only after RED: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvNavigationRail.kt`
- Potential token change only if evidence supports it: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt`
- Test: add/extend design-system Android/Compose test nearest existing navigation rail tests.

**Required behavior contract:**
- content reservation remains owned by `AppNavigation` and is constant;
- rail visual width may transition independently;
- selected state is independent from focus state;
- labels/brand visibility follows the evidence-approved rail focus state;
- reduced-motion path snaps rather than animates;
- no new global focus state machine.

- [ ] **Step 1:** Write a RED rail test for the exact U0-proven visual-state defect.
- [ ] **Step 2:** Observe RED on current permanent-expanded rail.
- [ ] **Step 3:** Implement only the necessary rail visual-state behavior.
- [ ] **Step 4:** Run rail tests plus shell geometry test to prove visual width no longer affects destination constraints.
- [ ] **Step 5:** Commit separately from H1.

---

## Task 8: H3 token restoration by owner

**Execute only for representative-mode regressions proven in Task 3. Do not bulk-revert A tokens.**

**Files:**
- Modify selectively: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt`
- Modify Home-local files only when runtime evidence proves the size is Home-owned.

**Candidate deltas requiring separate justification:**
- `Focus.outlineWidth`: A `3dp`, current `1dp`
- `Spacing.sectionGap`: A `40dp`, current `16dp`
- `Size.homeCardWidth`: A `300dp`, current `120dp`
- `Size.homeCardHeight`: A `140dp`, current `72dp`
- `Typography.heroTitle`: A `48sp`, current `24sp`
- `Typography.sectionTitle`: A `26sp`, current `14sp`
- `Typography.cardTitle`: A `20sp`, current `10sp`
- `Typography.metadata`: A `15sp`, current `8sp`

- [ ] **Step 1:** Group only values that share one visual owner and one RED test.
- [ ] **Step 2:** Observe RED on representative 1080p/320 or 720p/213 evidence-derived contract.
- [ ] **Step 3:** Restore/minimally adjust the smallest set needed for GREEN.
- [ ] **Step 4:** Verify both representative modes; stress mode is diagnostic and must not force representative-TV shrinkage.
- [ ] **Step 5:** Commit each owner separately so it can be reverted independently.

---

## Task 9: Decide PR #180 from real 720p evidence

**Files:**
- Review PR #180 head `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9`.
- Shared action component modified by #180 only if representative evidence supports it.

- [ ] **Step 1:** Inspect C at `1280x720 @ 213dpi` and compare with B.
- [ ] **Step 2:** If CTA containment is broken in B and corrected in C at 213dpi with no 1080p regression, restack the defensive one-line policy into U1 as its own commit.
- [ ] **Step 3:** If C only helps 320dpi stress, do not present it as a 720p-TV fix; close/supersede #180 after U1 disposition is recorded.

---

## Task 10: U1 exact-head acceptance

**Files:**
- U1 branch only.

- [ ] **Step 1:** Run focused local/self-hosted test set for every changed owner.
- [ ] **Step 2:** Run exact-head self-hosted host validation.
- [ ] **Step 3:** Run API36 device acceptance and capture both representative display modes on the same canonical API36 AVD.
- [ ] **Step 4:** If shared design-system/navigation code changed, run API26 -> API36 Product Matrix sequentially.
- [ ] **Step 5:** Treat artifact-upload quota errors separately from substantive workload conclusions.
- [ ] **Step 6:** Verify final AVD inventory contains no third MuxTV AVD.
- [ ] **Step 7:** Merge U1 only with fresh exact-head substantive evidence.

---

## Task 11: Durable truth sync

**Files:**
- Modify after U1 acceptance:
  - `.work/CURRENT-STATE.md`
  - `.work/ROADMAP.md`
  - `.work/meta/status.yaml`
  - `docs/superpowers/reports/2026-08-25-lounge-ui-regression-forensics.md`

- [ ] **Step 1:** Remove stale dual-seek debt already closed by #175.
- [ ] **Step 2:** Record exact two-AVD infrastructure truth from #181.
- [ ] **Step 3:** Record the U0 proven cause(s), rejected hypotheses and U1 correction with exact SHAs/evidence paths.
- [ ] **Step 4:** Do not describe #190 combined dependency branch as accepted product baseline.

---

## Task 12: Handoff to M0/#178

**Files:**
- Restack PR #178 only after U1 lands.

- [ ] **Step 1:** Rebuild #178 from accepted post-U1 main instead of merging its old divergent history.
- [ ] **Step 2:** Preserve product Search behavior; correct measurement expectation to published-result boundaries.
- [ ] **Step 3:** Extend measurement variance path ownership/static contract.
- [ ] **Step 4:** Obtain exact-head host + measurement variance substantive GREEN before any performance tuning.

---

## Stop conditions

Stop production implementation and classify the blocker if any of the following occurs:

- U0 has any failed case unrelated to the already-classified one-time emulator modem startup failure;
- A/B/C probe hashes differ;
- any non-canonical AVD identity appears;
- H1 analyzer predicates do not support a cross-destination +50dp representative-mode shift;
- a proposed U1 RED test passes before the fix;
- AppNavigation correction requires destination-specific padding to make tests pass;
- focus restoration fails after the shell correction;
- API26 regression appears after shared design-system/navigation changes;
- measurement or dependency changes become necessary to make U1 pass.

## Completion definition

U1 is complete only when U0 evidence is reproducible, each accepted change has an observed RED and minimal GREEN, representative 1080p/720p geometry is stable, Back/Right focus restoration remains valid, the two-AVD contract is intact, repository truth is synchronized, and #178 can be restacked onto a stable product baseline.