# MuxTV Stabilization Master Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a trustworthy pre-alpha baseline after the unvalidated Lounge UI push, enforce exactly two repository-owned Android TV AVD identities, then finish measurement correctness before resuming performance/release tuning.

**Architecture:** Work is split into four independently reviewable admission packages. D0 changes only Android test/measurement infrastructure. U0 gathers deterministic UI evidence without changing product behavior. U1 applies only the root UI correction proven by U0. M0 restacks and completes PR #178 after product/UI stability is re-established. No package may mix unrelated runtime, schema, player or dependency work.

**Tech Stack:** Kotlin, Compose for TV, PowerShell 7, Android Emulator/ADB, GitHub Actions self-hosted Windows runner, Gradle, repository exact-head evidence tooling.

**Spec:** `docs/superpowers/specs/2026-08-22-two-avd-device-contract-design.md` for D0; Lounge Light accepted implementation in PR #168 plus live `main@515072022d11b218fcb20f43079f94098b3ea973` for U0/U1; PR #178 issue/body and existing measurement contracts for M0.

## Global Constraints

- Local/repository-owned Android TV virtual device identities are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- No API 28/29/30 fallback AVD, benchmark AVD, catalog-measurement AVD, player-measurement AVD, variance AVD, 720p AVD or low-RAM AVD may be created.
- API 26 and API 36 system-image resolution is exact and fail-closed.
- Device workloads remain sequential on the singleton self-hosted device runner.
- Do not weaken `Runner.Listener` singleton preflight. Exactly one listener process is required before CI evidence is valid.
- Do not treat the non-repository clipboard image used by `515072` as durable design authority.
- Do not modify player seek ownership; PR #175 is already accepted and merged.
- Do not begin buffer/cache tuning until #178 correctness and subsequent measurement evidence are accepted.
- Do not claim emulator evidence as vendor MediaCodec/HDR/passthrough/weak-ARM evidence.
- Every production/configuration change follows RED -> observed failure -> minimal GREEN -> exact-head verification.

---

## Admission Package D0 — Exact two-AVD infrastructure contract

**Purpose:** Make every repository-owned Android validation/measurement/benchmark path reuse the same two AVD definitions.

**Primary files:**

- `tools/android/AndroidSdk.ps1`
- `tools/android/Invoke-TvDeviceValidation.ps1`
- `tools/android/Invoke-BenchmarkDryRun.ps1`
- `tools/android/Invoke-CatalogDatabaseDeviceValidation.ps1`
- `tools/android/Invoke-PlayerProxyDeviceValidation.ps1`
- `tools/android/Test-TvHarnessSyntax.ps1`
- `tools/android/Test-TwoAvdContract.ps1` (new)
- `tools/measurements/MeasurementProfiles.ps1`
- `tools/measurements/Invoke-MeasurementSeriesCore.ps1`
- `tools/measurements/Test-MeasurementHarnessSyntax.ps1` if an existing assertion must be updated for the canonical identity policy

### D0.1 Contract RED

- [ ] Add `Test-TwoAvdContract.ps1` with assertions that reject the current fallback and lane-specific AVD names.
- [ ] Wire the new contract into `Test-TvHarnessSyntax.ps1`.
- [ ] Run the static harness contract and observe failures for current main: `AllowOldEdgeFallback`, `MuxTV_VARIANCE_*`, `MuxTV_BENCHMARK_API36`, `MuxTV_CATALOG_MEASUREMENT_API*`, and `MuxTV_PLAYER_MEASUREMENT_API*`.
- [ ] Commit the RED contract alone.

### D0.2 Shared canonical identity GREEN

- [ ] Add `Get-MuxTvCanonicalAvdName` to `AndroidSdk.ps1` with exact API 26/36 mapping and fail-closed rejection for any other API.
- [ ] Remove `AllowOldEdgeFallback` from `Resolve-TvSystemImage` and its implementation.
- [ ] Change `Invoke-TvDeviceValidation.ps1` to resolve API 26 exactly and obtain both AVD names through the shared helper.
- [ ] Verify D0 static contract advances past fallback/main-device assertions.
- [ ] Commit the shared identity change.

