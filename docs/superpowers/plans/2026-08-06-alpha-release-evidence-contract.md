# Alpha release evidence contract implementation plan

**Issue:** #31
**Base:** accepted `main@ec2b7743183b227ef54c16989d061ae5d4775dee`
**Execution constraint:** self-hosted runner unavailable while this contract package is authored. No release/build/device gate is considered passed by these documentation changes.

## Objective

Turn #31 from a prose checklist into a fail-closed exact-head release gate before enabling R8, changing the version to `0.1.0-alpha` or producing signed artifacts.

## Current release gap

Accepted `app/tv/build.gradle.kts` still has:

- `versionCode = 1`;
- `versionName = "0.0.1"`;
- `release { isMinifyEnabled = false }`.

This is expected pre-alpha state, not a defect to patch while execution is unavailable. The risk is instead that future partial evidence (for example a green assemble + one emulator) could be summarized as “alpha ready” without proving the rest of #31.

## Package A — contract only (offline-safe)

- [x] Add `docs/release/alpha-evidence-contract.md`.
- [x] Add Draft 2020-12 structural schema at `docs/release/schemas/alpha-evidence-manifest.schema.json`.
- [x] Keep the schema structural; secret redaction and cross-field exact-commit equality belong to the future executable finalizer rather than fragile schema regex.
- [ ] Add an executable manifest finalizer/validator only after its test-only contract can be run.
- [ ] Do not create a “passed” example manifest. Any template must use `claimEligible=false` and all gates pending.

## Future Package B — executable finalizer (TDD after runner returns)

Create a pure host-side validator, preferably in existing repository tooling rather than a new runtime dependency.

Required test-first behavior:

1. reject invalid/non-lowercase/non-40-char commit;
2. reject unknown/missing required canonical gates;
3. reject `claimEligible=true` if any `required=true` gate is not `PASSED`;
4. reject passed gate without evidence;
5. reject passed gate whose `evidenceCommit` differs from root manifest commit;
6. reject artifact whose `sourceCommit` differs from root manifest commit;
7. reject `DEFERRED` without issue+rationale+scope effect;
8. reject `claimEligible=true` with required deferred/blocked/pending/failed gate;
9. reject claim-eligible manifest without at least one release APK/AAB artifact with SHA-256;
10. reject unsafe evidence references/notes using the repository redaction policy rather than embedding secret heuristics only in JSON Schema;
11. require `security.redaction` itself to be passed on exact head;
12. preserve payload-free diagnostics — report gate IDs/reasons, not arbitrary evidence file contents.

No production finalizer should be written before these tests show the expected RED.

## Future Package C — release build hardening

Only after product critical-path acceptance (#128/#29/#127/#30 as scoped) and a working finalizer:

1. set version/versionCode according to release policy;
2. enable minification/resource shrinking with justified keep rules;
3. add Baseline Profile generation/packaging;
4. add release-specific install/journey validation;
5. generate SBOM + dependency/license report;
6. archive artifact SHA-256/size/source commit;
7. populate manifest gates from exact workflow/device evidence;
8. finalizer decides claim eligibility.

## Future Package D — virtual/device gate

Required evidence categories come from `alpha-evidence-contract.md`:

- old-edge/current/mainstream virtual profile as declared;
- low-RAM profile;
- current/constrained physical Android TV where available;
- Fire TV/Quality Central where available or explicitly blocked/deferred;
- install/upgrade/Room/Keystore recovery;
- core D-pad navigation/Player return;
- recovery/reset path;
- performance/Baseline Profile evidence;
- diagnostic redaction/export.

Physical observations remain device-specific facts. The manifest/finalizer must not automatically turn one device result into a universal compatibility statement.

## Acceptance for this offline package

The only defensible claim while the runner is off is that a versioned release-evidence contract/schema has been authored on an isolated branch. It is not executed validation.

When execution returns, this #31 branch is lower priority than the product critical path. Validate/merge it only after #128 exact-head acceptance is resumed, then implement the finalizer test-first before release hardening begins.
