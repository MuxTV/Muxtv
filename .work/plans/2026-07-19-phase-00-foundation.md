# Phase 00 Architecture Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создать воспроизводимый Android TV проект MuxTV с premium TV shell, deterministic D-pad focus, architecture/domain/playback contracts, Room schema v1 с одним Основным профилем, CI, tests, benchmark baseline и downloadable debug APK.

**Architecture:** Android-first modular monolith. Pure Kotlin modules remain Android-free/KMP-compatible, but no KMP plugin/database target is introduced before a real second platform. Room is Android-only behind repositories; Media3 is isolated behind `PlaybackEngine`; features depend on contracts/use cases, not DAO/OkHttp/Media3.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.0, Gradle 9.5.0, JDK 17, Compose BOM 2026.06.00, Compose for TV 1.1.0, TV Foundation 1.0.0, Navigation 3 1.1.4, Media3 1.10.1, Room 3.0.0 after scaffold validation, Hilt 2.59.2, AndroidX Hilt 1.4.0, WorkManager 2.11.2, DataStore 1.2.1.

## Global Constraints

- `minSdk=26`, `compileSdk=37`, `targetSdk=37`.
- Production dependencies use stable releases only.
- Application ID baseline: `app.muxtv.tv`; debug builds use `.debug` suffix.
- Domain/pure Kotlin modules must not import Android, Room, Media3, OkHttp, Ktor, Compose or Hilt.
- Database remains Android-first per ADR-0003; no `commonMain`/KMP database in Phase 00.
- Clean database creation must atomically create exactly one undeletable primary profile named `Основной`.
- No built-in profile types (`Дети`, `Родители`, `Гости`) and no `profileType` field.
- TV controls use `androidx.tv.material3` variants and expose visible default/focused/pressed/selected/disabled states.
- All primary journeys must work with five-button D-pad and Back.
- Player lifetime belongs to process-scoped controller/service boundary, never Activity/Composable.
- No Rust, libmpv, Xtream, Stalker, DVR, VOD, multiview or executable extensions in Phase 00.
- Release signing secrets must never be committed or exposed to untrusted PR workflows.
- All architecture documentation and status metadata remain under `.work`.
- Every task ends in independently testable state and separate commit.

---

## Planned physical structure

```text
app/tv
core/common
core/model
core/database
core/designsystem
core/ui
core/testing
catalog/api
player/api
player/media3
player/fake
feature/home
benchmark
baseline-profile
build-logic/convention
```

Do not create future feature/provider modules in Phase 00. Add modules only when the task needs their boundary.

### Task 1: Reproducible Gradle foundation

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.android.application.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.android.library.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/muxtv.kotlin.library.gradle.kts`
- Test: `build-logic/convention/src/test/kotlin/app/muxtv/buildlogic/ConventionFilesTest.kt`

**Produces:** pinned toolchain/version catalog and three minimal convention plugins.

- [ ] **Step 1: Generate official Gradle 9.5 wrapper**

Run using a trusted local Gradle installation:

```bash
gradle wrapper --gradle-version 9.5 --distribution-type bin
```

Expected: wrapper scripts/JAR/properties exist and `./gradlew --version` reports Gradle 9.5 with JDK 17.

- [ ] **Step 2: Write failing convention/version-catalog test**

Test must assert that version catalog contains exact aliases for AGP, Kotlin, Compose BOM, TV Material, TV Foundation, Navigation 3, Media3, Room, Hilt, WorkManager and DataStore; three convention plugin source files must exist.

- [ ] **Step 3: Verify red state**

```bash
./gradlew -p build-logic :convention:test
```

Expected: FAIL because catalog/plugins are not complete.

- [ ] **Step 4: Implement minimal build foundation**

Use `google()`, `mavenCentral()`, `gradlePluginPortal()` only. Set JVM toolchain 17. Enable configuration cache, parallel execution and Gradle build cache. Do not add JitPack or snapshot repositories.

- [ ] **Step 5: Verify**

```bash
./gradlew help --configuration-cache
./gradlew help --configuration-cache
./gradlew -p build-logic :convention:test
```

Expected: PASS; second help run reports configuration cache reuse.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat build-logic
git commit -m "build: establish reproducible Android TV toolchain"
```

