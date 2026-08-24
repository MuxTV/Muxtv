# MuxTV module-boundary and runtime-ownership audit — 2026-08-24

## Purpose

This is a runner-free static audit of the accepted `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` architecture. It identifies dependency-direction violations and runtime ownership risks without changing production code, Gradle configuration, Room schema, Android manifests, or GitHub workflows.

The audit is intentionally evidence-led. A wide dependency is not automatically a defect; it becomes a defect when it contradicts an accepted boundary or creates a second authority for mutable runtime state.

## Current execution constraints

- GitHub Actions and the self-hosted runner are intentionally unavailable at this checkpoint.
- Do not edit `.github/workflows/**` while that freeze remains in force.
- Do not open a PR solely to publish this runner-free preparation branch because the current universal PR validation workflow allocates/queues the self-hosted runner for every same-repository PR to `main`.
- Do not touch PR #189, PR #190, or `.github/ui-characterization/run.request`.
- Repository-owned Android TV AVD identities remain exactly:
  - `MuxTV_TV_OLD_API26`
  - `MuxTV_TV_CURRENT_API36`
- This document authorizes no production refactor by itself.

---

## Accepted dependency rules used as the audit oracle

`.work/ARCHITECTURE.md` defines the intended direction:

```text
TV UI / Feature reducers
          ↓ intents, immutable state
Application use cases / coordinators
          ↓ domain ports
Domain models, policies and algorithms
          ↓ repository/engine/provider contracts
Adapters
          ↓
Room · OkHttp · Media3 · WorkManager · platform integrations
```

Relevant normative rules:

1. feature modules do not access DAO/network/player implementation directly;
2. Room/SQLite stay behind storage/repository ports;
3. playback engine mechanics stay behind the service/controller boundary;
4. Activity/Compose do not own or release ExoPlayer;
5. one component owns each retry class;
6. source/EPG refresh is generation/lease safe;
7. stale work cannot publish over newer active truth;
8. app/tv is allowed to be the composition root that wires ports to adapters.

The audit distinguishes:

- **composition dependency** — expected in `app:tv`;
- **port dependency** — desired from feature/domain consumers;
- **adapter dependency** — acceptable inside adapter/orchestration modules;
- **boundary leak** — feature/domain code directly consumes adapter implementation types;
- **authority duplication** — two components can mutate the same runtime truth without a shared generation/lease/serialization owner.

---

# 1. Module graph checkpoint

## 1.1 Root modules

The repository currently contains:

```text
app:tv

core:common
core:model
core:database
core:designsystem
core:ui
core:testing
core:network
core:credentials

catalog:api
catalog:ingest
catalog:importer
catalog:refresh
catalog:sync
catalog:onboarding

player:api
player:media3
player:fake

feature:home
feature:channels
feature:guide
feature:search
feature:player
feature:sources
feature:doctor
feature:settings

benchmark:macrobenchmark
benchmark:jvm
```

## 1.2 Healthy inner/core boundaries

### `core:common`

Pure Kotlin library. It has no Android/storage/player/UI adapter dependency.

**Classification:** clean inner boundary.

### `core:model`

Pure Kotlin library depending only on `core:common`.

**Classification:** clean inner boundary.

### `catalog:api`

Pure Kotlin API module depending on `core:common`, `core:model`, Coroutines Core and Paging Common.

This is already the natural stable catalog/provider port surface.

**Classification:** clean port boundary.

### `player:api`

Pure Kotlin API module depending on `core:common` and Coroutines Core. It already owns provider-neutral playback/session/recovery/capability contracts.

**Classification:** clean port boundary and preferred home for additional UI-facing playback commands/state when those contracts are Media3-neutral.

### `core:database`

Android/Room adapter depending on `catalog:api`, `core:common`, `core:model`, Room/Paging/Coroutines/Tracing. It does not depend on feature UI or `player:media3`.

**Classification:** dependency direction is acceptable for the current adapter implementation.

---

# 2. Feature dependency audit

## 2.1 Healthy feature modules

The following feature modules consume stable APIs/design primitives without directly importing Room or Media3 implementation modules:

