# DEP-00B4b / DEP-00C — security triage and staged toolchain decision

Refs #179, #453, #452, #146, #199, #31, #195.
Reviewed 2026-11-25. Immutable diagnostic baseline: 27129138c842141848d18d67415bc5de3c6d5bd8.
All 11 workflows passed; specialized run 45577253946, artifact 15005011867, SHA-256 37786f9f221e2e3483747fea6984d0bbb6fcae47dd9e31725634a53660deb7aa. 23 Python tests, 6 runtime and 15 build graphs, 345/261 selected GAVs, 18 distinct advisory IDs and 22 match rows. Source: CI log, not independently unpacked ZIP. These are version matches, not 18 confirmed exploitable vulnerabilities.

## Owner-based triage (all 18 advisory IDs)

Reported fixed boundaries are not a guarantee that a candidate Maven variant is available or safe; validate actual resolved graph and advisory rescan. Paths below are observed shortest paths, not exhaustive parents or execution paths. No automatic suppression, blanket force or cross-classloader override.

| IDs | Baseline and observed owners | Reported fixed boundaries | Decision / applicability gate |
|---|---|---|---|
| GHSA-xxph-c9ww-hj94 | Guava 33.3.1-android via TV Media3 UI -> media3-common; phone camera-view -> camera-video; Truth in test graphs. Build 33.2.1-android via Truth/JMH; 33.2.1-jre via Room compiler-processing; 33.4.0-jre via AGP/databinding; 33.6.0-jre via CycloneDX/Maven and Hilt compiler. | 33.7.2 | P1 release candidate separately: verify Android artifact/variant, inspect Java serialization entry points and APK contents; validate release, test and KSP scopes independently. Never substitute jre variant in APK. Keep Media3/CameraX behavior. |
| GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89 | jackson-core 2.22.1 via CycloneDX 3.4.1 -> core-java 13.1.0 -> jackson-dataformat-xml | 2.18.11 / 2.21.7 / 2.22.3 | P1 #31: isolated CycloneDX upgrade candidate; verify matched coordinates disappear and SBOM/provenance contract remains correct. Input parsing exposure must be reviewed. |
| GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54 | jackson-databind 2.22.1 same observed owner | 2.18.11 / 2.21.7 / 2.22.3 | Same CycloneDX owner, keep Jackson family aligned. |
| GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf | jackson-databind 2.22.1 same owner | 2.18.10 / 2.21.6 / 2.22.2 | Same CycloneDX candidate; all seven Jackson IDs must be rechecked. |
| GHSA-j288-q9x7-2f5v | commons-lang3 3.16.0 via AGP -> repository -> commons-compress | 3.18.0 | AGP owner; review untrusted input handling; inspect candidate graph before considering local constraints. |
| GHSA-3677-xxcr-wjqv | jose4j 0.9.5 via AGP -> bundletool | 0.9.6 | AGP owner; inspect token-processing exposure and bundletool behavior. |
| GHSA-wg6q-6289-32hp | bcpkix-jdk18on 1.79 via AGP -> builder | 1.84 | AGP owner; qualify signing/verification and release provenance. |
| GHSA-574f-3g2m-x479, GHSA-c3fc-8qff-9hwx | bcprov-jdk18on 1.79 via AGP -> builder | 1.80.2 / 1.81.1 / 1.84 | AGP owner; align BC family, no app crypto change. |
| GHSA-p93r-85wp-75v3 | bcprov-jdk18on 1.79 same owner | 1.80.2 / 1.84 | Same AGP candidate. |
| GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26 | bcprov-jdk18on 1.79 same owner | 1.85 | Same AGP candidate, verify actual BC family including bcutil. |
| GHSA-2363-cqg2-863c | jdom2 2.0.6 via AGP -> jetifier-processor | 2.0.6.1 | AGP owner; check actual Jetifier/XML parsing use (android.enableJetifier absent in gradle.properties). No claim that absence eliminates exposure. |
| GHSA-r937-wjx7-w2jp | kotlin-gradle-plugin 2.4.10 direct convention and root plugin marker | 2.4.20-Beta1 | P0 #453: Kotlin 2.4.21 / KSP 2.3.14 candidate; verify matched KGP version disappears, build cache/incremental correctness. |

