# Module Boundary Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the accepted feature → stable port → adapter dependency direction without changing product semantics, duplicating playback/refresh ownership, or introducing a repository-wide domain rewrite.

**Architecture:** Add a cheap static module-boundary contract first, then invert Sources onto stable catalog ports, invert Player controls/state onto `player:api` while keeping the Media3 rendering surface adapter-owned, and finally consolidate the ancillary recent-playback coroutine onto the existing application IO scope. Existing Room run-token leases, immutable revision publication, EPG relation-snapshot publication and service-owned ExoPlayer/seek authority remain unchanged.

**Tech Stack:** Kotlin 2.4.x, Android/Compose for TV, Gradle Kotlin DSL, Hilt, Coroutines/Flow, Room3, WorkManager, Media3.

**Spec:** `docs/architecture/2026-08-24-module-boundary-and-runtime-ownership-audit.md`

## Global Constraints

- Do not execute this plan while GitHub Actions/self-hosted validation is intentionally frozen unless the user explicitly re-enables execution.
- Do not modify PR #189, PR #190, or `.github/ui-characterization/run.request` as part of this train.
- Do not create any additional Android TV AVD identity. Canonical identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- Preserve one service-owned ExoPlayer and one service-owned semantic seek mutation authority.
- Preserve WorkManager scheduling + Room run-token lease ownership for source/EPG refresh.
- Preserve immutable catalog/EPG revision publication and `replaceIfCurrent` matching semantics.
- Do not change Room schema merely to invert dependencies.
- Do not add a generic telemetry/event bus, service locator, new global Redux/MVI system, or speculative plugin abstraction.
- Keep Sources and Player corrections independently reviewable and revertible.
- Write an executable RED contract before each production correction.

---

## File structure and intended responsibilities

### New/modified architecture-test surface

- Create: `tools/architecture/Test-ModuleDependencyBoundaries.ps1`
  - repository-owned static dependency-edge checker;
  - parses Gradle project dependencies from selected feature build files;
  - reports exact illegal edge;
  - does not invoke Gradle or Android.
- Create: `tools/architecture/Test-ModuleDependencyBoundaries.Tests.ps1`
  - synthetic positive/negative fixtures for the checker itself.
- Modify later: `tools/verify-local.ps1`
  - invoke the cheap architecture contract in Fast mode after the checker is proven stable.
  - Do not edit any GitHub workflow solely for this contract.

### Sources stable API

- Modify: `catalog/api/src/main/kotlin/app/muxtv/catalog/...`
  - add only source-management state/commands genuinely consumed by UI.
- Modify: `catalog/onboarding/...` only if a stable onboarding-facing contract cannot cleanly live in `catalog:api` without forcing Android implementation details into the API.
- Create/modify adapter in app/catalog composition:
  - implements stable source-management port by delegating to existing `SourceRefreshStore`, `SourceRefreshScheduler`, `PlaybackAccessPolicyResolver` and durable onboarding.
- Modify: `feature/sources/...`
  - consume stable API only;
  - retain presentation/focus/session state.

### Player stable API

- Modify: `player/api/src/main/kotlin/app/muxtv/player/...`
  - add Media3-neutral UI session/seek/track command and state contracts only where current feature needs them.
- Modify/create adapter-owned Media3 UI surface under `player/media3` or a narrowly justified `player:ui-media3` module if Compose dependency shape demands separation.
- Modify: `feature/player/...`
  - remove MediaController and `app.muxtv.player.media3.*` from feature state/command ownership;
  - preserve overlay/focus presentation.
- Modify: `app/tv/...`
  - compose the feature and adapter surface/session implementation.

### Process scope cleanup

- Modify: `app/tv/src/main/kotlin/app/muxtv/di/RecentPlaybackModule.kt`
  - inject existing `@ApplicationIoScope` instead of constructing a second process scope.
- Existing `RecentPlaybackObserver.kt` behavior should remain unchanged unless a test proves an interface correction is needed.

---

# Task 1: Add a static module dependency boundary contract

**Files:**
- Create: `tools/architecture/Test-ModuleDependencyBoundaries.ps1`
- Create: `tools/architecture/Test-ModuleDependencyBoundaries.Tests.ps1`
- Modify after GREEN: `tools/verify-local.ps1`