### Task 2: Minimal module graph and dependency enforcement

**Files:**
- Create: `app/tv/build.gradle.kts`
- Create: `core/common/build.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/database/build.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `core/ui/build.gradle.kts`
- Create: `core/testing/build.gradle.kts`
- Create: `catalog/api/build.gradle.kts`
- Create: `player/api/build.gradle.kts`
- Create: `player/media3/build.gradle.kts`
- Create: `player/fake/build.gradle.kts`
- Create: `feature/home/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Test: `core/testing/src/test/kotlin/app/muxtv/testing/ModuleDependencyRulesTest.kt`

**Produces:** physical graph matching `.work/meta/modules.yaml` without premature modules.

- [ ] **Step 1: Write failing architecture tests**

Test reads Gradle project/build files and rejects:

```text
core:model → Android/Room/Media3/OkHttp/Compose/Hilt
catalog:api → Android
player:api → Media3/Android
feature:* → core:database or player:media3
cycles
```

- [ ] **Step 2: Verify red state**

```bash
./gradlew :core:testing:test
```

Expected: FAIL because modules/dependency rules are not present.

- [ ] **Step 3: Create minimal modules**

Dependencies:

```text
app:tv → feature:home, player:media3, core:database, core:designsystem, core:ui
feature:home → core:model, core:designsystem, core:ui, player:api
player:media3 → player:api, core:common
player:fake → player:api
core:database → core:model, core:common
catalog:api → core:model, core:common
```

Pure Kotlin modules apply JVM plugin/toolchain only. Do not add KMP plugin.

- [ ] **Step 4: Verify graph**

```bash
./gradlew projects
./gradlew :core:testing:test
```

Expected: listed projects match plan and architecture tests PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app core catalog player feature
git commit -m "build: establish MuxTV module boundaries"
```

### Task 3: Domain identifiers, profile invariant and catalog skeleton

**Files:**
- Create: `core/common/src/main/kotlin/app/muxtv/common/Identifiers.kt`
- Create: `core/common/src/main/kotlin/app/muxtv/common/Clock.kt`
- Create: `core/model/src/main/kotlin/app/muxtv/model/ProfileModels.kt`
- Create: `core/model/src/main/kotlin/app/muxtv/model/CatalogModels.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/CatalogRepository.kt`
- Test: `core/model/src/test/kotlin/app/muxtv/model/ProfileInvariantTest.kt`
- Test: `core/model/src/test/kotlin/app/muxtv/model/CatalogIdentityTest.kt`

**Interfaces:**

```kotlin
@JvmInline value class ProfileId(val value: String)
@JvmInline value class SourceId(val value: String)
@JvmInline value class CanonicalChannelId(val value: String)
@JvmInline value class StreamVariantId(val value: String)

data class UserProfile(
    val id: ProfileId,
    val name: String,
    val isPrimary: Boolean,
)
```

No `profileType` enum/property.

- [ ] **Step 1: Write failing model tests**

Assert typed ID equality, primary profile name/invariant validation, additional arbitrary names, and absence of role/type semantics. Catalog test asserts URL is locator data, not channel ID.

- [ ] **Step 2: Verify red state**

```bash
./gradlew :core:model:test
```

Expected: compilation FAIL because types are absent.

- [ ] **Step 3: Implement minimal immutable models/contracts**

Use only Kotlin/JDK types and injected clock/ID factory contracts. Keep lifecycle/business policy in use-case layer, not Android constructor side effects.

- [ ] **Step 4: Verify**

```bash
./gradlew :core:model:test :catalog:api:test
```

Expected: PASS; dependency report confirms no Android dependencies.

- [ ] **Step 5: Commit**

```bash
git add core/common core/model catalog/api
git commit -m "feat: define profile and catalog domain contracts"
```

### Task 4: Room schema v1 with installation/profile separation

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/InstallationEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/ProfileEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/SourceEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/ProviderChannelEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/CanonicalChannelEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/StreamVariantEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/entity/UserChannelOverlayEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/dao/ProfileDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/dao/CatalogDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/DatabaseInitializer.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/SchemaV1ProfileTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/OverlayIsolationTest.kt`
- Create after test/build: `core/database/schemas/app.muxtv.database.MuxTvDatabase/1.json`

