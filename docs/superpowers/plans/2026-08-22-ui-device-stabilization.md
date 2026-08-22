# UI + Device Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a reproducible Lounge Light UI baseline after `515072022d11b218fcb20f43079f94098b3ea973`, eliminate emulator-profile proliferation, and re-establish exact-head host/device acceptance before resuming measurement/performance work.

**Architecture:** Treat the UI regression and device-harness policy as two bounded stabilization tracks with a shared acceptance gate. Device validation is constrained to exactly two repository-owned AVD identities (Android TV API 26 and API 36), run sequentially; 720p/1080p are display modes on API 36, never separate AVDs. UI changes are evidence-driven: compare accepted baseline `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`, suspect direct push `515072022d11b218fcb20f43079f94098b3ea973`, and PR #180 head `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9` before changing shared layout tokens.

**Tech Stack:** Kotlin 2.3.x, Jetpack Compose for TV, Media3, PowerShell 7 Android harness, Android Emulator API 26/API 36, GitHub Actions self-hosted runner.

**Spec:** `docs/design/lounge-light/design-qa.md`, issue #93 Lounge Light design contract, current stabilization findings from PRs #175-#180 and commit `5150720`.

## Global Constraints

- Local virtual-device matrix contains exactly two repository-owned AVD identities: Android TV API 26 and Android TV API 36.
- API 26 is exact and fail-closed; no fallback to API 28/30/31/33/34/35.
- API 36 is exact and fail-closed.
- DeviceMatrix runs profiles sequentially; no parallel emulator fan-out.
- 1280x720 and 1920x1080 are display configurations of the API 36 AVD, not separate AVD profiles.
- Weak-device/low-RAM experiments may temporarily reconfigure API 36 but must not create a third persistent AVD identity.
- Do not delete or modify arbitrary user AVDs; cleanup may target only repository-owned `MuxTV_TV_*` identities.
- Do not weaken the self-hosted runner invariant requiring exactly one `Runner.Listener` process.
- Do not merge PR #178 measurement changes into this stabilization branch; restack #178 only after the UI/device baseline is accepted.
- Do not change player ownership/seek architecture completed by PR #175.
- UI fixes must address proven shared-layout causes; do not accumulate screen-local compensating constants.

---

## Execution order and dependency graph

1. **Runner hygiene** -> exact one listener; otherwise device evidence is invalid.
2. **Two-AVD contract** -> exact API 26 + API 36, no fallback/profile proliferation.
3. **A/B/C UI forensic capture** -> `2302c114` vs `5150720` vs `7a45487` at identical deterministic state.
4. **Canonical Lounge geometry decision** -> reconcile accepted issue #93 geometry with the temporary 1080p reference and 720p containment.
5. **Root UI repair** -> shared tokens/layout only where evidence proves regression.
6. **Exact-head acceptance** -> host + API36 1080p/720p + API26 matrix.
7. **Truth sync** -> design QA, current state, issues #31/#93/#101/#109/#132/#180.
8. **Restack #178** -> finish measurement correctness only on the stabilized main baseline.
9. **Resume #27/#179/#109** -> performance modernization after stable product/device baseline.

---

### Task 1: Enforce the exact two-device contract

**Files:**
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`
- Modify: `tools/android/AndroidSdk.ps1`
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`

**Interfaces:**
- Consumes: `Resolve-TvSystemImage -Tools <tools> -PreferredApi <api>`.
- Produces: exact API selection; canonical AVD names `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`; sequential matrix execution.

- [ ] **Step 1: Add RED contract assertions**

Add harness assertions that reject `AllowOldEdgeFallback`, require explicit API 26/API 36 resolutions, require the two canonical AVD names, and reject dynamically API-derived AVD names.

- [ ] **Step 2: Run the contract test and verify RED**

```powershell
pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1
```

Expected: FAIL because current code still exposes/uses `AllowOldEdgeFallback`.

- [ ] **Step 3: Remove fallback behavior from `Resolve-TvSystemImage`**

Change the public function signature to:

