# DEP-01c1 — bounded AGP patch candidate and advisory delta

Refs #457, #179, #453, #454, #455, #456, #452. Date 2026-11-25.
Comparison parent: 13d0f568ff947bfdc5228ebdbe06d978c6bc85b7. Diagnostic stacked draft only; keep parent branches and runs unchanged. Prior Kotlin/KSP #454: 15/16 workflows GREEN, C364 attempt 2 running. Gradle candidate #456: 11/16 GREEN, remaining 5 in progress at decision; specialized gates green. Full parent acceptance and independent review remain mandatory before merge.

## Exact comparison evidence

Gradle 9.9.2 inventory run 45620313843, job 139226381259, artifact 15023982692: actual Gradle 9.9.2, wrapper LF/CRLF and nine negative fixtures passed, 23 Python tests, six runtime and 15 build graphs, 345/261 selected GAVs, one runtime and 11 build matched GAVs, 17 distinct advisory IDs / 21 match rows. All previous match coordinates/IDs retained; no new matched row in logs. This is not a full graph equality assertion. KSP run 45620313441, job 139226379662, artifact 15024393164: four fixture tests, all four build phases, warm/edited/restored configuration cache reuse, unchanged Room schemas, source restored. R8, phone matrix, SBOM and M0 also passed. Artifacts identified from logs, not independently unpacked.

## Candidate choice

KGP 2.4.21 fully supported Gradle range 7.6.3–9.7.0 and AGP 8.5.2–9.3.1. Gradle 9.9.2 remains an explicit outside-range diagnostic, not vendor-certified. AGP 9.3.1 is the minimal patch and maximum fully supported AGP for this Kotlin line. Android Developers lists no publicly marked fixes for 9.3.1; no security benefit presumed. Current AGP 9.3 minimum Gradle 9.5, max API37. Latest documented stable patch in AGP 9.5 line is 9.5.1; 9.5 requires minimum Gradle 9.6 and max API37. The 9.5 release notes document Bouncy Castle BOM (#549199023), optimization DSL isMinifyEnabled (#556343682), Gradle Configuration.setVisible deprecations (#565740572, #560282299), JDK25 R-file warning (#553121473), L8 mapping preservation (#532206373). These are concrete reasons to evaluate 9.5.1 if 9.3.1 does not help, not proof of patched transitive versions. Direct upstream POM fetch unavailable in this research interface; live selected graphs are required.

Sources:
- https://kotlinlang.org/docs/gradle-configure-project.html
- https://developer.android.com/build/releases/agp-9-3-0-release-notes
- https://developer.android.com/build/releases/agp-9-5-0-release-notes

## Baseline AGP-owned matches

| Package | Selected version | Advisory IDs | Reported fixed boundary (not selected candidate) |
|---|---|---|---|
| commons-lang3 | 3.16.0 | GHSA-j288-q9x7-2f5v | 3.18.0 |
| jose4j | 0.9.5 | GHSA-3677-xxcr-wjqv | 0.9.6 |
| bcpkix-jdk18on | 1.79 | GHSA-wg6q-6289-32hp | 1.84 |
| bcprov-jdk18on | 1.79 | GHSA-574f-3g2m-x479, GHSA-9pwp-9qqc-pr26, GHSA-c3fc-8qff-9hwx, GHSA-p93r-85wp-75v3, GHSA-qp49-qgx5-5m26 | up to 1.85 for specific ranges |
| jdom2 | 2.0.6 | GHSA-2363-cqg2-863c | 2.0.6.1 |
| guava (shared owner) | 33.4.0-jre | GHSA-xxph-c9ww-hj94 | 33.7.2 |

Nine AGP-specific IDs plus shared Guava match. Jackson/CycloneDX and application Guava remain separate owners. No force constraints or blanket overrides. Preserve package scope and variant; review actual dependency/constraint paths. A BOM alignment does not prove security remediation.

## Changes and acceptance

Change AGP only: version catalog, included convention build dependency, both strict ConventionFilesTest pins. Leave Gradle/KGP/KSP/SDK/JDK, runtime library declarations, application source and optimization DSL unchanged. Existing synthetic graph test fixture versions remain historical test data, not live pins. No new workflows or diagnostic framework.

1. Inventory must resolve exact 9.3.1 in selected build graphs, complete both OSV scans/detail review. Compare matched PURL/ID pairs, affected scopes and actual paths against #456; don't accept aggregate counts alone.
2. Full existing hosted suite, four-phase KSP and schema, R8 optimized release and analyzer, APK/AAB provenance and SBOM, lint, M0/C364, TV/phone API26/API36 and local-control regressions. No physical-device or performance claim.
3. If 9.3.1 has no meaningful benefit, record NO_SECURITY_DELTA (not a security success) and decide whether to retain or discard the patch; next separate diagnostic candidate 9.5.1 only after reviewing risk. If incompatible, stop and isolate the first reproducible RED. No indefinite infrastructure expansion.
4. Do not merge until #452/#454/#456 reviewed and accepted, explicit Gradle outside-range decision recorded and final narrow diff reviewed/restacked. #401 and product source unchanged.