**Produces:** exported schema v1 with one primary profile and correct cascade boundaries.

- [ ] **Step 1: Write failing database tests**

Tests assert:

- empty database initialization creates one profile named `Основной`, `isPrimary=true`;
- initializer is idempotent;
- second primary profile violates invariant/transaction policy;
- primary cannot be deleted through repository/use case;
- arbitrary additional profile name works;
- no role/type column exists;
- deleting additional profile deletes only its overlays/history rows;
- source/provider/canonical/variant rows remain;
- provider metadata replacement preserves profile overlay.

- [ ] **Step 2: Verify red state**

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

Expected: FAIL because schema/DAO/initializer do not exist.

- [ ] **Step 3: Implement schema v1 and initializer**

Use foreign keys/unique indexes, schema export and short transactions. Do not use destructive migration fallback in release configuration. Keep secrets outside ordinary source entity fields through credential reference.

- [ ] **Step 4: Verify schema**

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

Expected: PASS and exported schema JSON committed.

- [ ] **Step 5: Commit**

```bash
git add core/database
git commit -m "feat: add database schema v1 and primary profile invariant"
```

### Task 5: Playback API, deterministic fake and minimal Media3 adapter

**Files:**
- Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackEngine.kt`
- Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackModels.kt`
- Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackError.kt`
- Create: `player/fake/src/main/kotlin/app/muxtv/player/fake/FakePlaybackEngine.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/Media3PlaybackEngine.kt`
- Test: `player/api/src/test/kotlin/app/muxtv/player/PlaybackContractTest.kt`
- Test: `player/fake/src/test/kotlin/app/muxtv/player/fake/FakePlaybackEngineTest.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/Media3ErrorMappingTest.kt`

**Produces:** stable engine-independent playback contract; no service/full playback UI yet.

- [ ] **Step 1: Write failing contract/state/error tests**

Assert legal state transitions, cancellable stop, semantic track IDs, stable error codes and redacted diagnostics. Test fake can emit first-frame, buffering, failure and recovery events deterministically.

- [ ] **Step 2: Verify red state**

```bash
./gradlew :player:api:test :player:fake:test :player:media3:test
```

Expected: FAIL because contracts/adapters are absent.

- [ ] **Step 3: Implement contracts and minimal adapter**

Media3 adapter may prepare/play/pause/stop one item and map a minimal set of current Media3 errors. It must not expose `Player`, `MediaItem`, `PlaybackException` outside module. No failover in Phase 00.

- [ ] **Step 4: Verify**

```bash
./gradlew :player:api:test :player:fake:test :player:media3:test
./gradlew :player:api:dependencies
```

Expected: tests PASS and `player:api` has no Media3/Android dependency.

- [ ] **Step 5: Commit**

```bash
git add player
git commit -m "feat: establish playback engine boundary"
```

### Task 6: TV design system, shell and deterministic focus

**Files:**
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/MuxTvTheme.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/TvTokens.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvFocusSurface.kt`
- Create: `core/designsystem/src/main/kotlin/app/muxtv/designsystem/component/MuxTvActionButton.kt`
- Create: `core/ui/src/main/kotlin/app/muxtv/ui/FocusBookmark.kt`
- Create: `feature/home/src/main/kotlin/app/muxtv/feature/home/HomeRoute.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/MuxTvApplication.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Create: `app/tv/src/main/AndroidManifest.xml`
- Test: `core/designsystem/src/test/kotlin/app/muxtv/designsystem/TvTokensTest.kt`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/NavigationFocusTest.kt`
- Test: `app/tv/src/test/kotlin/app/muxtv/HomeScreenshotTest.kt`