- `feature:home`
- `feature:channels`
- `feature:guide`
- `feature:search`
- `feature:doctor`
- `feature:settings`

This is the desired direction:

```text
feature
  ↓
catalog:api / player:api / designsystem / ui
```

No architecture correction is justified for these modules from this audit.

---

# 3. Finding A — `feature:player` leaks Media3 implementation into UI

**Severity:** high architectural debt, not currently a duplicate-player correctness bug.

## 3.1 Static evidence

`feature:player/build.gradle.kts` directly depends on:

```text
player:api
player:media3
media3-ui-compose
```

`PlayerRoute.kt` exposes adapter types in feature-level state/interfaces, including:

```text
MediaController
MuxTvMediaControllerConnector
MediaControllerOperationException
MediaControllerOperationFailure
```

`PlayerSurfaceContent.kt` imports raw Media3 and `player:media3` implementation types including:

```text
MediaController
Player
PlaybackException
PlayerSurface
Media3TrackController
PlaybackSeekPolicy
PlaybackSeekRequest
PlaybackSeekResult
PlaybackSeekRejectReason
SeekControllerState
```

This contradicts the accepted rule that feature modules must not consume player implementation directly.

## 3.2 What is *not* broken

The audit does **not** find a second ExoPlayer owner.

`MuxTvPlaybackService` currently:

- constructs the single ExoPlayer;
- owns the MediaSession;
- owns setup generation and cancellation;
- owns recovery orchestration;
- owns active candidate/setup generation;
- owns the semantic seek controller;
- performs the actual `player.seekTo(...)` mutation;
- releases player/session in service destruction.

Therefore PR #175's single service-owned player/seek authority remains valid. Do not reopen #132's already-completed authority consolidation.

## 3.3 Actual risk

The problem is coupling and future change amplification:

```text
Compose feature
   ↓ knows MediaController/Media3 session mechanics
player:media3 adapter
   ↓
service-owned player
```

Consequences:

1. feature tests require Media3-shaped test seams even for provider-neutral UI behavior;
2. alternate playback adapter experiments cannot reuse the feature without Media3 awareness;
3. Media3 API changes can force feature churn;
4. semantic track/seek state ownership is harder to reason about because UI sees both provider-neutral and Media3-specific concepts;
5. the accepted `player:api` abstraction is bypassed on the route/surface path.

## 3.4 Preferred correction

Do **not** introduce a giant playback-domain rewrite.

Use two narrow seams:

### Seam 1 — provider-neutral playback session gateway in `player:api`

Feature-visible operations/state should not mention MediaController:

```kotlin
interface PlaybackUiSession {
    val state: StateFlow<PlaybackSessionState>
    val capabilities: StateFlow<PlaybackCapabilities>

    suspend fun start(request: PlaybackStartRequest): PlaybackStartResult
    suspend fun seek(request: PlaybackSeekCommand): PlaybackSeekOutcome
    suspend fun selectTrack(selection: PlaybackTrackSelection): PlaybackTrackSelectionResult
}
```

Exact names may be refined during TDD, but accepted public types must stay Media3-neutral.

### Seam 2 — Media3 surface renderer stays adapter-owned

`PlayerSurface` inherently uses Media3 player/controller mechanics. Do not pretend it is platform-neutral by wrapping raw `Any` or leaking Player through an interface.

Preferred options, in order:

1. keep the Media3-specific surface Composable in an adapter-owned module and pass feature-owned overlay/content callbacks around it;
2. if module boundaries require it, introduce a **small** `player:ui-media3` adapter module rather than putting Media3 types back into `feature:player`;
3. app composition wires the adapter surface and the provider-neutral feature controls.

Do not create a generic plugin renderer abstraction before a second renderer exists.

## 3.5 Acceptance for a future correction PR

- `feature:player` has no Gradle dependency on `player:media3`;
- no `androidx.media3.*` imports remain in provider-neutral feature state/command files;
- one service-owned ExoPlayer remains unchanged;
- one service-owned semantic seek authority remains unchanged;
- raw locator/header values remain outside UI state/semantics;
- current Player focus/overlay behavior remains equivalent;
- exact API26/API36 validation is required before acceptance because this changes playback/UI integration;
- physical-device codec/HDR claims remain outside this refactor.

