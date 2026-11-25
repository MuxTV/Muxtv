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

## RED and correction (2026-11-25)

Run 45563753498 on 9e803bf88a2fc9985a366e4a44de5e03bb0bddd6 passed declared inventory and publication but failed resolution: Gradle 9.5 rejected cross-project configuration access without the owning project's exclusive lock. The replacement registers one task on each application project, writes separate UTF-8 reports, and validates all six graphs in a standalone Python step before writing the combined report. No internal locking API or parallel-build workaround. Negative tests cover missing, stale, duplicate, unresolved and malformed graph evidence. A partial artifact is diagnostic only; the workflow must succeed before DEP-00B consumes it.

## DEP-00B1 — selected Maven package normalization

DEP-00A specialized workflow 45564580461 passed on 6b182e8a859a7585cee79482984358a00e934575: six graphs, zero unresolved edges, evidence artifact 14999836154. Overall PR acceptance remains separate.

The resolver now exports the actual Gradle root component ID rather than guessing from its display name. The validator requires that root to be a non-Maven component in each graph. The normalizer revalidates both owner reports and compares the combined graph before producing maven-packages.json, SHA-256-bound to the exact input bytes. Identity is the Maven GAV PURL per https://packageurl.org/ (ECMA-427); no classifier or packaging is inferred. Preserve case and encode version delimiters. Deduplicate across graphs, retain all project/configuration memberships, record app-root non-constraint and constraint edges separately, and report multiple selected versions across configurations without labeling them conflicts. Non-Maven nodes are counted, not scanned as Maven. All packages are NOT_SCANNED. This is selected metadata, not an installed-artifact SBOM or reachability evidence.

Acceptance: existing six-graph gate, fixture tests, successful normalization and published exact-HEAD artifact. DEP-00B2 advisory queries and expanded build/plugin coverage remain separate; no runtime or dependency version changes.

## DEP-00B2 — bounded OSV version-match scan

DEP-00B1 specialized gate passed on da21d1f0f9d60d33e5591ac7a018b0ad43c5e3b9 (run 45565661188, artifact 15000057260). Overall PR acceptance remains separate.

Use official https://google.github.io/osv.dev/post-v1-querybatch/ with versioned Maven PURLs only (no second version field). The scanner reconstructs the normalized inventory from validated graph inputs, binds findings to source SHA and both evidence hashes, and transmits only public package coordinates. The response is positional; require exact result cardinality. Follow each query's next_page_token independently, even on empty pages, and reject repeated tokens. Batches <=50, <=12 pages per batch, <=3 attempts on transient errors, 30-second socket timeout, 8 MiB response cap, 5000 package cap, 600-second scheduling budget and 12-minute CI step limit. No redirects or new packages. The scheduling budget is not a hard socket deadline; the workflow step timeout is the outer bound.

A complete query with no matches is NO_KNOWN_ADVISORIES; matching IDs are ADVISORY_FOUND; incomplete queries are SCAN_FAILED or NOT_SCANNED. Partial matches remain diagnostic; incomplete scan fails the workflow but uploads evidence when possible. Finding IDs does not fail the gate solely because vulnerabilities exist. Record ID and modified timestamp; distinct IDs are not unique CVEs. Detail retrieval, alias reconciliation, fix versions, severity, applicability and expanded build/plugin/npm coverage are follow-ons. No version changes, no release security certification, no physical hardware claims.

Acceptance: fixture tests, all six graph gates, normalized inventory revalidation, complete live OSV query coverage and published exact-source security-findings.json; review findings separately.

## DEP-00B3a — selected build classpaths

DEP-00B2 specialized gate GREEN on 466df148c375bf97bea5251e3310f05aea59da23 (run 45566030871): 345 Maven GAVs scanned, 344 without OSV matches, one matched package and one distinct advisory ID, zero failures. Artifact 15000610312. The advisory identity/applicability was not established from the summary log. New logs expose only public PURL/ID and release-graph membership for review.

Collect four separate selected build graphs: root and TV buildscript classpaths, convention runtime and test runtime in an explicit included-build invocation. Gradle documents buildscript classpath reporting via buildEnvironment; use the same configuration's public resolution result and owning-project task rather than parsing console trees. Require exact report names, source/Gradle version, complete graph keys, valid edges, nonempty Maven components and the declared AGP/KGP anchors in convention graphs. Normalize and scan with the existing bounded OSV client. Build findings remain separate from app findings; join on exact PURL when triaging. The BUILD_TEST scope is graph membership, not proof that an artifact is test-only globally. No duplicate CVE-count claims.

Acceptance: fixture tests, prior runtime scan, all four selected build graphs with zero unresolved edges, complete separate OSV scan, exact-head artifact. Remaining DEP-00B3b: KSP processor classpaths, remaining plugin/settings and benchmark/research scopes, npm lockfile and wrapper authentication. No claim of complete build coverage, no versions changed, no new runtime dependency.
