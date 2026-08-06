# MuxTV 0.1.0-alpha evidence contract

Status: release-gate contract, not release evidence
Issue: #31
Base when authored: `main@ec2b7743183b227ef54c16989d061ae5d4775dee`

## Purpose

Issue #31 requires a reproducible `0.1.0-alpha` with R8/resource shrinking, Baseline Profile evidence, virtual and physical TV journeys, upgrade/security checks, signed artifacts, SBOM/dependency reporting and evidence-bounded compatibility claims.

The repository currently has no machine-readable owner for the final alpha gate. This document and `schemas/alpha-evidence-manifest.schema.json` define one so a partial set of green logs cannot accidentally be described as an accepted alpha.

This package does not enable R8, change `versionName`, create signing material, generate an SBOM or claim any device compatibility. Those actions require executable evidence and remain future #31 implementation work.

## Core rule

An alpha manifest is **claim-eligible only when every gate marked `required=true` is `PASSED` on the exact release commit and every artifact/evidence reference belongs to that same commit**.

`DEFERRED` is an explicit product decision, not success. A deferred item requires:

- a non-empty rationale;
- a tracking issue number;
- a statement explaining whether the deferral changes the advertised alpha scope/compatibility.

A required gate cannot be `DEFERRED` while `claimEligible=true`.

## Manifest identity

Every manifest records:

- schema version;
- repository;
- exact lowercase 40-character commit SHA;
- source branch/tag when relevant;
- requested release version;
- UTC generation timestamp;
- `claimEligible` boolean;
- gate map keyed by stable gate ID;
- release artifact records;
- known limitations.

Paths, workflow URLs and artifact names are evidence references only. They are never interpreted as proof unless the corresponding gate is `PASSED` and the recorded commit matches the manifest commit.

## Gate states

- `PENDING` — not executed or evidence not reviewed;
- `PASSED` — exact-head evidence exists and acceptance checks are satisfied;
- `FAILED` — executed and did not satisfy the gate;
- `BLOCKED` — cannot execute because of an external dependency/environment;
- `DEFERRED` — explicitly removed from the current alpha scope with issue+rationale.

Never use `PASSED` for a static code review when the gate requires execution.

## Canonical gate IDs for 0.1.0-alpha

### Scope / truth

- `scope.alpha_dependencies` — #24–#30 complete or explicitly scoped/deferred according to #31.
- `truth.version` — release version/code/changelog all agree.
- `truth.clean_tree` — release artifact comes from the recorded commit with no uncommitted source mutation.

### Release build

- `build.release_assemble` — release APK/AAB assembly succeeds.
- `build.r8_shrinking` — minification/resource shrinking enabled and verified by installed core journeys.
- `build.baseline_profile_packaged` — Baseline Profile is present in the release artifact.
- `build.signing` — intended alpha signing path produces the recorded artifact; no private key is stored in evidence.
- `build.sbom` — SBOM generated for the exact release dependency graph.
- `build.dependency_report` — dependency/version/license report archived.

### Functional TV matrix

- `virtual.old_edge` — oldest declared Android TV API profile completes non-zero core D-pad journeys.
- `virtual.mainstream` — representative mainstream API profile when the project declares one for this release.
- `virtual.current` — current API profile (currently API 36 target evidence) completes non-zero core journeys.
- `virtual.low_ram` — constrained virtual profile completes the declared low-RAM smoke/endurance scope.

Each virtual gate must record API, ABI, emulator/device profile and non-zero test/journey counts.

### Database / upgrade / security

- `data.room_current_schema` — Room current schema hash/identity and migration chain verified on exact release commit.
- `data.upgrade_previous_supported` — install/upgrade from declared previous schema/app baseline succeeds without destructive fallback.
- `security.keystore_persistence` — normal upgrade preserves expected credential access behavior.
- `security.keystore_reset` — reset/recovery path behaves explicitly and does not expose secret material.
- `security.redaction` — release diagnostics/evidence contain no private playlist, locator token, Authorization/Cookie/header or credential material.

