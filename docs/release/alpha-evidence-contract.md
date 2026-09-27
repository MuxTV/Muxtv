# MuxTV 0.1.0-alpha evidence contract

Status: release-gate contract, not release evidence
Issue: #31

## Purpose

Issue #31 requires a reproducible `0.1.0-alpha` whose compatibility and quality claims are bounded by evidence collected on the exact release commit. This contract and `alpha-gates-v1.json` define the canonical gate inventory; `schemas/alpha-evidence-manifest.schema.json` defines the structural manifest format; the executable finalizer enforces cross-field provenance, gate ownership, claim eligibility and conservative redaction rules.

Authoring or validating this contract does not mark any release gate `PASSED`, enable R8, change the app version, create signing material, generate an SBOM, or claim device compatibility.

## Core rule

An alpha manifest is claim-eligible only when every gate marked `required=true` is `PASSED` on the exact manifest commit and every passed-gate/artifact provenance record belongs to that same commit.

`DEFERRED` is an explicit product decision, not success. A deferred item requires an issue number, rationale and scope effect. A required gate cannot be deferred while `claimEligible=true`.

## Canonical virtual-device policy

Repository-owned persistent Android TV AVD identities are exactly:

- `MuxTV_TV_OLD_API26`;
- `MuxTV_TV_CURRENT_API36`.

The release contract must not introduce `virtual.mainstream` or any third persistent AVD. `virtual.low_ram` is a constrained runtime/device configuration applied to one of the canonical devices where representable; it is not a separate AVD identity. API37 and hardware-specific behavior are ephemeral/physical release evidence under #31.

## Manifest identity

Every manifest records the schema version, repository, exact lowercase 40-character source commit, optional source ref, requested release version, UTC generation timestamp, claim-eligibility flag, canonical gate map, artifact provenance and known limitations.

Paths, workflow IDs/URLs and artifact names are references only. They are never interpreted as proof unless the owning gate is `PASSED` and its `evidenceCommit` equals the manifest commit.

## Gate states

- `PENDING` — not executed or evidence not reviewed;
- `PASSED` — exact-head evidence exists and acceptance checks are satisfied;
- `FAILED` — executed and did not satisfy the gate;
- `BLOCKED` — cannot execute because of an external dependency/environment;
- `DEFERRED` — explicitly removed from current scope with issue+rationale+scope effect.

Never use `PASSED` for static review when a gate requires execution.

## Gate groups

The executable inventory is `alpha-gates-v1.json`. Its groups are:

- scope/truth: dependency scope, version identity, clean source tree;
- release build: release assembly, R8/resource shrinking, Baseline Profile packaging, signing, SBOM and dependency report;
- virtual correctness: API26 old edge, API36 current, and constrained low-RAM mode on a canonical device;
- data/security: Room schema/upgrade, Keystore persistence/reset and redaction;
- recovery: TV-operable recovery, previous-good preservation and user recovery docs;
- player/diagnostics: core TV journey, transport behavior and diagnostic export;
- physical qualification: current Android/Google TV, constrained TV and availability-conditional Fire TV;
- performance: Baseline Profile effect, startup/frame/memory evidence and measurement provenance.

Physical observations remain device-scoped. Emulator evidence cannot certify vendor codec, HDR/Dolby Vision, passthrough, weak-ARM, Fire OS, thermal or absolute performance behavior.

## Canonical gate ownership

Every gate from `alpha-gates-v1.json`, including optional/availability-conditional gates, must be present in the manifest so unavailable work cannot disappear silently. The finalizer rejects unknown gates and missing canonical gates. A manifest cannot weaken a gate whose catalog entry has `requiredByDefault=true` by setting `required=false`.

Optional gates may remain non-required with an explicit non-passed status while `claimEligible=true`; their state and limitations must still be represented truthfully.

## Exact-commit provenance

For every `PASSED` gate:

- `evidenceCommit` is required by schema;
- the finalizer requires `evidenceCommit == manifest.commit`.

For every artifact:

- `sourceCommit` must equal `manifest.commit`.

A claim-eligible manifest must include at least one APK or AAB with a non-null SHA-256 digest and exact source-commit provenance.

## Claim eligibility

`claimEligible=true` is valid only when:

1. schema validation succeeds;
2. manifest gate names exactly match the canonical catalog;
3. no required-by-default gate is weakened;
4. every `required=true` gate is `PASSED`;
5. every passed gate belongs to the manifest commit;
6. every artifact belongs to the manifest commit;
7. at least one APK or AAB has SHA-256 provenance;
8. `security.redaction` is passed as part of the required gate set;
9. known limitations remain explicit rather than hidden through omitted gates.

The finalizer validates eligibility; it does not generate evidence or turn pending gates into passed ones.

## Redaction boundary

Evidence references and artifact names are public-safe metadata only. The finalizer conservatively rejects values shaped like:

- Authorization/Cookie headers;
- credential/signature query parameters such as token/password/signature values;
- URI user-info credentials;
- private-machine absolute filesystem paths.

Diagnostics for rejected metadata must not echo the rejected value. The manifest must never contain playlist/provider credentials, signing secrets, temporary signed download URLs, private keystore paths, Authorization/Cookie material or raw secret-bearing locators.

## Release artifact and performance policy

Release hardening must operate on the minified release-shaped artifact. R8 keep-rule changes need analyzer/runtime evidence rather than broad defensive keep rules. Baseline Profile packaging is a separate gate from measured effect; emulator-generated profiles or correctness runs are not public performance claims. Physical-device before/after evidence remains required for release-facing startup/frame performance claims.

## Qualification boundary

The canonical API26/API36 matrix proves bounded platform correctness. Android 17/API37 Local Network Protection, route-dependent IPv6, vendor codecs/HDR/passthrough and Fire TV behavior require appropriate ephemeral or physical evidence. Lack of such evidence must remain visible in the manifest/known limitations rather than being inferred from emulator success.
