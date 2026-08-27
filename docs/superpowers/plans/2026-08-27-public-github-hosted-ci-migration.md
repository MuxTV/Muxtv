# Public GitHub-Hosted CI Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the removed Windows self-hosted runner with secure, ephemeral GitHub-hosted CI for the public MuxTV repository while preserving stable PR check names and the two-AVD Android TV contract.

**Architecture:** Host-only validation stays on `windows-latest` so the existing Windows/PowerShell verification harness can be reused with minimal semantic change. Android TV connected tests move to `ubuntu-latest` with KVM-backed hardware acceleration and exactly the canonical API26/API36 AVD identities. Device jobs are isolated and parallel; one aggregate job preserves the stable `Android TV product device matrix` check name. U0 remains evidence-first and uses only API36, but its emulator ownership must be migrated away from persistent self-hosted machine assumptions.

**Tech Stack:** GitHub Actions, Windows/Linux standard GitHub-hosted runners, PowerShell 7, Gradle/AGP, Android Emulator, KVM, Android TV system images, ReactiveCircus Android Emulator Runner where it reduces repository-owned emulator lifecycle code.

**Spec:** GitHub issue #210 and `.work/ARCHITECTURE.md`.

## Global Constraints

- The repository is public; no public PR code may execute on a private self-hosted machine.
- Standard GitHub-hosted runners only; no larger runner is required for the initial migration.
- Preserve known stable required-check names: `Full validation`, `App TV lint`, `Media3 lint`, and aggregate `Android TV product device matrix`.
- Repository-owned Android TV identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- API26 uses `system-images;android-26;android-tv;x86`; API36 uses `system-images;android-36;android-tv;x86_64`.
- Do not introduce API30/API35/API37 or another AVD identity.
- Public fork PRs get read-only repository permissions and must not require secrets.
- Artifact storage remains bounded and is owned separately by #209; hosted compute does not justify weakening evidence-publication semantics.
- U0 immutable comparison refs remain A `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`, B `515072022d11b218fcb20f43079f94098b3ea973`, C `7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9` until #188 explicitly changes them.

---

### Task 1: Hosted CI migration contract

**Files:**
- Create: `tools/ci/Test-GitHubHostedWorkflowContract.ps1`
- Create: `.github/workflows/hosted-ci-contract.yml`

**Interfaces:**
- Consumes: repository workflow files under `.github/workflows`.
- Produces: deterministic failure while required workflows still reference `self-hosted` runner labels or self-hosted preflight/reset helpers.

- [ ] **Step 1: Write the failing contract**

The contract must enumerate the supported workflows explicitly, inspect their text, and reject `runs-on` values containing `self-hosted`. It must also reject `Assert-SelfHostedRunnerPreflight.ps1` and `Reset-SelfHostedAndroidState.ps1` from the migrated workflow set.

- [ ] **Step 2: Run it on `windows-latest`**

Run:

```powershell
pwsh -NoProfile -File ./tools/ci/Test-GitHubHostedWorkflowContract.ps1
```

Expected before migration: FAIL naming at least `self-hosted-validation.yml` and `android-tv-product-device-matrix.yml`.

- [ ] **Step 3: Commit the RED contract**

Commit only the contract and its hosted workflow so the RED is attributable to the old runner architecture.

---

### Task 2: Ordinary PR host validation

**Files:**
- Modify: `.github/workflows/self-hosted-validation.yml`

**Interfaces:**
- Consumes: `tools/verify-local.ps1`, existing evidence uploader.
- Produces: stable PR check `Full validation` on `windows-latest`.

- [ ] **Step 1: Replace persistent runner routing**

Use `runs-on: windows-latest` for ordinary host validation. Remove the self-hosted preflight and final machine-reset steps. Checkout may use the normal clean ephemeral runner behavior.

- [ ] **Step 2: Narrow manual modes**

Keep `Fast` and `Full` host modes in this workflow. Device modes must be rejected or routed to dedicated hosted Android workflows rather than pretending a Windows hosted runner has the old AVD inventory.

- [ ] **Step 3: Preserve provenance and stable check name**

Continue checking the exact PR head SHA and preserve job name `Full validation` for pull requests.

- [ ] **Step 4: Run the contract and the PR host workflow**

Expected: hosted contract advances; `Full validation` starts on a GitHub-hosted Windows VM and executes the existing host verification path.

---

### Task 3: Hosted lint jobs

**Files:**
- Modify: `.github/workflows/app-tv-lint.yml`
- Modify: `.github/workflows/media3-lint.yml`

**Interfaces:**
- Consumes: preinstalled Android SDK on GitHub-hosted Windows plus repository initialization helper.
- Produces: stable `App TV lint` and `Media3 lint` checks.

- [ ] **Step 1: Move both jobs to `windows-latest`**

Remove self-hosted preflight/reset and persistent-workspace cleanup assumptions that are unnecessary on ephemeral VMs.

- [ ] **Step 2: Keep exact-head provenance**

Do not change lint task scope or baseline semantics.

