# Alpha release evidence contract v2 plan

**Issue:** #31  
**Base:** accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`

## Objective

Define the machine-readable evidence contract that will later decide whether a `0.1.0-alpha` claim is eligible. This package is release governance only: it must not enable R8, change the app version, add signing material, generate a release artifact, or imply device compatibility.

## Restack state

- [x] Confirm historical `work/alpha-release-evidence-contract-31@36afed088572d9bb3cf3f317ce95143979d1cede` has only four release-doc/schema paths over its old base.
- [x] Create `work/alpha-release-evidence-contract-31-v2` from exact current main.
- [x] Restack `docs/release/alpha-evidence-contract.md` with current base identity.
- [x] Restack `docs/release/alpha-gates-v1.json`.
- [x] Restack `docs/release/schemas/alpha-evidence-manifest.schema.json`.
- [x] Keep app/runtime/build/signing configuration unchanged.

## Core claim rule

A future finalizer may set `claimEligible=true` only when all of the following hold on one exact release commit:

1. every required gate is `PASSED`;
2. every passed gate records `evidenceCommit == manifest.commit`;
3. no required gate is `PENDING`, `FAILED`, `BLOCKED`, or `DEFERRED`;
4. at least one release artifact has exact source-commit provenance plus digest where applicable;
5. the redaction gate is passed;
6. runtime-required gates use runtime evidence, not static review;
7. known limitations remain explicit.

The JSON schema can validate structure but cannot enforce all cross-field relationships. Therefore the next implementation package must be an executable finalizer/validator, not more prose.

## Next implementation after product critical path

### A. Finalizer first

Create one executable release-evidence finalizer that:

- validates the manifest against the schema;
- loads the canonical gate catalog;
- rejects missing/unknown required gate IDs;
- enforces `PASSED -> evidenceCommit == manifest.commit`;
- enforces artifact `sourceCommit == manifest.commit` for claim eligibility;
- rejects `claimEligible=true` with any required non-PASSED gate;
- requires deferral metadata for `DEFERRED`;
- performs conservative secret-bearing evidence-reference/path checks;
- never mutates a failed manifest into an eligible one silently.

Write executable contract tests before using the finalizer in release workflows.

### B. Functional release hardening

Only after the application critical path is stable:

- enable and validate R8/resource shrinking;
- add Baseline Profile generation/packaging;
- generate SBOM and dependency report;
- define alpha signing flow without storing secrets in repository evidence;
- update version/changelog only when the release train is genuinely eligible.

### C. Evidence matrix

Capture exact-head evidence for:

- old-edge and current Android TV virtual journeys;
- low-RAM profile;
- Room migration/upgrade;
- Keystore persistence/reset;
- TV recovery path;
- transport/player journeys;
- redacted diagnostics;
- repeated startup/frame/memory evidence;
- physical current Android/Google TV;
- constrained physical TV;
- Fire TV when available, otherwise explicitly BLOCKED/DEFERRED with scope effect.

## Runner-off rule

While the self-hosted runner is unavailable:

- the contract/schema may be restacked and statically reviewed;
- no gate may be marked `PASSED` merely because the files exist;
- no release version/minification/signing/runtime configuration should change;
- do not claim alpha readiness or compatibility.

## Current status

The v2 branch contains only release evidence contract material. It is not an alpha candidate and not release evidence. The highest-value next code item for #31 is the executable finalizer, but implementation should wait until the current product train (#29, recovery/measurement dependencies) has executable acceptance so the finalizer is designed against real evidence artifacts rather than speculative paths.