**Interfaces:**
- Consumes: repository paths and Gradle Kotlin DSL `project(":...")` dependency declarations.
- Produces: deterministic exit status and exact `feature-module -> forbidden-module` diagnostics.

### Forbidden feature edges for the first contract

```text
feature:* -> core:database
feature:* -> core:credentials
feature:* -> catalog:sync
feature:* -> player:media3
```

Do not globally ban `catalog:refresh` in the checker until Sources onboarding contracts are relocated; the RED test for Sources should explicitly describe the current intended violation during migration.

- [ ] **Step 1: Write the checker tests first**

Use temporary synthetic module build files to prove:

```text
feature:good -> catalog:api                    PASS
feature:good -> player:api                     PASS
feature:bad  -> core:database                  FAIL
feature:bad  -> core:credentials               FAIL
feature:bad  -> catalog:sync                   FAIL
feature:bad  -> player:media3                  FAIL
app:tv       -> player:media3                  PASS
player adapter -> player:media3                PASS
```

Expected failure text must name both source module and forbidden target.

- [ ] **Step 2: Run the checker test script and observe RED**

Run:

```powershell
pwsh -NoProfile -File .\tools\architecture\Test-ModuleDependencyBoundaries.Tests.ps1
```

Expected before implementation: failure because `Test-ModuleDependencyBoundaries.ps1` does not exist or cannot satisfy the synthetic cases.

- [ ] **Step 3: Implement the minimal static checker**

Requirements:

- inspect only repository-owned `feature/*/build.gradle.kts` by default;
- match project dependencies structurally enough to avoid comments/string literals being treated as edges;
- support explicit include/exclude input for synthetic tests;
- fail closed on unreadable selected build file;
- output sorted deterministic violations;
- no Gradle invocation;
- no network access.

- [ ] **Step 4: Run checker unit contract**

```powershell
pwsh -NoProfile -File .\tools\architecture\Test-ModuleDependencyBoundaries.Tests.ps1
```

Expected: all synthetic cases pass.

- [ ] **Step 5: Run checker against current repository and preserve the expected RED inventory**

```powershell
pwsh -NoProfile -File .\tools\architecture\Test-ModuleDependencyBoundaries.ps1
```

Expected at this stage: exact violations for current `feature:player` and `feature:sources`; no surprise violations from Home/Channels/Guide/Search/Doctor/Settings.

Do not add this checker to `verify-local Fast` while production still intentionally contains the audited violations.

- [ ] **Step 6: Commit the contract-only RED checkpoint**

```bash
git add tools/architecture
git commit -m "test(architecture): characterize forbidden feature dependencies"
```

---

# Task 2: Move Sources UI onto stable catalog ports

**Files:**
- Modify/Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/SourceManagement.kt`
- Modify/Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/SourceOnboarding.kt` if onboarding belongs in this stable API surface after review
- Create: app/catalog adapter implementation, exact package chosen to match current DI ownership
- Modify: `feature/sources/build.gradle.kts`
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourcesRoute.kt`
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourceEntrySession.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Test: existing Sources/onboarding/refresh tests plus new port-mapping tests

**Interfaces:**
- Consumes: existing `SourceRefreshStore`, `SourceRefreshScheduler`, `PlaybackAccessPolicyResolver`, durable onboarding implementation.
- Produces: feature-safe source overview/policy/onboarding commands with no Room/WorkManager/credential implementation types.

- [ ] **Step 1: Add a RED compile/static contract for Sources dependencies**

Extend the architecture checker fixture/repository expectation so acceptance requires:

```text
feature:sources -> catalog:api       allowed
feature:sources -> core:designsystem allowed
feature:sources -> core:database     forbidden
feature:sources -> core:credentials  forbidden
feature:sources -> catalog:sync      forbidden
```

Run the current-repository checker and confirm the existing Sources edges fail.

- [ ] **Step 2: Define minimal API models from actual UI needs**

Use only fields currently rendered/mutated by Sources. Example shape:

```kotlin
data class SourceOverview(
    val sourceId: String,
    val sourceName: String,
    val activeRevision: Long,
    val refreshPolicy: SourceRefreshPolicy?,
    val refreshStatus: SourceRefreshStatus?,
)

