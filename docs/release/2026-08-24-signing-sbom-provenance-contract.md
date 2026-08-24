# Alpha signing, SBOM and artifact provenance contract — 2026-08-24

Status: runner-free release design. No signing secret, build plugin or release workflow is introduced by this document.

Reviewed accepted baseline: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Owner: #31 release hardening.

## Current repository facts

At the reviewed baseline:

- application ID is `app.muxtv.tv`;
- version is `0.1.0-alpha.1` / versionCode `1001`;
- release optimization is enabled;
- Baseline Profile plugin/integration exists;
- application keep rules are intentionally narrow/empty-by-default;
- no explicit repository-owned release `signingConfig` is present in `app/tv/build.gradle.kts`;
- no dedicated SBOM plugin is declared in the root build plugin set;
- no dedicated alpha signing/publication workflow is part of current main.

The last three points mean **NOT IMPLEMENTED IN REPOSITORY**, not “no external process exists”. Do not infer the state of developer machines or external secret stores from source alone.

---

## Release provenance goals

For an alpha artifact, a reviewer should be able to answer without access to signing secrets:

1. Which exact Git commit produced this artifact?
2. Which package/version/toolchain produced it?
3. Which signing certificate identifies the distributed artifact?
4. What is the cryptographic digest of the final APK/AAB?
5. Which dependency/SBOM snapshot belongs to it?
6. Which R8/Baseline Profile/device evidence belongs to the same candidate?
7. Which compatibility claims are justified by that evidence?

The provenance record is metadata about a release. It is **not** a place to store credentials, keystores, provider URLs or user data.

---

## Signing secret boundary

### Never commit

- keystore bytes;
- private key;
- keystore password;
- key password;
- secret-manager token;
- base64-encoded keystore;
- local absolute secret path that exposes user/environment identity;
- signing command output containing secrets.

### Allowed repository configuration

Repository code may define only secret-free wiring such as environment-variable names, externally injected file handles/paths and explicit fail-closed validation.

Illustrative input names, not an implementation requirement:

```text
MUXTV_RELEASE_KEYSTORE_FILE
MUXTV_RELEASE_KEYSTORE_PASSWORD
MUXTV_RELEASE_KEY_ALIAS
MUXTV_RELEASE_KEY_PASSWORD
```

The actual secret source can be a local protected store or future CI secret store. The repository contract must not depend on a particular vendor secret manager unless #31 explicitly adopts one.

### Fail closed

A release-signed build must fail before packaging/publication when required signing material is absent or inconsistent. It must not silently:

- fall back to debug signing;
- generate a new release key;
- use an arbitrary developer keystore;
- publish an unsigned artifact under a signed-artifact name.

Debug/local development remains separate and may continue to use ordinary debug signing.

---

## Certificate identity

The alpha provenance record should store only public certificate identity, for example:

- certificate SHA-256 fingerprint;
- certificate subject/issuer only if useful and stable;
- signing scheme/tool output version if needed for verification.

Never store private key material.

### Continuity rule

Once an alpha distribution key is intentionally accepted, a different certificate fingerprint is a release-signing identity change and must be explicit. Do not treat it as an ordinary rebuild difference.

---

## Artifact digest contract

Every distributed artifact gets a SHA-256 digest after final signing/alignment/packaging.

Minimum record:

```text
artifact_file
artifact_kind = APK | AAB
sha256
byte_size
package_id
version_name
version_code
source_commit
signing_certificate_sha256
```

If both APK and AAB are produced, record them as separate artifacts; do not assume their digests or install semantics are interchangeable.

Evidence ZIPs/reports may also have digests so the release manifest can bind them without embedding every report inline.

---

## Reproducibility terminology

Do not claim “reproducible build” merely because two builds compile from the same commit.

Separate three concepts:

### Source/toolchain reproducibility

The release manifest records enough non-secret inputs to recreate the build environment:

- source SHA;
- Gradle version;
- AGP version;
- Kotlin/KSP versions;
- JDK major/vendor if it affects accepted build policy;
- compile/target/min SDK;
- resolved dependency snapshot or SBOM;
- relevant build flags.

### Unsigned/intermediate byte reproducibility

If later desired, compare an appropriate unsigned/intermediate artifact before signing and document unavoidable timestamp/metadata sources rather than assuming bit identity.

### Signed distribution provenance

The primary alpha requirement is stronger traceability: the final signed artifact has an exact digest and certificate fingerprint bound to the exact source/toolchain/evidence record.

Bit-for-bit reproducibility of the final signed artifact is a separate claim and is not required by this document unless #31 promotes it.

---

## SBOM contract

### Purpose

The SBOM provides a machine-readable dependency inventory for the accepted release candidate. It does not replace license review, vulnerability review or source provenance.

### Format

At implementation time prefer an established machine-readable format such as CycloneDX or SPDX rather than inventing a MuxTV-specific dependency JSON schema.

Do not add an SBOM Gradle plugin until the selected tool is checked against the current AGP/Gradle/Kotlin stack and its output is validated for the actual Android dependency graph.

### Minimum SBOM scope

Include, where resolvable:

- application/module identity;
- Maven/Gradle coordinates and versions of runtime-distributed dependencies;
- AndroidX/Jetpack libraries;
- Kotlin runtime components that ship;
- Media3 components;
- Room/runtime components;
- OkHttp/runtime networking components;
- any native `.so`/AAR native payload introduced later;
- license identifiers/metadata where the generator can resolve them reliably;
- package/component hashes where available and meaningful.