Every row remains APPLICABILITY_REVIEW_REQUIRED until actual call/input or execution analysis supports a stronger determination. A fixed boundary is not a remediation result. No production release approval from this triage. Gradle distribution vulnerabilities require separate assessment from Maven.

## Official compatibility (2026-11-25)

- Kotlin 2.4.20–2.4.21 fully supported: Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1. Later versions may work but are outside fully supported bounds. Baseline 2.4.10 itself lists AGP max 9.1.0 even though observed 9.3.0 baseline passed. Source https://kotlinlang.org/docs/gradle-configure-project.html .
- AGP 9.3 uses minimum Gradle 9.5.0; AGP 9.5 uses minimum 9.6.0, JDK 17, max API 37. Source https://developer.android.com/build/releases/agp-9-3-0-release-notes and https://developer.android.com/build/releases/agp-9-5-0-release-notes . A vendor minimum is not a full cross-product certification.
- Gradle supports running on Java 25 from 9.1.0; keep runtime JDK 25 and Java toolchain 17. https://docs.gradle.org/current/userguide/compatibility.html .
- Kotlin Compose and serialization plugin versions follow Kotlin catalog. AGP 9 built-in Kotlin remains unchanged. https://kotlinlang.org/docs/compose-compiler-migration-guide.html .
- KSP 2.3.14 release 2026-11-13 fixes the concurrent library-symbol disappearance regression introduced by 2.3.13 (#3286) and includes no-dirty-file incremental processing changes. https://github.com/google/ksp/releases/tag/2.3.14 and https://github.com/google/ksp/issues/3286 . Processor compatibility requires live Room/Hilt tests; no blanket KSP compatibility guarantee inferred.
- Gradle 9.9.2 security candidate ZIP SHA-256 971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b, source https://gradle.org/releases/ and https://docs.gradle.org/9.9.2/release-notes.html . Do not update wrapper JAR until independently verified official bytes/hash and candidate preflight pins. 9.9.2 exceeds KGP 2.4.21 fully supported Gradle max. AGP 9.5.x exceeds its fully supported AGP max. The earlier combined newest-version proposal is therefore NOT an officially supported tuple.

## Execution order

A: This candidate changes only Kotlin 2.4.10 -> 2.4.21 (catalog and convention) and KSP 2.3.10 -> 2.3.14. Keep Gradle 9.5.0 and AGP 9.3.0, SDK/JDK, all runtime library declarations unchanged. This candidate is within the documented KGP bounds, not qualified until resolved and tested.

B: After A has evidence, independent Gradle 9.9.2 security probe with explicit out-of-fully-supported-range decision; use verified wrapper distribution and JAR pins and isolate from AGP updates. Do not merge an unsupported tuple merely because it resolves. Evaluate current advisory applicability and supported alternatives, or document a narrow exception with observed benefit and all gates.

C: AGP 9.3.1 as bounded candidate; AGP 9.5.x only as separately reviewed outside-range probe. Verify Maven metadata, R8 optimization DSL, signing/provenance, BC/commons/jose4j/JDOM/Guava graph deltas. Do not adopt new DSL flags. Separate CycloneDX/Jackson, Guava release and Room/Hilt updates.

## Acceptance for A

- Check exact-head existing hosted workflow results (all required, no failures) plus source/graph provenance and OSV delta; no missing or incomplete scan. KGP advisory must no longer match the selected versions in covered graphs. New or retained matches require documented decisions.
- Verify clean compilation, generated Room schema and Hilt bindings, warm no-op and edited-input incremental builds, configuration-cache reuse, R8 optimized release build, canonical TV and phone API26/API36 correctness, release provenance/SBOM and M0/C364 correctness. Existing CI is necessary but does not alone prove all incremental gates; inspect task outcomes and add a narrow hosted probe if needed before acceptance.
- No performance claims from CI duration or emulator measurements. No physical-device claims. No product source changes, no merge/rebase of #401. This branch includes #452 ancestry to permit existing main-targeted CI triggers; it is a diagnostic draft, not an independent merge unit before #452 is accepted. Preserve #452's green SHA, then restack/review final narrow diff before merge.