- [ ] **Step 3: Verify both checks on the migration PR**

Expected: substantive lint task succeeds/fails independently of artifact publication.

---

### Task 4: GitHub-hosted Android TV API26/API36 product matrix

**Files:**
- Modify: `.github/workflows/android-tv-product-device-matrix.yml`
- Create: `tools/ci/Assert-AndroidTestResults.ps1`

**Interfaces:**
- Consumes: Gradle connected Android tests for `catalog:importer`, `catalog:refresh`, `core:credentials`, `core:database`, `player:media3`, and `app:tv`.
- Produces: two isolated device verdicts and one aggregate `Android TV product device matrix` check.

- [ ] **Step 1: Add cross-platform result-count contract**

Parse `TEST-*.xml` under each module's `build/outputs/androidTest-results`, require at least one executed test and zero failures/errors, and emit a small JSON evidence summary.

- [ ] **Step 2: Define matrix**

Use `ubuntu-latest` with:

```yaml
matrix:
  include:
    - api: 26
      arch: x86
      avd: MuxTV_TV_OLD_API26
    - api: 36
      arch: x86_64
      avd: MuxTV_TV_CURRENT_API36
```

- [ ] **Step 3: Enable KVM**

Use the GitHub-hosted Linux KVM permission sequence before starting the emulator.

- [ ] **Step 4: Start Android TV emulator**

Use a pinned `ReactiveCircus/android-emulator-runner` v2 commit, `target: android-tv`, the matrix architecture, `profile: tv_1080p`, exact `avd-name`, headless/no-audio/no-snapshot-save options, and no additional AVD identity.

- [ ] **Step 5: Run device tasks**

Run the six existing connected test modules with `./gradlew ... --no-daemon --stacktrace --console=plain --no-problems-report`, then run `Assert-AndroidTestResults.ps1`.

- [ ] **Step 6: Aggregate**

Add a final lightweight job named exactly `Android TV product device matrix` that runs on `ubuntu-slim` or `ubuntu-latest`, depends on both matrix jobs, and fails unless both concluded success.

---

### Task 5: Remaining self-hosted workflow inventory

**Files:**
- Modify as required: `android-tv-focused-device.yml`, `database-migration-device-matrix.yml`, `benchmark-foundation.yml`, `measurement-variance-smoke.yml`, `focused-m3u-evidence.yml`, `integration-gate.yml`, `phase00-red.yml`.

**Interfaces:**
- Consumes: Task 1 contract allowlist.
- Produces: no supported workflow silently queues for a removed runner.

- [ ] **Step 1: Classify each workflow**

Host-only workflows move to `windows-latest`; emulator workflows move to the hosted Android pattern; specialized measurement workflows that cannot produce meaningful numbers on ephemeral hardware remain explicit manual evidence lanes and must state their hosted-environment interpretation.

- [ ] **Step 2: Remove all supported self-hosted routing**

The contract must become GREEN only after every supported workflow is migrated or explicitly retired.

---

### Task 6: U0 hosted characterization

**Files:**
- Modify on #189 branch or a restacked successor: U0 executor/device workflow and the minimum characterization harness needed to consume an externally managed hosted API36 emulator.

**Interfaces:**
- Consumes: immutable A/B/C refs and corrected probe contract.
- Produces: complete 3 refs × 3 display-profile characterization on `MuxTV_TV_CURRENT_API36` without local runner state.

- [ ] **Step 1: Keep characterization logic separate from emulator ownership**

Do not port the whole Windows machine lifecycle merely to preserve it. Hosted CI owns ephemeral emulator provisioning; the repository probe owns A/B/C installation, display profile changes, screenshots, semantics and analysis.

- [ ] **Step 2: Reproduce current runtime RED first**

The current `Эфир` title failure demonstrates that source text is not a reliable runtime semantics anchor. Add a probe anchor contract based on a stable explicit test tag or another cross-ref runtime-observable seam before changing the probe.

- [ ] **Step 3: Run full corrected U0**

Only a complete corpus may advance H1-H4 classification and U1.

---

### Task 7: PR revalidation and cleanup

**Files:**
- GitHub PR metadata/branch events only unless a branch needs an actual rebase/restack.

**Interfaces:**
- Consumes: merged hosted CI baseline.
- Produces: fresh hosted verdicts for every open PR that needs CI.

- [ ] **Step 1: Enumerate open PRs and required path-triggered checks**

Current expected set includes #178, #180, #189, #190 and #207; re-read live GitHub before execution.

- [ ] **Step 2: Trigger a new synchronize event without changing net PR scope**

Prefer updating/restacking same-repository branches onto the hosted CI baseline. If a branch should not be restacked for provenance reasons (notably U0), use its dedicated hosted executor rather than mutating immutable evidence refs.

- [ ] **Step 3: Classify every check**

Separate workload verdict from artifact-publication verdict under #209.

- [ ] **Step 4: Retire obsolete local-runner issue**

Close #208 as superseded only when no supported workflow depends on the removed runner.
