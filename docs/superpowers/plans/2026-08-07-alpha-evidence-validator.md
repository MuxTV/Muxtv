# Alpha Evidence Validator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an executable, fail-closed validator for the existing MuxTV `0.1.0-alpha` evidence manifest contract without changing app runtime, release version, signing, R8, or CI topology.

**Architecture:** The validator is a PowerShell 7.4+ repository tool. JSON Schema remains the structural owner; `alpha-gates-v1.json` remains the canonical gate catalog; the validator enforces cross-field invariants that JSON Schema cannot express. It never fabricates evidence and never silently turns an ineligible manifest into an eligible release claim.

**Tech Stack:** PowerShell 7.4+, `Test-Json -SchemaFile`, `ConvertFrom-Json`, existing `docs/release/alpha-gates-v1.json`, existing Draft 2020-12 schema.

## Global Constraints

- Base exactly on `work/alpha-release-evidence-contract-31-v2` from accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`.
- Do not modify `app/**`, Gradle release configuration, signing, versionName/versionCode, R8, Baseline Profile, SBOM generation, or workflows in this package.
- Require PowerShell >= 7.4 because the repository schema uses Draft 2020-12 and `Test-Json` switched to the current schema engine in PowerShell 7.4.
- Schema validation is mandatory before semantic checks.
- Canonical `requiredByDefault=true` gates cannot be omitted or weakened to `required=false` by a manifest.
- Unknown manifest gate IDs are rejected fail-closed.
- Every `PASSED` gate must use `evidenceCommit == manifest.commit`, whether or not the manifest currently claims eligibility.
- Every artifact must use `sourceCommit == manifest.commit`; mixed-commit release evidence is invalid, not merely ineligible.
- `claimEligible=true` requires every required gate to be `PASSED`, the `security.redaction` gate to be `PASSED`, and at least one APK/AAB artifact with SHA-256 provenance.
- Evidence references and artifact names must reject high-confidence secret-bearing values and private-machine absolute paths.
- No RED/GREEN claim without actual PowerShell execution.

---

### Task 1: Lock semantic validation with an executable contract test

**Files:**
- Create: `tools/release/Test-AlphaEvidenceValidator.ps1`

**Interfaces:**
- Consumes future CLI: `Validate-AlphaEvidenceManifest.ps1 -ManifestPath <path> -SchemaPath <path> -GateCatalogPath <path>`.
- Produces a dependency-free PowerShell contract suite using temporary synthetic manifests.

- [ ] **Step 1: Build a canonical synthetic eligible manifest from the gate catalog**

The test helper must enumerate `alpha-gates-v1.json`. Required-by-default gates are emitted as `required=true`, `status=PASSED`, one safe evidence reference, and `evidenceCommit=<manifest commit>`. Optional gates are emitted as `required=false`, `status=PENDING`.

- [ ] **Step 2: Add a positive claim-eligible case**

Include one APK artifact with exact `sourceCommit`, a 64-hex SHA-256 and non-zero byte count. Expected validator result: success.

- [ ] **Step 3: Add required-gate omission and weakening cases**

Remove one required gate, then separately set one canonical required gate to `required=false`. Each must fail.

- [ ] **Step 4: Add unknown gate ID case**

Add `future.typo_gate`; expected failure.

- [ ] **Step 5: Add PASSED evidence-commit mismatch case**

Set one passed gate to another 40-char commit. Expected failure even with `claimEligible=false`.

- [ ] **Step 6: Add artifact mixed-commit case**

Set APK `sourceCommit` to another commit. Expected failure.

- [ ] **Step 7: Add ineligible required-status case**

Set a required gate to `PENDING` while keeping `claimEligible=true`. Expected failure.

- [ ] **Step 8: Add missing release-artifact provenance case**

Use `claimEligible=true` with no APK/AAB carrying a digest. Expected failure.

- [ ] **Step 9: Add redaction cases**

Evidence containing `?token=secret`, `Authorization: Bearer ...`, a URI with user-info, or a Windows absolute private-machine path must fail. A safe GitHub workflow URL/ID and repository-relative evidence path must remain valid.

- [ ] **Step 10: Execute RED**

Run:

```powershell
pwsh -NoProfile -File tools/release/Test-AlphaEvidenceValidator.ps1
```

Expected first RED: validator entry point does not exist.

- [ ] **Step 11: Commit test-only contract**

Commit: `test(release): define alpha evidence validator contract (#31)`.

---

### Task 2: Implement the minimal semantic validator

**Files:**
- Create: `tools/release/Validate-AlphaEvidenceManifest.ps1`

**Interfaces:**
- Parameters:
  - mandatory `ManifestPath`;
  - default schema path `docs/release/schemas/alpha-evidence-manifest.schema.json`;
  - default gate catalog path `docs/release/alpha-gates-v1.json`.
- Output: one safe summary object only after successful validation.
- Failure: terminating error with no secret-bearing value echoed.

- [ ] **Step 1: Fail if PowerShell < 7.4**

- [ ] **Step 2: Resolve the manifest/schema/catalog paths and require regular files**

- [ ] **Step 3: Run `Test-Json -LiteralPath $ManifestPath -SchemaFile $SchemaPath` before semantic parsing**

If false, throw a coarse `Alpha evidence manifest failed JSON Schema validation.` message.

- [ ] **Step 4: Load manifest and canonical gate catalog**

Use `ConvertFrom-Json -Depth 100` and construct a case-sensitive set of canonical gate IDs.

- [ ] **Step 5: Enforce canonical gate ownership**

Reject unknown manifest IDs. For each `requiredByDefault=true` catalog entry, require presence plus `required=true`.

- [ ] **Step 6: Enforce exact-commit evidence**

For every `PASSED` gate require `evidenceCommit` to equal `manifest.commit`. For every artifact require `sourceCommit` to equal `manifest.commit`.

- [ ] **Step 7: Enforce conservative redaction on evidence-reference values and artifact names**

Reject high-confidence secret patterns (`Authorization:`, `Cookie:`, bearer credentials, `token=`, `access_token=`, `refresh_token=`, `api_key=`, `password=`, `secret=`), URI user-info, Windows drive absolute paths, UNC paths, and query signatures such as `X-Amz-Signature`.

- [ ] **Step 8: Compute semantic claim eligibility**

`computedClaimEligible` is true only when all present `required=true` gates are PASSED, every canonical required gate is present/required, `security.redaction` is PASSED, and at least one APK/AAB has a SHA-256 and positive byte count.

- [ ] **Step 9: Reject a false positive claim**

If `manifest.claimEligible == true` and `computedClaimEligible == false`, throw `Manifest claims alpha eligibility without satisfying required evidence.`

Do not mutate the input manifest in this task.

- [ ] **Step 10: Emit safe validation summary**

Return only release version, commit, declared/computed eligibility, total gate count, required gate count, passed required count and artifact count.

- [ ] **Step 11: Execute GREEN**

Run the Task 1 contract suite. Expected: all cases pass.

- [ ] **Step 12: Commit production validator**

Commit: `feat(release): validate alpha evidence semantics (#31)`.

---

### Task 3: Integrate only after standalone GREEN

**Files:**
- Modify later: `tools/verify-local.ps1` or release workflow only after Task 2 is executable-green.

- [ ] **Step 1: Add a syntax/contract invocation to the repository verification path**
- [ ] **Step 2: Keep alpha manifest generation separate from validation**
- [ ] **Step 3: Do not enable release build changes in the same PR**
- [ ] **Step 4: Re-run exact-head host validation**

## Current execution state

- [x] Existing evidence contract/schema/catalog restacked on current main.
- [x] PowerShell `Test-Json -SchemaFile` verified in current Microsoft documentation; PowerShell 7.4+ is the explicit floor for this validator.
- [ ] Task 1 test-only contract authored.
- [ ] RED observed.
- [ ] Task 2 production validator implemented.
- [ ] GREEN observed.
- [ ] Repository verification integration.
