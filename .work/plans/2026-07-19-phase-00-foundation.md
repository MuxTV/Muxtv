# Phase 00 Architecture Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создать воспроизводимый Android TV проект MuxTV с TV-first shell, архитектурными контрактами, database schema v1, CI, tests и debug APK artifact.

**Architecture:** Модульный монолит с отдельным Android application entry point, platform-neutral domain modules и adapter modules. UI использует Compose for TV и Navigation 3; playback изолирован контрактом `PlaybackEngine`; persistence проходит через repository boundary.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.0, Gradle 9.5.0, JDK 17, Compose for TV 1.1.0, Navigation 3 1.1.4, Media3 1.10.1, Room 3.0.0, Hilt 2.59.2, AndroidX Hilt 1.4.0.

## Global Constraints

- `minSdk=26`, `compileSdk=37`, `targetSdk=37`.
- Production dependencies use stable releases only.
- Domain modules must not import Android, Room, Media3, OkHttp or Compose types.
- TV controls use `androidx.tv.material3` variants and expose a visible focus state.
- No Rust, libmpv, Xtream, DVR or external plugins in Phase 00.
- Release signing secrets must never be committed.
- All architecture documentation and project status metadata remain under `.work`.

---

### Task 1: Reproducible Gradle foundation

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Generate: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.android.application.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.android.library.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.kotlin.library.gradle.kts`
- Test: `build-logic/convention/src/test/kotlin/ConventionPluginFilesTest.kt`

**Produces:** version catalog, Gradle wrapper and three convention plugins used by every later task.

- [ ] **Step 1: Write the failing repository-structure test**

The test asserts that the three convention plugin files exist and the version catalog contains `agp`, `kotlin`, `compose-bom`, `media3`, `room3`, and `navigation3` keys.

- [ ] **Step 2: Run the test and verify failure**

```bash
gradle -p build-logic :convention:test
```

Expected: FAIL because convention plugins and catalog entries do not exist.

- [ ] **Step 3: Implement the Gradle foundation**

Use `google()`, `mavenCentral()`, and `gradlePluginPortal()`. Set JVM toolchain 17. Enable configuration cache and parallel execution. Pin versions exactly from `.work/meta/dependencies.yaml`. Generate the wrapper with:

```bash
gradle wrapper --gradle-version 9.5.0 --distribution-type all
```

- [ ] **Step 4: Verify build configuration**

```bash
./gradlew help --configuration-cache
./gradlew help --configuration-cache
./gradlew -p build-logic :convention:test
```

Expected: tests PASS and the second `help` run reports configuration-cache reuse.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat build-logic
git commit -m "build: add reproducible Android TV toolchain"
```

### Task 2: Module graph and architecture enforcement

**Files:**
- Create: `app/tv/build.gradle.kts`
- Create: `core/common/build.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/database/build.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `core/testing/build.gradle.kts`
- Create: `player/api/build.gradle.kts`
- Create: `player/media3/build.gradle.kts`
- Create: `feature/home/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Test: `core/testing/src/test/kotlin/ModuleDependencyRulesTest.kt`

**Produces:** the minimal physical graph needed by all Phase 00 tasks.

- [ ] **Step 1: Write failing architecture assertions**

Reject Android plugins in `core:model`, direct `feature:* → player:media3`, direct `feature:* → core:database`, and dependency cycles.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :core:testing:test
```

Expected: FAIL because modules are not included.

- [ ] **Step 3: Create modules and dependencies**

`app:tv` depends on `feature:home`, `core:designsystem`, `core:database`, and `player:media3`. `player:media3` implements `player:api`. `feature:home` depends only on `player:api`, `core:model`, and `core:designsystem`.

- [ ] **Step 4: Verify graph**

```bash
./gradlew projects
./gradlew :core:testing:test
```

Expected: modules are listed and architecture tests PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app core player feature
git commit -m "build: establish MuxTV module boundaries"
```

### Task 3: TV design system and navigation shell

**Files:**
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/MuxTvTheme.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvFocusSurface.kt`
- Create: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeRoute.kt`
- Create: `app/tv/src/main/AndroidManifest.xml`
- Create: `app/tv/src/main/kotlin/app/muxtv/MuxTvApplication.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Test: `core/designsystem/src/test/kotlin/app/muxtv/designsystem/TvTokensTest.kt`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/NavigationFocusTest.kt`

**Produces:** launchable TV shell with deterministic D-pad focus and TV launcher declaration.

- [ ] **Step 1: Write failing token and focus tests**

