# Baseline Profile and Startup Macrobenchmark Foundation

> **Execution rule:** keep this increment limited to tooling and the already-existing cold-start journey. Search/Guide/Recent CUJs are added only after those product surfaces are accepted.

## Goal

Add the official Android Baseline Profile/Macrobenchmark toolchain to MuxTV so startup and later critical TV journeys can be optimized and compared with evidence instead of subjective tuning.

## References

- Android Baseline Profile Gradle plugin / Macrobenchmark guidance (current stable toolchain: Macrobenchmark 1.4.1 and ProfileInstaller 1.4.1).
- Android JetStreamCompose/Jetcaster as Apache-2.0 TV/Compose reference applications.
- NuvioTV only as an independent product reference; no GPL code is copied.

## Constraints

- Do not change playback, Room, Search or startup-maintenance behavior in this PR.
- Do not enable R8 in this PR; release hardening remains a separate measured increment.
- Do not claim a performance improvement until Macrobenchmark results compare Baseline Profiles enabled vs disabled on a physical device.
- Generation runs only on connected API 33+ or otherwise suitable rooted test devices; API26 remains a compatibility target, not a profile-generation target.
- The baseline profile must target `app.muxtv.tv` release package and `:app:tv`.

## Tasks

### 1. Add stable Baseline Profile tooling

- Add `com.android.test` plugin alias at AGP 9.3.0.
- Add `androidx.baselineprofile` plugin alias at 1.4.1.
- Register `:baseline-profile` in `settings.gradle.kts`.

### 2. Add baseline-profile module

Create a `com.android.test` module targeting `:app:tv` with:

- namespace `app.muxtv.baselineprofile`;
- API 33 minimum for connected non-root profile generation;
- AndroidJUnitRunner;
- Macrobenchmark 1.4.1 dependency;
- connected-device generation enabled.

### 3. Add one bounded startup profile generator

`BaselineProfileGenerator.startup()` records only:

- Home/launcher boundary reset;
- cold application launch through `startActivityAndWait()`.

Mark this path for the Startup Profile because it is genuinely part of initial display.

### 4. Add cold-start comparison benchmark

Add Macrobenchmark variants for:

- `CompilationMode.None()`;
- `CompilationMode.Partial(BaselineProfileMode.Require)`.

Measure cold startup with `StartupTimingMetric`; use at least 10 iterations for shareable evidence.

### 5. Wire generated profiles into app:tv

- Apply `androidx.baselineprofile` plugin to `:app:tv`.
- Keep existing `profileinstaller` dependency.
- Add `baselineProfile(project(":baseline-profile"))`.

### 6. Verification gates

Before merge:

- Gradle configuration succeeds on exact head.
- Baseline profile test module compiles.
- `:app:tv:assembleRelease` succeeds.
- Generate a startup baseline profile on a connected API33+ device.
- Verify packaged release includes the generated profile.
- Run cold-start Macrobenchmark with no compilation and Baseline Profile compilation on a physical TV/box before publishing any performance claim.

## Follow-up after product surfaces stabilize

Extend the generator/benchmarks with real MuxTV CUJs:

1. launch → first usable/focused navigation target;
2. Channels scroll and filter;
3. Channels → Player → Back;
4. Search input → result → Player → Back;
5. bounded Guide navigation.

These additions should reuse stable test tags and D-pad interactions from accepted product tests rather than creating benchmark-only UI paths.