```powershell
function Resolve-TvSystemImage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Tools,
        [Parameter(Mandatory)][ValidateSet(26, 36)][int]$PreferredApi
    )
    # exact match only; otherwise throw with discovered images
}
```

Do not add replacement fallback logic.

- [ ] **Step 4: Make profile identities invariant**

In `Invoke-TvDeviceValidation.ps1` use:

```powershell
$oldImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 26
$currentImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 36
```

and canonical names:

```powershell
MuxTV_TV_OLD_API26
MuxTV_TV_CURRENT_API36
```

`DeviceCurrent` resolves/runs only API 36. `DeviceMatrix` resolves/runs API 26 followed by API 36.

- [ ] **Step 5: Verify GREEN**

```powershell
pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1
```

Expected: PASS.

- [ ] **Step 6: Run host verification**

```powershell
pwsh -NoProfile -File tools/verify-local.ps1 -Mode Full
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/android/Test-TvHarnessSyntax.ps1 tools/android/AndroidSdk.ps1 tools/android/Invoke-TvDeviceValidation.ps1
git commit -m "fix(android): enforce exact api26 api36 tv matrix"
```

---

### Task 2: Add bounded repository-owned AVD hygiene

**Files:**
- Modify: `tools/android/AndroidSdk.ps1`
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`

**Interfaces:**
- Produces: cleanup function that recognizes only names starting with `MuxTV_TV_` and preserves the two canonical identities.

- [ ] **Step 1: Add RED tests** for name filtering: canonical API26/API36 are preserved; stale `MuxTV_TV_*` names are eligible; unrelated user AVD names are never eligible.
- [ ] **Step 2: Verify RED** with `Test-TvHarnessSyntax.ps1`.
- [ ] **Step 3: Implement bounded cleanup** using `avdmanager list avd` / delete only for stale repository-owned names.
- [ ] **Step 4: Invoke cleanup before deterministic AVD creation**, not after successful evidence collection.
- [ ] **Step 5: Verify GREEN + Full host verification**.
- [ ] **Step 6: Commit** `fix(android): bound muxtv avd cleanup to owned profiles`.

---

### Task 3: Establish 720p/1080p as display modes, not emulator identities

**Files:**
- Modify: `tools/android/AndroidSdk.ps1`
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`
- Modify/create only if current QA harness requires it: the existing Lounge Light screenshot/device runner.

**Interfaces:**
- Produces: `Set-TvDisplayMode`/equivalent helper that changes size/density on the running API36 device and restores defaults.

- [ ] **Step 1: Add RED contract test** proving display modes contain exactly `1920x1080` and `1280x720` and do not introduce new AVD names.
- [ ] **Step 2: Verify RED**.
- [ ] **Step 3: Implement device display-mode helper** using ADB `wm size` / `wm density`, with `finally` restoration.
- [ ] **Step 4: Verify GREEN**.
- [ ] **Step 5: Run a DeviceCurrent smoke on API36** and record manifest showing one AVD identity with two display captures.
- [ ] **Step 6: Commit** `test(tv): reuse api36 avd for 720p and 1080p evidence`.

---

### Task 4: Repair self-hosted runner hygiene without weakening fail-closed preflight

**Files:**
- Inspect: `tools/ci/Assert-SelfHostedRunnerPreflight.ps1`
- Inspect/update only if necessary: runner service/bootstrap documentation or repository-owned maintenance script.

**Acceptance:** exactly one `Runner.Listener`; no change that converts duplicate listeners from failure into warning.

- [ ] Capture process/service evidence for both listeners.
- [ ] Determine whether duplication is service+interactive runner, two services, or orphaned process.
- [ ] Stop/remove only the duplicate registration/process; retain one canonical runner.
- [ ] Re-run `Test-SelfHostedRunnerPreflightContract.ps1` and actual preflight.
- [ ] Re-run failed #180 host/device jobs only after the invariant is restored.

---

### Task 5: Run A/B/C forensic UI comparison

**Refs:**
- A accepted baseline: `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`
- B suspect push: `515072022d11b218fcb20f43079f94098b3ea973`
- C current containment candidate: `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9`