**Produces:** launchable TV shell with one primary profile context and visible focus.

- [ ] **Step 1: Write failing token/focus/screenshot tests**

Assert semantic focus tokens, minimum visible state cues, DPAD_RIGHT/LEFT movement, Back behavior and focus restoration after route round-trip. Initial screenshot states: default, focused, selected, high-contrast at 1080p reference.

- [ ] **Step 2: Verify red state**

```bash
./gradlew :core:designsystem:test :app:tv:testDebugUnitTest :app:tv:connectedDebugAndroidTest
```

Expected: FAIL because UI/activity/components are absent.

- [ ] **Step 3: Implement minimal shell**

Use Compose for TV Material components and Navigation 3. Show Home, Channels placeholder, Guide placeholder, Search placeholder and current `Основной` profile affordance. Do not show profile picker. Avoid mobile Material focusable controls.

Manifest declares leanback launcher, no touchscreen requirement, network permission and TV banner placeholder. No Google Play Services dependency.

- [ ] **Step 4: Verify APK and focus**

```bash
./gradlew :app:tv:assembleDebug :core:designsystem:test :app:tv:testDebugUnitTest :app:tv:connectedDebugAndroidTest
```

Expected: APK builds; all tests PASS; D-pad can traverse and return predictably.

- [ ] **Step 5: Commit**

```bash
git add app/tv core/designsystem core/ui feature/home
git commit -m "feat: add TV-first shell and focus design system"
```

### Task 7: Composition root and safe local observability

**Files:**
- Create: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Create: `core/common/src/main/kotlin/app/muxtv/common/diagnostics/DiagnosticEvent.kt`
- Create: `core/common/src/main/kotlin/app/muxtv/common/diagnostics/Redactor.kt`
- Create: `core/common/src/main/kotlin/app/muxtv/common/diagnostics/BoundedEventBuffer.kt`
- Test: `core/common/src/test/kotlin/app/muxtv/common/diagnostics/RedactorTest.kt`
- Test: `core/common/src/test/kotlin/app/muxtv/common/diagnostics/BoundedEventBufferTest.kt`

**Produces:** Hilt composition root and secret-safe bounded local diagnostics foundation.

- [ ] **Step 1: Write failing canary redaction/buffer tests**

Cover URL userinfo/query, Authorization/Cookie, common token/password keys, bounded eviction and correlation ID preservation.

- [ ] **Step 2: Verify red state**

```bash
./gradlew :core:common:test :app:tv:assembleDebug
```

Expected: FAIL before implementation/wiring.

- [ ] **Step 3: Implement minimal DI and diagnostics**

Hilt provides database/repositories/player contract implementations at composition root. Release logging accepts only redacted structured events. No telemetry/crash upload SDK.

- [ ] **Step 4: Verify**

```bash
./gradlew :core:common:test :app:tv:assembleDebug
```

Expected: PASS and debug app starts with injected dependencies.

- [ ] **Step 5: Commit**

```bash
git add app/tv core/common
git commit -m "feat: add composition root and redacted diagnostics"
```

### Task 8: CI, screenshots, benchmark baseline and debug artifact

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/debug-apk.yml`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/kotlin/app/muxtv/benchmark/StartupBenchmark.kt`
- Create: `benchmark/src/main/kotlin/app/muxtv/benchmark/HomeFocusBenchmark.kt`
- Create: `baseline-profile/build.gradle.kts`
- Create: `baseline-profile/src/main/kotlin/app/muxtv/baselineprofile/BaselineProfileGenerator.kt`
- Create: `.work/quality/reference-devices.md`
- Create: `.work/reviews/phase-00-baseline.md`
- Modify: `.gitignore`

**Produces:** required PR checks, baseline evidence template and downloadable debug APK.