data class SourceRefreshPolicy(
    val sourceId: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val unmeteredOnly: Boolean,
    val requiresCharging: Boolean,
)
```

Do not expose `credentialRef`, WorkRequest IDs, DAO entities or raw failure strings.

- [ ] **Step 3: Define the narrow source-management port**

Expected capability surface:

```kotlin
interface SourceManagement {
    fun observeSources(): Flow<List<SourceOverview>>
    fun refreshNow(sourceId: String)
    suspend fun updateRefreshPolicy(policy: SourceRefreshPolicy)
    suspend fun removeRefreshPolicy(sourceId: String)
    suspend fun revokePlaybackApprovals(sourceId: String): SourceApprovalResetResult
}
```

Use typed results. Do not throw adapter exceptions through the feature boundary as normal control flow.

- [ ] **Step 4: Add mapping/adapter tests before implementation**

Cover:

- DB status -> API status mapping;
- missing source -> typed not-found approval reset;
- credential/access unavailable -> typed unavailable result without credential value;
- policy update delegates once to existing scheduler;
- refreshNow delegates once and introduces no second retry loop;
- Flow mapping preserves source identity and active revision.

- [ ] **Step 5: Implement the adapter by composition, not reimplementation**

Delegate to the existing owners:

```text
read/status       -> SourceRefreshStore
scheduling        -> SourceRefreshScheduler
approval mutation -> PlaybackAccessPolicyResolver
onboarding        -> DurableRemoteSourceOnboarding existing path
```

Do not move WorkManager scheduling into the feature/API object.

- [ ] **Step 6: Migrate SourcesRoute**

Replace `SourceRefreshStore` and `SourceRefreshScheduler` parameters with `SourceManagement` (or an equivalent stable port). Keep:

- focus requesters;
- busy-source UI guard;
- details sheet state;
- user-visible error copy;
- current D-pad behavior.

- [ ] **Step 7: Migrate SourceEntrySession contracts**

Feature session state stays feature-owned. Remote preparation token/result contracts crossing the feature boundary must be stable catalog/onboarding API types, not `catalog:refresh` implementation contracts.

Preserve:

- `Mutex` exclusivity;
- cancellation behavior;
- cleanup-pending semantics;
- prepare → approve HTTP → activate/cancel state transitions.

- [ ] **Step 8: Remove adapter dependencies from feature:sources**

Expected Gradle end-state:

```text
implementation(project(":catalog:api"))
implementation(project(":core:designsystem"))
```

plus UI/coroutine libraries actually used.

No direct:

```text
catalog:sync
core:database
core:credentials
```

- [ ] **Step 9: Run Sources and architecture tests**

Run targeted host tests, then the static architecture checker. Expected: Sources violations disappear while Player violation remains intentionally RED for the next task.

- [ ] **Step 10: Commit Sources inversion independently**

```bash
git add catalog/api catalog/onboarding feature/sources app/tv tools/architecture
git commit -m "refactor(sources): invert UI onto catalog ports"
```

---

# Task 3: Invert Player feature controls/state away from Media3 implementation

**Files:**
- Modify/Create: `player/api/src/main/kotlin/app/muxtv/player/PlaybackUiSession.kt`
- Modify/Create: provider-neutral seek/track types in `player/api`
- Modify: `player/media3/...` connector/session adapter
- Move/create Media3-specific `PlayerSurface` host under adapter ownership
- Modify: `feature/player/build.gradle.kts`
- Modify: `feature/player/PlayerRoute.kt`
- Modify: `feature/player/PlayerSurfaceContent.kt`
- Modify: `app/tv` composition as required
- Test: player API policy tests, media3 adapter tests, feature host tests

**Interfaces:**
- Consumes: current service custom commands, PlaybackSessionState, PlaybackCapabilities, service-owned seek/track mechanics.
- Produces: Media3-neutral feature session commands/state; Media3 surface remains adapter-owned.

- [ ] **Step 1: Lock the current architecture defect as RED**

Run the static checker and record `feature:player -> player:media3` as the remaining expected violation after Task 2.

- [ ] **Step 2: Write provider-neutral session contract tests**

Required behavior:

- start returns existing typed `PlaybackStartResult` semantics;
- cancellation remains cancellation, not generic failure;
- session state is observable without MediaController;
- seek command carries service generation identity without exposing Media3 Player;
- track selection uses semantic track identity, not array index;
- no locator/request headers appear in public session state.

- [ ] **Step 3: Define minimal `player:api` session surface**

Do not mirror every MediaController method. Expose only current MuxTV feature needs.

Conceptual shape:

```kotlin
interface PlaybackUiSession {
    val sessionState: StateFlow<PlaybackSessionState>
    val capabilities: StateFlow<PlaybackCapabilities>