---

# 4. Finding B — `feature:sources` consumes sync/refresh/database/credential adapters directly

**Severity:** high architectural debt; stronger dependency inversion violation than Finding A.

## 4.1 Static evidence

`feature:sources/build.gradle.kts` directly depends on:

```text
catalog:refresh
catalog:sync
core:credentials
core:database
core:designsystem
```

`SourcesRoute.kt` accepts and directly calls:

```text
SourceRefreshStore          // core:database
SourceRefreshScheduler      // catalog:sync
SourceRefreshPolicy         // core:database
SourceRefreshOverview       // core:database
SourceRefreshRunState       // core:database
```

The Composable observes the database-backed refresh stream and invokes WorkManager orchestration through the scheduler.

`SourceEntrySession.kt` additionally imports preparation/activation/cancellation contracts directly from `catalog:refresh`.

The app DI module then has to import both feature-local interfaces and adapter implementations to bridge them.

## 4.2 Why this matters

The current shape is effectively:

```text
feature:sources
  ├── Room-facing refresh store model
  ├── WorkManager scheduler
  ├── remote onboarding implementation contracts
  └── credential adapter dependency
```

This makes Sources UI aware of persistence and scheduling implementation details and conflicts with the architecture's intended UI → coordinator/port direction.

It also makes future provider work under #184 more expensive because provider capability/readiness APIs would have to coexist with existing database/scheduler-shaped feature contracts.

## 4.3 Preferred correction

Do not create another broad `domain` module.

Extend the already-existing `catalog:api` boundary with only the stable contracts that Sources genuinely consumes.

Conceptual minimal surface:

```kotlin
data class SourceOverview(...)

data class SourceRefreshPolicy(...)

enum class SourceRefreshState { ... }

interface SourceManagement {
    fun observeSources(): Flow<List<SourceOverview>>
    fun refreshNow(sourceId: String)
    suspend fun updateRefreshPolicy(policy: SourceRefreshPolicy)
    suspend fun removeRefreshPolicy(sourceId: String)
    suspend fun revokePlaybackApprovals(sourceId: String): SourceApprovalResetResult
}
```

Onboarding contracts that are already durable and user-facing should move to a stable catalog/onboarding API surface rather than remaining typed as `catalog:refresh` implementation details.

A concrete adapter in app/catalog orchestration can compose:

```text
SourceRefreshStore
SourceRefreshScheduler
PlaybackAccessPolicyResolver
DurableRemoteSourceOnboarding
```

and implement the stable port.

Then:

```text
feature:sources
       ↓
   catalog:api
       ↑
app composition / catalog adapters
       ↓
Room + WorkManager + credentials + network
```

## 4.4 What must remain unchanged

- durable prepare/activate/cancel semantics;
- exact-origin HTTP approval behavior;
- opaque credential references;
- refresh lease/run-token ownership;
- WorkManager scheduling semantics;
- previous-good catalog publication;
- cleanup/TTL behavior for prepared source onboarding.

This is dependency inversion, not a source lifecycle redesign.

## 4.5 Acceptance for a future correction PR

- `feature:sources` no longer depends on `core:database`, `catalog:sync`, `core:credentials`, or remote implementation-only `catalog:refresh` types;
- feature consumes stable port/state models only;
- no raw credentialRef, URL, token, header or exception string enters UI state;
- source refresh scheduling still uses the existing WorkManager owner;
- Room run-token lease remains the actual refresh authority;
- existing onboarding cleanup/recovery tests remain valid;
- UI behavior and focus semantics remain equivalent;
- no schema bump unless a separately justified product contract requires one.

---

# 5. Finding C — process async scope ownership is inconsistent

**Severity:** medium/low; lifecycle consistency debt, not an observed leak.

## 5.1 Existing preferred process scope

`AppInfrastructureModule` already owns:

```kotlin
@ApplicationIoScope
@Singleton
CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

`MuxTvApplication` uses this scope for credential-encrypted startup orchestration.

That is a coherent process-lifetime async owner.

## 5.2 Duplicate standalone scope

`RecentPlaybackModule` constructs another process-lifetime scope inside a singleton:

```kotlin
CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