### D0.3 Measurement/benchmark consolidation GREEN

- [ ] Remove `AllowOldEdgeFallback` from `MeasurementProfiles.ps1`; keep the three workload profiles but make them all exact API configurations.
- [ ] In `Invoke-MeasurementSeriesCore.ps1`, use the canonical AVD name for each repetition and remove per-repetition AVD deletion.
- [ ] Keep cold/wiped boot for repetition isolation.
- [ ] Change benchmark, catalog-measurement and player-measurement device scripts to use `Get-MuxTvCanonicalAvdName 36`.
- [ ] Run static Android + measurement harness contracts until GREEN.
- [ ] Commit the lane consolidation.

### D0.4 Exact-head evidence

- [ ] Run self-hosted host validation on the final D0 head.
- [ ] Run API 36 DeviceCurrent on the same head.
- [ ] Run API 26 + API 36 integration/device matrix on the same head.
- [ ] Confirm manifests have `requestedApi == resolvedApi` and AVD names are only the two canonical names.
- [ ] Confirm no third `MuxTV_*` AVD is left by repository-owned lanes.
- [ ] Update D0 PR body with exact SHA, workflow IDs and artifact digests.

**Exit criterion:** Merge only when the exact final head is green and the runner has exactly one `Runner.Listener`.

---

## Admission Package U0 — UI regression forensics, no product fix

**Purpose:** Prove exactly which geometry changes in `515072` caused the user-visible regressions before changing Compose code.

**Comparison heads:**

1. accepted pre-push baseline `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`;
2. current UI push `515072022d11b218fcb20f43079f94098b3ea973`;
3. PR #180 head only as a symptom-hardening comparison, not assumed solution.

**Primary surfaces:** Home, expanded/collapsed rail, Channels, Guide, Search, Settings/Sources, shared action buttons, player overlay only where shared tokens affect it.

### U0.1 Repro harness

- [ ] Use only `MuxTV_TV_CURRENT_API36`.
- [ ] Rebuild and capture the same deterministic journey at 1920x1080 for each comparison head.
- [ ] On the same running/canonical API 36 device, apply a temporary 1280x720 display mode, recreate the Activity, execute the same journey, capture evidence, then restore 1920x1080 in `finally`.
- [ ] Preserve focus/D-pad traces together with screenshots; visual evidence without reachability evidence is insufficient.

### U0.2 Geometry diff

- [ ] Compare shared rail slot/expanded width, Home hero content column, card width/height/padding, primary CTA bounds, text line count, focus outline containment, and section visibility.
- [ ] Classify each regression as shared design token, screen-local layout, text overflow policy, or test-only artifact.
- [ ] Identify the earliest commit where each regression appears.

### U0.3 Root-cause report

- [ ] Write `docs/superpowers/reports/2026-08-22-lounge-ui-regression-forensics.md` with `baseline -> 515072 -> #180` evidence.
- [ ] State one primary root hypothesis per affected shared primitive and evidence that falsifies alternatives.
- [ ] Do not implement UI changes in U0.

**Exit criterion:** A reviewer can point to a concrete shared token/layout mutation and reproduce the regression on API 36 at 1080p/720p.

---

## Admission Package U1 — Evidence-driven Lounge UI correction

**Purpose:** Fix the proven shared-layout cause while preserving accepted Lounge D-pad/focus/navigation semantics.

### U1.1 RED regression contract

- [ ] Add the smallest Compose/unit/instrumentation contract that fails on the exact U0 root cause. Examples are geometry/token assertions for shared primitives plus a focused 720p journey for the impacted action/content surface.
- [ ] Observe the RED on the uncorrected `515072`-derived branch.