    suspend fun start(request: PlaybackStartRequest): PlaybackStartResult
    suspend fun seek(command: PlaybackSeekCommand): PlaybackSeekOutcome
    suspend fun selectTrack(selection: PlaybackTrackSelection): PlaybackTrackSelectionResult
}
```

Exact names must be consistent across API and adapter tests.

- [ ] **Step 4: Implement Media3 adapter on top of existing connector/service commands**

The adapter may use MediaController internally. Feature code may not.

Do not move ExoPlayer creation out of `MuxTvPlaybackService`.

- [ ] **Step 5: Keep Media3 rendering surface adapter-owned**

Move the `PlayerSurface` integration to Media3-owned code or a narrowly justified Media3 UI adapter module.

Feature owns:

- overlay state;
- buttons/sheets/HUD/focus;
- presentation of provider-neutral capability/track state.

Adapter owns:

- binding Media3 controller/player to `PlayerSurface`;
- Media3 listener mechanics needed only for rendering.

Do not pass raw MediaController back through a lambda simply to satisfy the module checker.

- [ ] **Step 6: Migrate PlayerRoute**

Remove from feature state/signatures:

```text
MediaController
MuxTvMediaControllerConnector
MediaControllerOperationException
MediaControllerOperationFailure
```

Map adapter failures to existing provider-neutral typed failures before they cross into feature code.

- [ ] **Step 7: Migrate seek and track controls**

All feature-issued seek input must still converge on the service-owned semantic seek command. UI may retain provisional HUD state only.

There must be no `player.seekTo()` in feature code.

- [ ] **Step 8: Remove player:media3 Gradle dependency from feature:player**

The architecture checker should become GREEN for all feature module edges.

If `media3-ui-compose` is required only by the adapter surface, remove it from `feature:player` as well.

- [ ] **Step 9: Run targeted host/compile tests**

Run:

- `player:api` unit tests;
- `player:media3` unit tests;
- `feature:player` unit tests;
- relevant app TV host tests;
- architecture checker.

Expected: no feature→adapter dependency violation.

- [ ] **Step 10: Run device acceptance when runner is available**

Because this task changes Player UI/Media3 integration, exact-head validation must include:

- canonical API36 playback start/first frame/seek/Back/track controls;
- canonical API26 compatibility;
- no additional AVD;
- existing product/device matrix as appropriate;
- no physical codec/HDR claims from emulator evidence.

- [ ] **Step 11: Commit Player inversion independently**

```bash
git add player/api player/media3 feature/player app/tv tools/architecture
git commit -m "refactor(player): isolate Media3 behind playback UI ports"
```

---

# Task 4: Consolidate Recent playback persistence onto the application IO scope

**Files:**
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/RecentPlaybackModule.kt`
- Test: existing recent playback observer/module tests; add DI/factory unit coverage if current suite cannot observe scope injection

**Interfaces:**
- Consumes: existing `@ApplicationIoScope CoroutineScope`.
- Produces: same `RecentPlaybackObserver` behavior under one explicit process async owner.

- [ ] **Step 1: Write a small RED construction test or static assertion**

The test must fail if `RecentPlaybackModule` constructs `CoroutineScope(SupervisorJob() + Dispatchers.IO)` itself.

- [ ] **Step 2: Inject `@ApplicationIoScope`**

Provider target:

