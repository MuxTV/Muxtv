# DEP-01c2 — AGP 9.5.1 security/compatibility diagnostic

Refs #457, #458, #456, #455, #454, #452, #179. 2026-11-25.
Comparison parent: 13d0f568ff947bfdc5228ebdbe06d978c6bc85b7 (#456, Gradle 9.9.2).
Independent sibling of #458, NOT a descendant of the 9.3.1 candidate. Preserve #458 as diagnostic evidence; do not require its merge. No product source changes and no new test infrastructure.

## DEP-01c1 result: NO_SECURITY_DELTA

Exact #458 source a8568d0e332b114a6ea3c69da5594dd7b0f05727.
Inventory 45622157297 job 139232855865 artifact 15025112397: six runtime/15 build graphs, 345/261 Maven GAVs, 17 advisory IDs/21 match rows; exact logged PURL/ID pairs unchanged from #456. No scan failures. This is not full graph equality nor proof of absence of vulnerabilities. KSP run 45622157327 job 139232855659 artifact 15027895382: all four phases passed, warm/edited/restored configuration cache reused, Room schema unchanged, source restored. Results from logs, artifacts not independently unpacked.

Hosted validation 45622157158 and R8 45622157419 stopped at :convention:test, 56 tests/1 failure, R8AnalyzerEvidenceContractTest.kt:15. Exact source confirms hardcoded agp = "9.3.0" in this separate test, missed by earlier pin update. Not a demonstrated R8 execution regression. Remaining two #458 workflows pending at decision; 12/16 green, 2 RED. No justification to accept 9.3.1 as security remediation. Do not alter its head solely to rerun diagnostics; retain recorded failure and mark superseded for this security objective.

## Candidate rationale and boundaries

Official Android Developers AGP 9.5 release notes (updated 2026-11-05) list 9.5.1, min Gradle 9.6.0, max API37, JDK17. The 9.5 line includes BC BOM alignment #549199023, new optimization DSL isMinifyEnabled #556343682, Configuration.setVisible deprecations #565740572/#560282299, JDK25 release R-file warning #553121473, L8 mapping preservation #532206373. No publicly marked fixes specific to 9.5.1. Relevant improvements do not establish fixed Maven coordinates; confirm actual resolution. Source https://developer.android.com/build/releases/agp-9-5-0-release-notes .

KGP 2.4.21 full support ends at Gradle 9.7.0 and AGP 9.3.1. Both Gradle 9.9.2 and AGP 9.5.1 are outside this range. JetBrains permits newer releases with possible warnings/features not working; this is a bounded diagnostic, not vendor-certified or accepted. Source https://kotlinlang.org/docs/gradle-configure-project.html . Keep KGP/KSP, JDK25/17, compile/target SDK37/min26, all product libraries and DSL unchanged. No experimental backup/restore suite, new optimization switches, forced transitives or global BOM overrides.

#456 currently 15/16 green, C364 in progress; #454 15/16 green, C364 attempt 2 in progress. Specialized parent inventory and four-phase KSP are green. Proceed with isolated diagnostic only, not permission to integrate. If reproducible parent product failure occurs reconcile it first.

## Exact narrow changes

AGP 9.3.0 -> 9.5.1 in gradle/libs.versions.toml and included convention build. Update BOTH explicit AGP pins in ConventionFilesTest and separate R8AnalyzerEvidenceContractTest literal to the same reviewed value. Preserve every other test and runtime evidence assertion. R8 task and expected report path/hash contract unchanged. Inspected R8 script, release SBOM/provenance scripts and corresponding release test contracts: no old AGP pin found in those inspected files other than R8 test. Broader bulk inspection hit connector call limit; no claim of exhaustive scan. Existing full suite detects further stale literals. No source tree changes beyond these four files and this decision record.

## Acceptance and comparison

- Require selected AGP 9.5.1 resolution in exact-head build inventory and COMPLETE runtime/build OSV scans plus details. Compare PURL/advisory pairs, scope and paths, particularly AGP-owned Commons Lang3, jose4j, BC (including alignment of provider/PKIX/util), JDOM2, shared Guava. No presumption of remediation from BOM alignment. Jackson/CycloneDX and runtime Guava separate owners.
- Existing hosted validation/convention tests, four-phase Room/Hilt regeneration and unchanged schemas, R8 analyzer and optimized release, APK/AAB provenance, release SBOM, TV/phone canonical API26/API36, local-control crypto/QR/native proof, M0/C364/benchmark trace, lint. No new AVD or physical-device claims. Compare actual warnings and release artifacts to #456; no speed claim from CI.
- If no meaningful security delta, record NO_SECURITY_DELTA, compare concrete compatibility benefits and residual risks; do not endlessly bump AGP. If new RED, classify exact failure before further version change. If new matches, investigate rather than suppress.
- Even full CI GREEN requires explicit review of both outside-full-support boundaries and security/compatibility tradeoff. #452/#454/#456 must be independently reviewed and accepted before any merge, final narrow diff restacked. #458 is comparison-only, not a merge prerequisite. #401/#451 and main unchanged.