and gives it to `RecentPlaybackObserver`.

The observer launches one ancillary persistence coroutine after first frame and correctly treats failures as non-fatal.

## 5.3 Classification

This is **not** currently evidence of an unbounded leak:

- the observer is singleton/process-lifetime;
- each launched operation is finite Room persistence;
- failures are bounded and cancellation is preserved.

However it creates unnecessary lifecycle fragmentation:

- process jobs are not under one explicit supervisor;
- test cancellation/control is less uniform;
- future shutdown/debug ownership is harder to inspect;
- duplicate scopes can proliferate if this pattern is copied.

## 5.4 Preferred correction

Inject the existing `@ApplicationIoScope` into `RecentPlaybackObserver` from app composition rather than creating a second standalone process scope.

Do not introduce a new global scope abstraction.

---

# 6. Source refresh ownership audit

**Verdict:** correctness ownership is strong; do not simplify away the DB lease.

## 6.1 Scheduling layer

`SourceRefreshScheduler` uses WorkManager:

- one immediate unique work name per source with `ExistingWorkPolicy.KEEP`;
- one periodic unique work name per source with `ExistingPeriodicWorkPolicy.UPDATE`;
- bounded exponential WorkManager backoff.

Immediate and periodic names are intentionally different, so WorkManager naming alone is not the concurrency authority.

## 6.2 Authority layer

`SourceRefreshWorker` creates a UUID run token and calls:

```text
SourceRefreshStore.tryAcquire(sourceId, runToken, startedAt, staleBefore)
```

`SourceRefreshDao.tryAcquire()` is transactional and only marks the source RUNNING if the prior run is absent/non-running/stale.

Completion is also transactional and requires the exact current `runToken`. It compares the current credential reference with the captured one and converts stale credential ownership into a superseded completion.

Therefore the true model is:

```text
WorkManager
  = scheduling / retry transport

Room runToken lease
  = per-source mutation authority
```

## 6.3 Import publication

`CatalogRevisionImporter` stages a new revision and activates durable refreshes only through a refresh-owner/credential guarded activation path. Cancellation performs best-effort staging discard in `NonCancellable` while preserving cancellation as the authoritative result.

## 6.4 Conclusion

No duplicate source-refresh write authority was identified.

Do not replace the Room lease with only WorkManager unique-work semantics.

---

# 7. EPG refresh + matching publication audit

**Verdict:** correctness race is guarded; possible duplicate compute should be measured, not guessed.

## 7.1 EPG refresh

EPG manual/startup/periodic WorkManager requests can have distinct unique-work names, but EPG refresh itself uses the same durable run-token lease pattern as catalog refresh.

## 7.2 Matching relation snapshot

Matching captures immutable relation truth:

```text
(epgSourceId,
 epgRevisionNumber,
 providerSourceId,
 catalogRevisionNumber)
```

It calculates matches against that snapshot.

## 7.3 Transactional publication

`EpgMatchingDao.replaceIfCurrent()` re-reads the active relation in a Room transaction before replacing match rows. If catalog or EPG revision changed during computation, publication returns `Superseded`.

Thus catalog refresh and EPG refresh may both request reconciliation, but stale calculated state cannot overwrite newer active truth.

## 7.4 Residual efficiency question

Two reconcilers can potentially perform duplicate CPU work for the same relation snapshot before one/both publish.

That is not a proven problem. Before adding a mutex/lease for matching, measure:

- reconcile duration at realistic EPG/channel counts;
- frequency of overlapping requests;
- superseded reconcile count;
- duplicate relation-snapshot compute count;
- startup impact.

A valid result is **no additional matching lock needed**.

---

# 8. Playback runtime ownership audit

**Verdict:** single player/seek ownership is currently correct.

## 8.1 Single mutable player owner

`MuxTvPlaybackService` owns:

- one ExoPlayer instance;
- MediaSession;
- playback setup generation;
- candidate recovery;
- callback generation gate;
- deadline and active jobs;
- active player listener;
- active seek generation;
- `PlaybackSeekController`;
- player release.