### U1.2 Minimal product fix

- [ ] Change only the shared token/layout owner identified by U0.
- [ ] Do not stack local compensating widths/paddings on individual destinations unless U0 proves the defect is destination-specific.
- [ ] Re-evaluate PR #180: keep one-line ellipsis only if it remains valid defensive policy after geometry correction; otherwise supersede or narrow it.

### U1.3 Behavioral + visual GREEN

- [ ] Run focused Compose/unit tests.
- [ ] Run exact-head host validation.
- [ ] Run API 36 DeviceCurrent.
- [ ] Capture 1080p and 720p on the same API 36 AVD.
- [ ] Run API 26 + API 36 integration acceptance if shared design-system code or navigation/focus behavior changed.
- [ ] Verify no regression of Channels/Guide/Search/Settings/Player D-pad journeys.

### U1.4 Durable truth sync

- [ ] Update `.work/CURRENT-STATE.md` from the old #168 snapshot to the newly accepted stabilization baseline only after merge evidence exists.
- [ ] Remove the now-closed #132 seek-authority debt from the reviewed snapshot because #175 is merged.
- [ ] Record the two-AVD contract as repository infrastructure truth.
- [ ] Record the UI correction based on repository-owned evidence, not the clipboard reference.

**Exit criterion:** Stable product baseline with exact-head behavioral and visual evidence.

---

## Admission Package M0 — PR #178 measurement correctness

**Purpose:** Repair measurement truth only after product/UI baseline stops moving.

### M0.1 Restack

- [ ] Rebuild/restack #178 onto the accepted post-U1 main instead of merging its currently diverged branch.
- [ ] Preserve its proven product conclusion: production Search publishes boundaries from actual result rows; the measurement runner's global-first-channel expectation is wrong.

### M0.2 Measurement RED/GREEN

- [ ] Keep/restore the fixture helper proving selective published-channel and broad-result earliest-boundary semantics.
- [ ] Replace the invalid global boundary assertion in the measurement runner with the published-result-derived expectation.
- [ ] Extend `measurement-variance-smoke.yml` path ownership to react to the catalog database measurement runner/tests.
- [ ] Extend the static harness contract to fail if those paths are removed.
- [ ] Run exact-head host + measurement variance GREEN.

**Exit criterion:** #178 correctness accepted on the stable baseline with no production Search query/schema change.

---

## Performance/release train after M0

Only after D0 + U0 + U1 + M0:

1. Review #27 evidence and choose which operations remain descriptive, warning-only or gate-worthy.
2. Measure seek/rebuffer on the already-accepted single service-owned seek authority from #175.
3. Consider #109 LoadControl/back-buffer/cache changes only if measurements show a user-visible bottleneck and memory trade-off is acceptable.
4. Add coarse secret-free seek/rebuffer Doctor observations only after the runtime metrics have stable semantics.
5. Complete release evidence: signing/SBOM/provenance, Baseline Profile/CUJ, physical weak/current TV, and separate API37 private-LAN behavior. Do not reinterpret a third emulator as part of the persistent two-AVD development matrix.

## Operational blocker handling

The current self-hosted runner has been observed with two `Runner.Listener` processes. Repository preflight is correct to reject this. Before any D0/U0/U1/M0 Action is accepted as evidence, runner administration must leave exactly one listener. Do not modify CI to auto-kill unknown runner processes and do not weaken the singleton assertion; that would turn an external administration defect into nondeterministic repository behavior.

## Final completion definition

The stabilization program is complete when:

- main has no unvalidated UI geometry push;
- repository tooling owns only the API26/API36 AVD identities;
- API26/API36 exact resolution is fail-closed;
- 720p/1080p validation reuses API36;
- reviewed repository truth includes merged #175 and no longer reports dual seek ownership;
- #178 measurement correctness is accepted on the stable baseline;
- performance work resumes only from valid evidence rather than stale or visually unstable product state.