```kotlin
fun provideRecentPlaybackObserver(
    repository: RecentChannelsRepository,
    @ApplicationIoScope scope: CoroutineScope,
): RecentPlaybackObserver = RecentPlaybackObserver(
    repository = repository,
    scope = scope,
    nowEpochMillis = System::currentTimeMillis,
)
```

Remove standalone SupervisorJob/Dispatchers imports from the module if no longer used.

- [ ] **Step 3: Run observer tests**

Verify:

- successful first frame still records once;
- repository failure remains ancillary;
- cancellation is still propagated where applicable;
- no playback control path waits on recent persistence.

- [ ] **Step 4: Commit independently**

```bash
git add app/tv/src/main/kotlin/app/muxtv/di/RecentPlaybackModule.kt app/tv/src/test
git commit -m "refactor(app): reuse application IO scope for recent playback"
```

---

# Task 5: Promote the static architecture contract into Fast verification

**Files:**
- Modify: `tools/verify-local.ps1`
- Test: existing verify-local script contracts plus architecture checker tests

**Interfaces:**
- Consumes: now-GREEN repository module graph.
- Produces: cheap local/CI guard against reintroducing feature→adapter edges.

- [ ] **Step 1: Confirm the repository checker is GREEN before wiring it into Fast**

```powershell
pwsh -NoProfile -File .\tools\architecture\Test-ModuleDependencyBoundaries.ps1
```

Expected: exit 0 and zero violations.

- [ ] **Step 2: Add the checker to Fast validation**

Invoke it before expensive Gradle/device work. Do not add a separate GitHub Actions workflow.

- [ ] **Step 3: Run verify-local Fast on the executable host**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
```

Expected before claiming completion: exit 0 with architecture contract included.

- [ ] **Step 4: Commit**

```bash
git add tools/verify-local.ps1 tools/architecture
git commit -m "test(architecture): enforce feature adapter boundaries"
```

---

# Task 6: Synchronize durable architecture truth

**Files:**
- Modify: `.work/ARCHITECTURE.md` only if wording needs a precise Media3 surface-adapter exception
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Modify: relevant issue bodies/comments

**Interfaces:**
- Consumes: accepted code and fresh validation evidence.
- Produces: durable truth that matches the implementation.

- [ ] **Step 1: Record accepted dependency graph**

Document that provider-neutral feature modules consume stable APIs and that Media3 rendering mechanics are adapter-owned.

- [ ] **Step 2: Remove stale dual-seek wording if any remains**

Current accepted state must say service-owned semantic seek authority is complete; #132 owns only measurement/back-buffer/cache residuals.

- [ ] **Step 3: Record Sources boundary**

Sources UI owns presentation/confirmation; source lifecycle/scheduling/storage remain behind stable catalog ports.

- [ ] **Step 4: Record verification SHA/evidence only after actual execution**

Do not mark the train GREEN from static planning alone.

- [ ] **Step 5: Commit truth sync separately**

```bash
git add .work docs
git commit -m "docs(architecture): sync accepted module ownership boundaries"
```

---

# Final verification checklist

Before any merge/completion claim after runner availability:

- [ ] static module boundary checker GREEN;
- [ ] Sources tests GREEN;
- [ ] source refresh lease/publication tests unchanged and GREEN;
- [ ] Player API/media3/feature tests GREEN;
- [ ] one service-owned ExoPlayer still proven by source/static contract;
- [ ] one service-owned semantic seek mutation path still proven;
- [ ] no `feature:* -> core:database` edge;
- [ ] no `feature:* -> catalog:sync` edge;
- [ ] no `feature:* -> player:media3` edge;
- [ ] no new AVD identity;
- [ ] Fast validation GREEN on exact head;
- [ ] API26/API36 device evidence GREEN for the Player integration change;
- [ ] no raw secret/locator/header value added to API/UI/diagnostics;
- [ ] `.work` truth matches accepted implementation.

# Explicit non-goals

- no repository-wide Clean Architecture rewrite;
- no new `domain` module merely for symmetry;
- no migration from Hilt;
- no replacement of WorkManager;
- no replacement of Room3;
- no alternate player engine;
- no new focus/navigation framework;
- no FTS/database performance tuning;
- no buffer/cache tuning;
- no provider/catch-up implementation inside this refactor;
- no additional Android TV emulator identity.