- [ ] **Step 1: Add workflows with minimal permissions**

CI uses JDK 17, official Gradle setup/wrapper validation, read-only default permissions, `check`, lint, architecture tests, database instrumentation where runner supports it, screenshot verification and debug APK upload. Third-party actions pinned to immutable commit SHA.

- [ ] **Step 2: Implement benchmark/baseline modules**

Baseline Profile covers cold start to Home and opening first focusable action. Macrobenchmark records startup and rapid Home focus navigation. Reports identify emulator/reference device and are not claimed as physical codec evidence.

- [ ] **Step 3: Run full local verification**

```bash
./gradlew clean check lintDebug :app:tv:assembleDebug
./gradlew :baseline-profile:generateBaselineProfile
./gradlew :benchmark:connectedCheck
```

Expected: all commands exit 0; debug APK and baseline profile generated; benchmark report saved/referenced.

- [ ] **Step 4: Inspect APK**

Use `apkanalyzer`/Android Studio APK Analyzer and verify:

```text
applicationId app.muxtv.tv.debug
minSdk 26 / targetSdk 37
LEANBACK_LAUNCHER
no touchscreen requirement
baseline.prof packaged in release-like build
no release signing secret
```

- [ ] **Step 5: Commit**

```bash
git add .github benchmark baseline-profile .work/quality/reference-devices.md .work/reviews/phase-00-baseline.md .gitignore
git commit -m "ci: add quality gates benchmark and debug APK pipeline"
```

### Task 9: Final verification and factual documentation update

**Files:**
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Modify: `.work/meta/modules.yaml` only if actual graph differs with accepted reason
- Create: `.work/reviews/phase-00-verification.md`

**Produces:** evidence-backed completion record; target docs no longer confused with implemented state.

- [ ] **Step 1: Run fresh final verification**

```bash
./gradlew clean check lintDebug :app:tv:assembleDebug
./gradlew :core:database:connectedDebugAndroidTest
./gradlew :app:tv:connectedDebugAndroidTest
```

Expected: exit 0 for every command, zero failing tests/lint errors.

- [ ] **Step 2: Verify repository facts**

Confirm exact module list, schema JSON, APK path/metadata, CI files, baseline profile, primary profile test and current commit. Do not mark features such as M3U playback/EPG/QR implemented.

- [ ] **Step 3: Update `.work` status and review report**

`CURRENT-STATE.md` and `status.yaml` list only implemented Phase 00 facts, commands and evidence. `phase-00-verification.md` records outputs, device/emulator, known limitations and next Phase 01 entry conditions.

- [ ] **Step 4: Commit**

```bash
git add .work/CURRENT-STATE.md .work/meta/status.yaml .work/meta/modules.yaml .work/reviews/phase-00-verification.md
git commit -m "docs: record verified Phase 00 foundation"
```

## Phase 00 final acceptance checklist

- [ ] Gradle 9.5/JDK17 build is reproducible and configuration cache works.
- [ ] Module dependency rules pass and no KMP/database premature target exists.
- [ ] Schema v1 exports and initializes exactly one primary `Основной` profile.
- [ ] Additional arbitrary profiles are supported by model/schema without built-in roles.
- [ ] Provider/canonical/profile overlay tables have non-destructive boundaries.
- [ ] `player:api` contains no Media3/Android types and fake engine tests pass.
- [ ] TV shell is remote-operable with visible focus and Back restoration.
- [ ] No profile picker appears for the single primary profile.
- [ ] Redaction canary tests pass; no telemetry SDK/signing secret committed.
- [ ] Debug APK contains TV launcher metadata and baseline profile.
- [ ] CI uploads debug APK and runs quality gates.
- [ ] Benchmark/report identifies environment and does not overclaim.
- [ ] `.work` factual status matches repository.

## Execution handoff

Implementation starts in an isolated worktree/branch and follows this plan task by task with review between tasks. No Phase 01 feature work is mixed into Phase 00.