### Distinguish tooling from shipped runtime

Build/test-only components such as benchmark/JMH/test runners should not be presented as if they are shipped runtime libraries. If the chosen SBOM generator includes build/tooling scope, label scopes explicitly.

Pre-release tooling dependencies such as the Benchmark 1.5 line should be visible in the build provenance even if not shipped in the production APK.

### Secret/data exclusion

SBOM generation must never include:

- repository/local credentials;
- IPTV provider/source URLs;
- Authorization/Cookie headers;
- signed/tokenized locators;
- user profile data;
- keystore path/password/private key.

---

## Release manifest v1

A repository- or evidence-owned release manifest should be generated for the accepted candidate. Exact serialization can be chosen during implementation, but fields should be stable and machine-readable.

Recommended logical schema:

```text
schema_version
release_id
source:
  repository
  commit
  branch_or_tag
application:
  package_id
  version_name
  version_code
android:
  min_sdk
  target_sdk
  compile_sdk
toolchain:
  jdk
  gradle
  agp
  kotlin
  ksp
artifacts[]:
  kind
  file_name
  sha256
  byte_size
  signing_certificate_sha256
sbom:
  format
  file_name
  sha256
r8:
  analyzer_report_file
  analyzer_report_sha256
baseline_profile:
  packaged
  evidence_file
  evidence_sha256
validation:
  host_evidence_sha256
  api26_evidence_sha256
  api36_evidence_sha256
  physical_evidence_refs[]
support_claims[]:
  capability
  evidence_class
  evidence_ref
known_limitations[]
```

Do not put timestamps into identity/digest fields unless they are deliberately part of release metadata. A wall-clock build time may be recorded as informational provenance, but exact source SHA remains the authority.

---

## Evidence-chain rule

The accepted release should form one directed evidence chain:

```text
source SHA
   |
   +--> resolved toolchain/dependencies --> SBOM digest
   |
   +--> release/R8 build -------------> artifact digest
   |                                      + signing cert fingerprint
   |
   +--> Baseline Profile/Macrobenchmark evidence
   |
   +--> API26/API36 correctness evidence
   |
   +--> physical-device evidence
   |
   `--> release manifest --> support-claim classes
```

A result from a different source SHA cannot silently certify the release candidate. If evidence is intentionally reusable across a byte-identical/non-runtime change, that reuse must be an explicit policy, not an assumption.

---

## R8 relationship

Before adding broad keep rules:

1. build the exact release candidate;
2. run the AGP R8 Configuration Analyzer owned by #31;
3. archive/digest the analyzer report;
4. identify the concrete reflection/serialization/native boundary that requires a keep rule;
5. use the narrowest rule possible;
6. rerun release/runtime evidence.

Current empty-by-default application keep policy is a strength. Do not preemptively add broad `-keep class **` style rules to make release builds “safe”.

---

## Baseline Profile relationship

The release manifest should distinguish:

- Baseline Profile plugin is configured;
- a profile was generated for the accepted candidate;
- the profile is actually packaged in the distributed artifact;
- representative CUJs were measured with it.

Current screen-reachability coverage alone is not enough for the final #31 performance claim; Channels/Search/Guide/Player/seek CUJs remain future executable work.

---

## Installation/upgrade provenance

Before alpha qualification, evidence should cover at least:

- clean install of the signed candidate;
- upgrade from the previous supported alpha when one exists;
- package/certificate continuity;
- Room migration chain on supported upgrade path;
- credential/Keystore behavior on upgrade/reset as owned by current security contracts;
- uninstall/reinstall expectations;
- rollback/recovery notes where supported.

An APK signed with a different certificate is not a valid in-place upgrade path unless Android/package policy explicitly allows the adopted signing transition mechanism.

---

## Implementation slices after runner availability

Keep the release work independently reviewable.

### R1 — release metadata/provenance generator

- no secrets;
- exact SHA/version/toolchain/artifact digest;
- deterministic schema validation.

### R2 — signing configuration boundary

- external secret injection;
- fail closed;
- certificate fingerprint evidence;
- no publication yet if release runtime evidence is not ready.

### R3 — SBOM generator

- selected standard format;
- runtime/build scopes reviewed;
- output digest bound into release manifest.

### R4 — R8 analyzer evidence

- run exact release variant;
- archive report;
- any keep-rule change separate if meaningful.

### R5 — release acceptance/publication

Only after #31 device/security/migration/known-limitations evidence is complete.

Do not combine all five into one PR solely because they are “release tasks”.

---

## Alpha support-claim binding

Each public capability statement must reference the evidence class defined by #31:

- `VERIFIED_VIRTUAL`;
- `VERIFIED_PHYSICAL_DEVICE`;
- `LIMITED_EVIDENCE`;
- `UNVERIFIED`;
- `KNOWN_UNSUPPORTED`.

For example, API36 emulator success may be bound to Android platform/UI correctness but cannot be promoted to HDR/Dolby Vision/vendor codec or weak-TV performance support.

## Stop condition while runner/Actions are unavailable

Do not yet:

- add signing configuration to Gradle;
- create/upload a keystore;
- add an SBOM plugin;
- create a release workflow;
- run R8 analyzer through Actions;
- claim reproducible or release-ready artifacts.

The contract is ready; implementation starts with executable build evidence and secret-safe RED/fail-closed tests when the host runner is available.