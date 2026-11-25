# DEP-00A — declared and selected dependency inventory

Refs #179. Baseline main: 47e3b93b5f04cddaa5a3149716a4fcbec9c0bfdc.
Separate from the unmerged #401 stack; do not merge or rebase that stack as part of this work.

## Scope

- Exact source commit and clean tracked inputs, catalog declarations (including BOM-managed aliases), wrapper URL/checksum presence/JAR digest, included build AGP/Kotlin agreement, workflow/composite action references, browser probe dependencies.
- Selected dependency components and edges in TV and phone release runtime, debug unit-test runtime and debug Android-test runtime configurations, kept separate. Unresolved dependencies or absent/empty required graphs fail the diagnostic task.
- Existing hosted dual-JDK and artifact upload/reconciliation contracts. No new AVD, no self-hosted runner, no runtime dependency changes.
- This is dependency resolution metadata, not artifact integrity or installed APK content. Existing TV release CycloneDX evidence remains independently authoritative for its scope.

## Acceptance

The exact-head workflow must execute the collector tests, write both JSON reports, resolve all six configurations with zero unresolved edges, and publish the evidence. A report of declarations alone is not acceptance. Do not interpret a missing checksum or a recorded action SHA as an authenticated artifact. Review graphs for unexpected test/lab dependencies; this slice does not declare them all forbidden or absent.

## Follow-on (not accepted by DEP-00A)

DEP-00B: normalize module IDs to Maven PURLs, check applicability of OSV/GHSA/advisory records to selected versions and production/build/test scopes; include plugin classpaths, included-build and benchmark graphs plus npm lockfile. Unknown or unavailable advisory data is not a clean scan. Distinguish vulnerable version from reachable code. Verify official wrapper distribution checksum and JAR provenance separately.

DEP-00C: verify official supported Gradle/AGP/KGP/KSP matrix and actual stable Maven metadata; prioritize applicable security fixes. Probe candidate tuple before changing main, using existing owner issues. No blanket version bump or automatic merge.

DEP-01+: isolated compatible toolchain, storage, UI, network and crypto owners. Crypto updates require original QR/F6/EOF/APPDATA negative and positive tests on canonical API26/API36, security review, and orderly #401 integration. No claim of physical device compatibility.

The collector uses Python standard library only (3.11+ for tomllib). The diagnostic Gradle invocation disables configuration cache because it reads resolution results at execution time; production configuration-cache settings remain unchanged. No logs or user IPTV credentials are collected by the inventory. Do not add repository secrets or URLs to its reports.