### Product recovery

- `recovery.tv_path` — at least one recovery path is actually operable on TV without relying solely on a touch-oriented system picker, as required by #113/#31.
- `recovery.failure_previous_good` — failed restore/reset does not silently destroy previous-good local state where the accepted restore contract requires rollback/preservation.
- `recovery.user_docs` — alpha notes contain recovery/reset instructions and known limitations.

### Player / diagnostics

- `player.core_journey` — Channels/Guide/Search as included in alpha scope can reach Player and return with focus ownership preserved.
- `player.transport` — accepted typed transport path is exercised on reproducible fixtures.
- `diagnostics.export` — redacted diagnostic export/path documented and verified.

### Physical device evidence

- `physical.current_android_tv` — at least one current Google/Android TV device when available.
- `physical.constrained_tv` — at least one constrained/weak device when available.
- `physical.fire_tv` — Fire TV/Quality Central evidence when available; if unavailable it must be `BLOCKED` or explicitly `DEFERRED`, never silently absent.

Physical-device results must record model, OS/API/Fire OS, ABI/SoC where known, relevant decoder/renderer identity where observed, and exact release commit. A physical observation never becomes a universal vendor/codec compatibility claim.

### Performance

- `perf.baseline_profile_effect` — before/after repeated startup/journey evidence demonstrates the packaged Baseline Profile effect.
- `perf.startup_frames_memory` — startup/frame/memory evidence exists for declared normal and low-RAM profiles.
- `perf.measurement_provenance` — performance reports identify environment, exact commit, corpus/fixture identity and repetition count; no single-run optimization claim.

## Gate evidence record

Each gate contains:

- `required`;
- `status`;
- `evidenceCommit` (required for `PASSED`, equal to manifest commit);
- zero or more evidence references;
- optional structured facts safe for publication;
- optional blocker/defer metadata.

Evidence references may point to workflow run IDs, job IDs, artifact names or repository-relative evidence directories. They must not contain secrets or signed URLs with credentials.

## Claim eligibility algorithm

A finalizer may set `claimEligible=true` only if:

1. manifest commit/version fields are valid;
2. every `required=true` gate is `PASSED`;
3. every passed gate has `evidenceCommit == manifest.commit`;
4. no required gate is pending/failed/blocked/deferred;
5. at least one release artifact is recorded with SHA-256 and exact commit provenance;
6. known limitations are explicit rather than hidden in omitted gates;
7. secret/redaction gate is passed;
8. release artifacts themselves have been installed/exercised where their gate requires runtime evidence.

This algorithm should later become executable validation. This offline package defines the contract only.

## Artifacts

Release artifact records contain only safe provenance:

- artifact type (`APK`, `AAB`, `SBOM`, `DEPENDENCY_REPORT`, `BASELINE_PROFILE_EVIDENCE`, etc.);
- repository-relative artifact/evidence name or CI artifact name;
- SHA-256 where applicable;
- byte size where applicable;
- exact source commit.

Do not store signing passwords, keystore paths from private machines, temporary download tokens or private provider material in the manifest.

## Compatibility claims

Alpha release notes may state only what the evidence supports. Examples:

- acceptable: “API 26 and API 36 virtual Android TV journeys passed on commit …”;
- acceptable: “Observed AC-3 playback on device X / OS Y with renderer Z”;
- unacceptable: “All Android TVs support AC-3/HDR/passthrough” from one emulator/device;
- unacceptable: “Fire TV supported” when the Fire gate is absent/pending.

## Runner-offline status

This document/schema are preparation only. No #31 gate is changed to `PASSED` by authoring them. In particular, current `app/tv` still reports `versionName = 0.0.1` and release minification is disabled on accepted main; those are future implementation items after the product critical path and executable release validation are available.
