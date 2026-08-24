# MuxTV 0.1 Alpha — Static Release Gap Audit

Baseline reviewed: accepted `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

This report is static repository evidence only. It does not claim build, R8, signing, Baseline Profile or device GREEN.

## 1. What already exists

### Release identity

`app/tv/build.gradle.kts` defines:

- `applicationId = app.muxtv.tv`;
- `versionCode = 1001`;
- `versionName = 0.1.0-alpha.1`;
- separate debug suffix;
- release optimization enabled.

### Baseline Profile foundation

The application applies the Baseline Profile plugin, consumes `:benchmark:macrobenchmark`, saves profiles in source and does not automatically regenerate them during every build.

`benchmark:macrobenchmark` contains:

- `BaselineProfileGenerator`;
- `MuxTvCriticalUserJourneys`;
- `MuxTvMacrobenchmarks`.

Current Baseline Profile collection reaches:

- startup/Home;
- Channels;
- Search;
- Guide;
- Sources;
- Doctor.

Current Macrobenchmark metrics include:

- cold startup timing;
- warm startup timing;
- frame timing for Home -> Channels;
- frame timing for Home -> Search;
- frame timing for Home -> Guide;
- frame timing for Sources -> Doctor.

### R8 keep-rule foundation

`app/tv/src/main/keepRules/muxtv.keep` exists and currently contains only the policy comment to add narrow rules when release/runtime evidence requires them. This is a good minimal baseline for later R8 Configuration Analyzer review; there is no current broad application keep rule to delete speculatively.

### Android release verification foundation

The repository already has Fast/Full host validation, focused/product/database device lanes, benchmark foundation and exact-source-head evidence tooling. D0/#181 provides the canonical API26/API36 virtual-device contract.

## 2. Proven static gaps

### G1 — Baseline Profile is navigation-reachability heavy

Current profile generation opens destinations but does not exercise the actual hot work required by #31:

- sustained Channels D-pad/paging/focus restoration;
- realistic Search input -> result rendering -> result navigation;
- populated Guide horizontal/vertical movement;
- Player launch -> first rendered frame;
- repeated channel zaps;
- semantic seek burst through #175 authority.

Therefore the current profile is a valid foundation, but not yet the release-complete CUJ profile.

### G2 — Macrobenchmark journey depth is insufficient

Current frame tests primarily measure route transitions. They do not currently encode:

- long D-pad navigation;
- paging boundary cost;
- Search query/update work;
- Guide viewport movement;
- Player startup/first-frame;
- channel zap distribution;
- seek/rebuffer work.

Do not interpret current route-transition numbers as whole-product runtime performance.

### G3 — No release-specific R8 Analyzer evidence is repository truth yet

Release optimization is enabled and a narrow keep-rule source set exists, but an accepted R8 Configuration Analyzer report is not part of the reviewed main evidence snapshot. #31 must generate/review it on the eventual release candidate before keep-rule conclusions.

### G4 — Signing acceptance is not expressed in `app/tv/build.gradle.kts`

The accepted application build file contains release optimization but no explicit release `signingConfig` block. This report does not infer whether external/manual signing material exists outside the reviewed file. #31 still needs an explicit reproducible release-signing procedure and certificate/package provenance contract without committing key material.

### G5 — SBOM generation is not a root build plugin on accepted main

The root plugin declaration includes Android, Baseline Profile, Kotlin, KSP, Hilt, Room3 and JMH plugins, but no SBOM plugin is declared there. This does not prove no external SBOM procedure exists; it proves SBOM generation is not currently visible as a root Gradle plugin in the accepted baseline.

Before alpha, choose one reproducible SBOM/dependency-report path and document exact output/digest ownership. Do not add a dependency solely to satisfy a checkbox if existing Gradle/dependency tooling can produce the required evidence cleanly.

### G6 — No dedicated release workflow is present in the accepted `.github/workflows` set

The accepted workflow set contains host/device/lint/benchmark/measurement gates, but no workflow named/owned as an alpha release/signing publication lane. This is a static gap in automation, not a request to create a workflow while Actions are unavailable.

Release automation design/implementation must wait for CI changes to be allowed again; meanwhile the release contract/checklist can be completed offline.

## 3. Release CUJ gap matrix

| CUJ | Existing static foundation | Release gap |
| --- | --- | --- |
| Startup -> usable Home | baseline + cold/warm startup | define first-usable-focus/result boundary if startup timing alone is insufficient |
| Channels sustained navigation | route open only | 50–100 D-pad moves, paging/focus boundary, frame distribution |
| Search | route open only | deterministic synthetic query, result-ready boundary, result navigation |
| Guide | route open only | populated bounded fixture + horizontal/vertical movement |
| Sources/Doctor | existing route transition | keep as secondary maintenance journey; lower priority than Player |
| Player first frame | app/player tests exist elsewhere | Macrobenchmark/CUJ must launch real deterministic fixture and record first-frame boundary |
| Channel zapping | playback architecture exists | repeated deterministic switches + distribution |
| Seek | #175 authority exists | bounded burst through semantic service path + apply/render/rebuffer evidence |

## 4. What can be prepared without runner

- define stable CUJ names/test tags/fixture requirements in docs;
- reuse existing Android instrumentation fixture concepts instead of inventing production data;
- define which metrics each journey produces;
- define exact-source/device/fixture provenance fields;
- map existing test tags/helpers that can be reused by future benchmark code;
- define release signing input/output contract without storing secrets;
- define SBOM/dependency report required fields and artifact naming;
- review current keep-rule ownership statically.

## 5. What must wait for executable evidence

- adding/changing benchmark Kotlin intended to be accepted without observing compile/runtime behavior;
- generating the actual Baseline Profile;
- R8 Analyzer result/score conclusions;
- deleting/adding keep rules based only on static guesses;
- release assembly/install claim;
- signing verification claim;
- SBOM generation claim;
- device/frame/startup/first-frame/zap/seek performance claim.

## 6. Recommended release implementation order after runner returns

1. U0 -> U1 -> M0 stabilization first.
2. Freeze accepted stack/dependency baseline.
3. Expand benchmark journey helpers with a real RED/compile gate.
4. Add Channels/Search/Guide workload CUJs.
5. Add Player first-frame/zap/seek CUJs against deterministic fixtures.
6. Generate/review Baseline Profile.
7. Run R8 Configuration Analyzer and release assembly.
8. Establish release signing procedure and verify package/certificate identity.
9. Generate SBOM/dependency report.
10. Run canonical API26/API36 release correctness.
11. Run physical-device support-claim matrix.
12. Publish alpha notes using the explicit evidence taxonomy.

## 7. Decision

The release foundation is materially present, but it is not release-complete. The highest-value gap is not another Gradle flag; it is turning current screen-reachability benchmark coverage into actual IPTV TV-workload CUJs, then coupling that with explicit signing/SBOM/R8/physical-evidence acceptance.
