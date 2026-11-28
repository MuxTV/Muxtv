# DEP-01b — Gradle 9.9.2 diagnostic compatibility probe

Refs #455, #453, #454, #452, #179, #195. 2026-11-25.
Comparison parent: 137866520202d744d5c8352a125ee095751ff1a5. Keep #454 unchanged while remaining exact-head checks finish. This is a diagnostic stacked draft targeted at main to use existing CI, not an accepted merge unit or permission to bypass prerequisites.

## Observed prerequisite evidence

KSP regeneration 45617843466 SUCCESS, job 139217783906, artifact 15023428531 (215152 bytes, sha256 d0b8d9130c627cebf023677e57bb597b27ec5c0ff5ab50609fff35624551789d). Four fixture tests and clean/warm/mutated/restored phases passed; Room schemas unchanged, source bytes restored. Both KSP/Kotlin tasks and Hilt Java compile UP-TO-DATE on warm build, EXECUTED on changed/restored inputs; configuration cache reused on warm/mutated/restored phases. Evidence from logs and artifact metadata, ZIP not independently inspected. Prior exact source ccfd800b093474b7887a67543d0d93af643efcde passed 15/15 workflows.

Current parent inventory 45617843161 SUCCESS: 6 runtime / 15 build graphs, 345/261 GAV, 17 advisory IDs / 21 rows, no scan failures. C364 45617843199 initial canonical API36 job failed on sdkmanager Android Emulator Premature EOF before tests; smoke API26/API36 and correctness contract passed; failed-job retry accepted, attempt 2 running. Hosted validation and TV matrix still running at decision. Specialized regeneration gate is GREEN; full parent acceptance remains PENDING. Start independent probe now without changing/canceling parent runs; if parent fails on a product regression, stop and reconcile before accepting this candidate.

## Compatibility decision

Gradle 9.9.2 is publicly available and contains security fixes documented by upstream; 9.7.0 does not resolve the later advisories. Older patched lines may require Gradle Security Subscription; no entitlement assumed. Kotlin 2.4.21 fully supported range ends at Gradle 9.7.0, so this deliberately exceeds documented full support, not a claim of vendor certification. AGP 9.3.0 minimum Gradle 9.5.0. Preserve AGP/Kotlin/KSP, JDK 25 runtime/17 toolchain, SDK and all runtime declarations. No Isolated Projects or new parallel configuration-cache flags. Do not merge merely because CI resolves.

## Atomic wrapper change

Official source: https://github.com/gradle/gradle-distributions/releases/tag/v9.9.2
- bin ZIP SHA-256 971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b
- wrapper JAR SHA-256 621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8

Actual upstream JAR bytes previously fetched and independently hashed (49154 bytes), matching official checksum. Copy exact upstream tag v9.9.2 JAR and both launcher blobs, verified destination Git blob SHA equals upstream, preserving POSIX executable mode and Windows regular-file mode:
- gradle/wrapper/gradle-wrapper.jar 100644 51cbd2a6f8121b2ce12daf979b128058fb9672ee
- gradlew 100755 203529cbc6f40b75a82fe2b6a6e3f8d5bc2efd55
- gradlew.bat 100644 5c30135bef0f00ed5cc7e55db9d3ea3af1111a76

The upstream source tag's own distribution properties target 9.9.1; do NOT copy those properties. Set project distribution URL to 9.9.2 and official release checksum. Preserve project networkTimeout, retries, validation and distribution storage settings. Update preflight's exact properties and independent checksum pins atomically. Upstream launchers include UTF-8 default encoding and Windows launcher changes; use exact upstream bytes instead of manually reconstructing scripts. No launcher authenticity claim from the runtime guard alone. ZIP hash verification occurs on fresh wrapper download; not independent verification of an existing unpacked cache. No new dependency repositories or new actions.

## Admission

1. Independent review/acceptance of #452 and #454 before merge; restack final narrow diff, retain all gates. This PR is not permission to merge the stack as one unit.
2. Verify actual Gradle version and embedded Kotlin/DSL resolution from hosted logs and inventory; wrapper positive and negative tests on Linux and Windows.
3. Exact-head convention/host validation, 4-phase KSP probe, R8, release provenance/SBOM, M0/C364, TV/phone API26/API36, crypto and QR gates. Compare security graph delta and warning changes; reject missing/incomplete evidence. No performance or physical-device claim.
4. Separately assess each Gradle distribution advisory and residual applicability; OSV selected Maven graphs do not cover Gradle distribution. Explicit reviewed compatibility exception required even if CI passes. If incompatible, isolate RED and evaluate supported alternatives; do not silently loosen gates.
