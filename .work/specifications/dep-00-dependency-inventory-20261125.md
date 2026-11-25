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

## DEP-00B3b1 — selected KSP processor and JMH classpaths

DEP-00B3a specialized gate GREEN on dcec8a211e7d7fc2b692e6ec89dd64484a973600, run 45568196173, artifact 15001346264: 345 runtime GAVs, 194 build GAVs, 1 runtime and 10 build matched GAVs, zero scan failures. Counts are not additive across scopes. Runtime match Guava 33.3.1-android GHSA-xxph-c9ww-hj94; build matches include Kotlin Gradle Plugin 2.4.10 GHSA-r937-wjx7-w2jp. Prioritize applicability review and isolated fixes; do not infer exploitability. Official records: https://github.com/advisories/GHSA-xxph-c9ww-hj94 and https://github.com/advisories/GHSA-r937-wjx7-w2jp.

Extend the existing build resolver (no second implementation): discover resolvable ksp*ProcessorClasspath configurations in app:tv and core:database, preserve discovery metadata, resolve only within each owner task, require debug and release main processor graphs with Hilt/AndroidX Hilt and Room anchors respectively. Empty non-main processor graphs are evidence, not silently omitted. Discover names using the KSP processor-classpath naming contract, not a hard-coded variant list. Add benchmark:jvm jmhRuntimeClasspath with JMH core anchor. Preserve previous four build graphs, separate KSP_PROCESSOR and BENCHMARK scope labels, deduplicate by PURL within build scan. No changes to dependency declarations or runtime behavior. Remaining coverage: other plugin/settings classpaths, macrobenchmark, research, npm, wrapper; this is selected evidence, not full inventory.

Acceptance: fixture tests, six runtime graphs, existing four build graphs plus discovered processor and JMH graphs, required compiler/JMH anchors, zero unresolved edges, complete OSV scans and exact-head artifact. Live Gradle configuration discovery requires qualification; do not assume names were verified by fixture tests.

## DEP-00B3b2 — wrapper integrity before Gradle execution

DEP-00B3b1 specialized gate GREEN on 7a7ca05e42c80c61071b8852dddc38e77cc733fe, run 45569849869, artifact 15001138898: 19 tests, six runtime and 15 build graphs, 345 runtime and 261 build GAVs, one runtime and 12 build matched GAVs, zero scan failures.

Official reference https://gradle.org/release-checksums/ lists Gradle 9.5.0 bin ZIP SHA-256 553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746 and wrapper JAR SHA-256 497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7. Current repository JAR bytes were independently fetched and hashed before this change (48,462 bytes, same SHA-256); they also exactly match upstream v9.5.0 Git blob b1b8ef56b44f16b14dc800fa8103a6d89abb526f. No JAR replacement. The launcher scripts differ from upstream in the DEFAULT_JVM_OPTS encoding option; they are not regenerated or authenticated by this slice.

Pin distributionSha256Sum in wrapper properties. Add a fail-closed preflight using an intentionally narrow ASCII exact-property allowlist (reject duplicate/escaped/continued keys, wrong URL/checksum and unexpected properties) and actual JAR hash. Invoke it before Java setup through the existing dual-JDK composite; test positive LF/CRLF and negative fixtures on the Linux inventory workflow and Windows hosted CI contract. All workflows calling the composite inherit this guard; it does not assert universal coverage of arbitrary workflows or protect against a malicious PR modifying both guard and pins. Gradle checks ZIP on fresh download; not a proof of integrity of every previously cached distribution. This is pinning current authentic bytes, not remediation of known Gradle vulnerabilities.

Gradle 9.9.2 release notes https://docs.gradle.org/9.9.2/release-notes.html identify four security fixes, including SFTP host key checks, transitive snapshot verification, TestKit temp directory and daemon registry permissions. 9.5.0 remains the baseline only until the separate compatibility/security probe. Review advisory affected ranges and project-specific applicability; do not label current Gradle secure because its hash matches. DEP-00B4 applicability and DEP-00C toolchain qualification next. No runtime versions changed.