**Routes/states:** Home hero, favorites rail, recent rail, navigation rail focused/unfocused; then Channels, Guide, Search, Sources/Settings smoke to detect shared-token collateral damage.

**Viewport matrix:** API36 1920x1080 and 1280x720 using the same AVD identity and deterministic data fixture.

- [ ] Capture identical screenshots and semantics bounds at A/B/C.
- [ ] Compare rail width, content origin, hero bounds, card width/height, row spacing, CTA containment, focus ring clipping, D-pad restoration.
- [ ] Record each delta as regression / intended reference change / ambiguous.
- [ ] Do not change product code until every P0/P1 regression has a reproducible A->B delta.

---

### Task 6: Restore canonical Lounge Light geometry at shared ownership points

**Likely files (confirm from Task 5 before editing):**
- `core/designsystem/src/main/kotlin/.../TvTokens.kt` or current token owner
- `core/designsystem/src/main/kotlin/.../Components.kt`
- Home feature composable(s)
- TV shell/navigation rail composable(s)
- corresponding Compose journey/unit tests

**Known suspect changes from `5150720`:** `railExpanded` reduced near 248dp -> 138dp; Home cards near 300x140dp -> 120x72dp; card padding 16dp -> 8dp; Home hero content constrained near 300dp. These are hypotheses to prove in Task 5, not values to blindly revert.

- [ ] Add RED regression tests from measured A/B deltas.
- [ ] Verify RED on `5150720`-derived branch.
- [ ] Fix the narrowest shared owner that explains multiple affected screens.
- [ ] Preserve #175 playback architecture and unrelated domain/data APIs.
- [ ] Verify GREEN at Compose/journey level.
- [ ] Capture API36 1080p + 720p evidence.
- [ ] Commit one root-cause slice at a time.

---

### Task 7: Exact-head acceptance gate

- [ ] Full host validation GREEN on final head.
- [ ] API36 1080p Home/journey evidence GREEN.
- [ ] API36 720p containment GREEN.
- [ ] DeviceMatrix sequential API26 -> API36 GREEN.
- [ ] Evidence manifest source commit equals PR head.
- [ ] No extra repository-owned persistent AVD identities remain.
- [ ] Self-hosted preflight confirms exactly one `Runner.Listener`.

---

### Task 8: Truth synchronization

**Files/issues:**
- `docs/design/lounge-light/design-qa.md`
- `.work/CURRENT-STATE.md`
- `.work/ROADMAP.md` / `.work/meta/status.yaml` if their ownership rules require update
- issues #31, #93, #101, #109, #132, #180

- [ ] Document the accepted canonical geometry and evidence refs.
- [ ] Remove stale implication that #132 still has dual seek authority; keep only remaining performance/diagnostic debt.
- [ ] Replace any local virtual-device wording implying >2 persistent profiles with exact API26+API36 policy.
- [ ] State that weak physical-device conclusions require physical hardware.
- [ ] Close/update #180 only after 720p/1080p exact-head evidence is green.

---

### Task 9: Restack measurement correctness and resume performance work

- [ ] Restack PR #178 onto stabilized main; do not merge its stale ancestry.
- [ ] Integrate `expectedCatalogSearchBoundaryEpochMillis(channelIndexes)` into the measurement runner.
- [ ] Add workflow routing for measurement runner/tests.
- [ ] Run exact-head host + variance smoke.
- [ ] Only after #178 is green, continue #27/#179/#109 dependency/performance experiments.

---

## Definition of Done

The stabilization is complete only when all of the following are true:

1. UI regression introduced by/after `5150720` is either corrected or explicitly accepted with reproducible evidence.
2. Home is contained and navigable at both 1920x1080 and 1280x720 on the same API36 AVD.
3. Local virtual acceptance uses exactly two AVD identities: API26 and API36.
4. No old-edge API fallback exists in the acceptance harness.
5. DeviceMatrix remains sequential.
6. Exactly one self-hosted `Runner.Listener` is active during evidence jobs.
7. Final evidence is generated from the exact PR head.
8. Documentation/issues describe current reality.
9. #178/performance work resumes only after this baseline is stable.
