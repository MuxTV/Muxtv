# DEP-01d — CycloneDX 3.6.1 / Jackson security probe

Refs #31 (release SBOM owner), #179, #457/#459, #455/#456, #453/#454, #452. Date 2026-11-25.
Comparison parent #459 exact 929c4723ad9a390b05602d3b82bcaa361e390f56. Diagnostic stacked draft, not merge approved. Preserve parents and all existing gates. #458 is superseded comparison only.

## Parent evidence

AGP 9.5.1 inventory 45625578224 job 139244748365 artifact 15027463375 COMPLETE: 345 runtime / 261 build GAVs, six runtime / 15 build graphs, 14 IDs / 18 match rows (17/21 prior), no scan failures. BC bcpkix and bcprov selected 1.80.2 instead of 1.79. GHSA-574f-3g2m-x479, GHSA-c3fc-8qff-9hwx, GHSA-p93r-85wp-75v3 no longer match; GHSA-wg6q-6289-32hp, GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26 remain. Commons Lang3, jose4j, JDOM2, Guava and Jackson unchanged in matched logs. No full graph equality or exploitability assertion. KSP 45625577806 all four phases PASS, CC reused, schemas unchanged and sources restored. R8 and hosted validation now GREEN after strict R8 pin correction. TV/phone, provenance, SBOM, lint, M0 and other gates GREEN. Only C364 45625577617 canonical API36 failed during emulator-runner animation settings (ADB exit 255), before test script; correctness and both smoke jobs GREEN. Failed-job retry accepted. Parent #459 15/16 first-attempt PASS; #454 and #456 each 15/16 PASS with C364 in progress. No parent acceptance or merge claim. Gradle/AGP remain outside KGP fully supported bounds; explicit review required.

Parent direct release SBOM 45625577590: 255 components, 256 dependency nodes, one POM metadata warning androidx.media3:media3-ui-compose:1.11.1 with component present; SHA256 ecedaacdb68d8632ecee90bce90868918369522609a2c893123349c7994b71fe, artifact 15027549597. These are comparison observations, not schema proof or complete equivalence to inventory (different graph semantics). Artifacts not independently unpacked.

## Upstream verification and candidate

CycloneDX Gradle plugin 3.6.1 stable release published 2026-11-13, https://github.com/CycloneDX/cyclonedx-gradle-plugin/releases/tag/cyclonedx-gradle-plugin-3.6.1 : fix #961 declares resolved dependency graph as task input to avoid stale Direct SBOM when dependency versions, relationships or test configuration membership change; #966 upgrades Core to 13.4.0; #967 covers newer Gradle releases; upstream wrapper 9.9.0. Core exact source tag cyclonedx-core-java-13.4.0 pom.xml imports com.fasterxml.jackson:jackson-bom:2.22.3 and depends on jackson-dataformat-xml, verified through upstream file fetch: https://github.com/CycloneDX/cyclonedx-core-java/blob/cyclonedx-core-java-13.4.0/pom.xml . This is upstream declared dependency evidence, not the actual selected MuxTV classpath.

Previously selected jackson-core 2.22.1: GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 (fixed 2.22.3 on 2.22 line). jackson-databind 2.22.1: GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54 (2.22.3), GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf (2.22.2). Candidate 2.22.3 meets reported boundaries. Prefer upstream owner upgrade rather than manually forcing Jackson modules. No claim of removal until exact-head scans.

## Changes

Only cyclonedx catalog 3.4.1 -> 3.6.1, corresponding explicit release evidence pluginVersion and strict ReleaseSbomEvidenceContractTest pin; assert script metadata version matches reviewed pin. Preserve app TV cyclonedxDirectBom config, CycloneDX spec 1.6, releaseRuntimeClasspath only, testConfigs empty, no build environment/system, no aggregate, POM-warning membership and forbidden test/benchmark dependency checks, exact SHA and hash. No runtime declarations, Gradle/AGP/KGP/KSP, schema, product code or new workflows. Existing metadata pluginVersion remains a reviewed declared pin, not independent proof of resolved plugin; confirm actual selection in inventory.

## Acceptance

- Exact-head inventory COMPLETE, actual selected CycloneDX plugin/Core/Jackson versions and owner paths reviewed, seven prior Jackson IDs absent, no unexpected new matches; preserve other matches accurately. Expected 14 -> 7 distinct IDs and 18 -> 11 match rows if only these seven disappear; not guaranteed. Runtime Guava remains separate next slice, residual AGP transitives separate applicability/owner review.
- Direct release SBOM generated and nonempty, schema 1.6/application/component identity, graph/exclusion and POM warning checks pass, digest and exact source metadata. Compare component/node counts and warning membership to parent; do not mistake counts for graph equality. Fresh generation is tested, upstream stale-output fix not independently regression-tested by MuxTV.
- Existing hosted gates including KSP four phases, Room/C364/M0, R8, APK/AAB provenance, canonical TV/phone, crypto/QR; no physical device or performance claim. If SBOM API or selected version mismatch, isolate observed RED rather than loosen contracts.
- Parent #452/#454/#456/#459 review and acceptance and final narrow diff restack required. No merge before full exact-head evidence and explicit compatibility tradeoff. #401/#451 unchanged.