Assert focus-scale/contrast tokens and that DPAD_RIGHT moves focus between two Home actions.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :core:designsystem:test :app:tv:connectedDebugAndroidTest
```

Expected: FAIL because theme, components and activity do not exist.

- [ ] **Step 3: Implement the TV shell**

Use `androidx.tv.material3.MaterialTheme`, Navigation 3 `NavDisplay`, immutable destinations and a focus surface with default/focused/pressed/disabled states. Declare `LEANBACK_LAUNCHER` and touchscreen-not-required metadata.

- [ ] **Step 4: Verify shell**

```bash
./gradlew :app:tv:assembleDebug :core:designsystem:test :app:tv:connectedDebugAndroidTest
```

Expected: APK builds and focus test PASS.

- [ ] **Step 5: Commit**

```bash
git add app/tv core/designsystem feature/home
git commit -m "feat: add TV-first application shell"
```

### Task 4: Domain IDs and playback contract

**Files:**
- Create: `core/model/src/commonMain/kotlin/app/muxtv/model/Identifiers.kt`
- Create: `core/model/src/commonMain/kotlin/app/muxtv/model/ChannelModels.kt`
- Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackEngine.kt`
- Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackModels.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/Media3PlaybackEngine.kt`
- Test: `player/api/src/test/kotlin/app/muxtv/player/PlaybackContractTest.kt`

**Produces:** stable platform-neutral channel models and replaceable playback boundary.

- [ ] **Step 1: Write failing contract tests**

Test typed IDs, immutable `PlaybackRequest`, stable error categories, and legal Idle → Preparing → Playing → Recovering → Failed transitions.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :player:api:test
```

Expected: FAIL because contract types do not exist.

- [ ] **Step 3: Implement contracts and minimal Media3 adapter**

The adapter wraps Media3 1.10.1 but exposes only MuxTV types. Phase 00 supports prepare, play, pause and stop for one media item; failover is excluded.

- [ ] **Step 4: Verify contracts**

```bash
./gradlew :player:api:test :player:media3:test
```

Expected: PASS and `player:api` has no Media3 dependency.

- [ ] **Step 5: Commit**

```bash
git add core/model player
git commit -m "feat: define playback and channel contracts"
```

### Task 5: Room schema v1 and migration harness

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/SourceEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/ProviderChannelEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/CanonicalChannelEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/StreamVariantEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/UserChannelOverlayEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/dao/CatalogDao.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/SchemaV1Test.kt`
- Generate: `core/database/schemas/app.muxtv.database.MuxTvDatabase/1.json`

**Produces:** Room 3 schema v1 with separated provider, canonical and user-overlay data.

- [ ] **Step 1: Write failing schema test**

Insert provider data, a canonical channel, variant and overlay; replacing provider metadata must preserve the overlay.

- [ ] **Step 2: Run and verify failure**

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

Expected: FAIL because schema and DAO do not exist.

- [ ] **Step 3: Implement schema v1**

Enable schema export. Use foreign keys and unique indexes. Do not configure destructive migration fallback for release builds.

- [ ] **Step 4: Verify schema**

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

Expected: PASS and schema JSON is generated.

- [ ] **Step 5: Commit**

```bash
git add core/database
git commit -m "feat: add catalog database schema v1"
```

### Task 6: CI, screenshots, benchmark and APK artifact

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/debug-apk.yml`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/kotlin/app/muxtv/benchmark/StartupBenchmark.kt`
- Create: `baseline-profile/build.gradle.kts`
- Create: `baseline-profile/src/main/kotlin/app/muxtv/baselineprofile/BaselineProfileGenerator.kt`
- Test: `core/designsystem/src/test/kotlin/app/muxtv/designsystem/MuxTvFocusSurfaceScreenshotTest.kt`
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`

**Produces:** required PR checks, screenshot regression coverage, Baseline Profile and downloadable debug APK artifact.

- [ ] **Step 1: Run the incomplete verification command**

```bash
./gradlew check lintDebug :app:tv:assembleDebug
```

Expected before implementation: FAIL because benchmark/profile/screenshot configuration is absent.

- [ ] **Step 2: Implement workflows and performance modules**

CI uses JDK 17, Gradle wrapper validation, dependency caching, `check`, lint, screenshot verification and APK artifact upload. Baseline Profile covers startup and Home navigation.

- [ ] **Step 3: Run full local verification**

```bash
./gradlew check lintDebug :app:tv:assembleDebug
./gradlew :baseline-profile:generateBaselineProfile
```

Expected: PASS; APK and baseline profile are generated.

- [ ] **Step 4: Inspect APK**

Use `apkanalyzer` or APK Analyzer to verify package name, SDK values, TV launcher intent and `assets/dexopt/baseline.prof` in the release-like APK.

- [ ] **Step 5: Commit**

```bash
git add .github benchmark baseline-profile core/designsystem app/tv settings.gradle.kts .gitignore
git commit -m "ci: add quality gates and debug APK pipeline"
```

### Task 7: Close Phase 00 documentation

**Files:**
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Modify: `.work/meta/modules.yaml`
- Create: `.work/reviews/phase-00-verification.md`

**Produces:** factual documentation matching the implemented repository.

- [ ] **Step 1: Run final verification**

```bash
./gradlew clean check lintDebug :app:tv:assembleDebug
```

Expected: successful clean build.

- [ ] **Step 2: Record evidence**

Record the exact commit, commands, test counts, APK path, APK SHA-256, emulator/device configuration and known limitations.

- [ ] **Step 3: Update status**

Set `phase: phase_01_reliable_live_tv`, mark all Phase 00 exit criteria true, and keep deferred features unchanged.

- [ ] **Step 4: Commit**

```bash
git add .work
git commit -m "docs: close Phase 00 foundation milestone"
```