## 8.2 Seek convergence

Both custom MuxTV seek commands and MediaSession/native seek intents converge into the service's `handleSeekRequest()`.

The service validates:

- current generation token;
- command availability;
- live/non-live semantics;
- known duration;
- current position;
- controller acceptance.

Actual `player.seekTo(...)` remains inside the service-owned controller callback.

## 8.3 Consequence for roadmap truth

Any durable document still describing “dual seek ownership” as current debt is stale. Remaining #132 work is measurement/back-buffer/cache residual work only.

Do not reintroduce feature-owned direct `seekTo()` while fixing the feature/module boundary.

---

# 9. Composition root audit

`app:tv` currently imports many concrete adapters. That is expected because it is the Android application composition root.

`AppModule.kt` wires:

- database component ports;
- remote source access/refresher;
- durable onboarding;
- playback candidate resolver;
- playback controller connector;
- external playback lease registry.

The problematic signal is not that app knows adapters. The signal is that app must implement **feature-local** interfaces because the stable port belongs to the feature instead of the domain/API surface.

Future direction:

```text
app:tv
  wires API port ↔ concrete adapter

feature
  imports API port

feature-local adapter bridge
  disappears
```

---

# 10. Priority table

| Finding | Risk | Correctness now | Priority | Future owner |
|---|---|---:|---:|---|
| `feature:player` → Media3 implementation | high coupling / architecture drift | service ownership still correct | P1 | new child under #184, coordinated with #132/#33 |
| `feature:sources` → DB/WorkManager/credentials/refresh adapters | high coupling / provider evolution cost | source lifecycle still guarded | P1 | new child under #184 |
| duplicate process IO scope in RecentPlaybackModule | lifecycle/test ownership inconsistency | no observed leak | P2 | small app composition cleanup |
| source refresh scheduler + DB lease | none found | strong | preserve | existing refresh owners |
| catalog/EPG matching overlap | possible duplicate CPU | publication safe | measurement only | #27 / EPG owners |
| service player/seek owner | none found | strong | preserve | #132 residual only |

---

# 11. Static enforcement recommended after runner/Actions return

Before performing large dependency inversion, add a cheap host/static architecture contract so the same leakage does not return.

The contract should validate at minimum:

```text
feature:* must not depend on core:database
feature:* must not depend on catalog:sync
feature:* must not depend on core:credentials
feature:* must not depend on player:media3

exception:
Media3-specific rendering adapter module may depend on player:media3,
but provider-neutral feature state/commands may not.
```

Do **not** enforce a naïve rule that bans all Android libraries from features. Compose/UI are platform code by design.

The contract should report exact offending module edge and be runnable in Fast host validation. It does not need a device.

---

# 12. Remediation ordering

Do not mix these changes into #189 U0/U1 or #190 stack staging.

Recommended order after runner availability:

```text
U0/#189 evidence
   ↓
U1 shared UI correction
   ↓
#178/M0 measurement correctness
   ↓
architecture boundary static contract
   ↓
Sources dependency inversion
   ↓
Player API/surface dependency inversion
   ↓
process-scope cleanup
   ↓
normal exact-head host/device acceptance
```

Sources can be corrected before Player because it is mostly dependency inversion around stable refresh/onboarding behavior and does not require Media3 surface decisions.

Player correction must remain isolated because it touches playback/UI integration and requires stronger device acceptance.

---

# 13. Claims this audit does not make

This static audit does **not** prove:

- performance improvement from module cleanup;
- absence of all coroutine leaks;
- physical-TV playback compatibility;
- absence of vendor MediaCodec issues;
- Room concurrency performance;
- correctness of unexecuted future refactors.

It does establish from current source structure that:

1. two feature modules currently bypass accepted adapter boundaries;
2. source refresh has a durable Room lease owner beyond WorkManager naming;
3. EPG matching publication is generation/revision-safe;
4. the playback service remains the single mutable player/seek authority;
5. one duplicate process-lifetime IO scope is unnecessary ownership debt.

These facts are sufficient to create narrowly scoped remediation work without speculative redesign.
