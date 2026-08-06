# Provider Readiness Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define a provider-neutral readiness contract where a validated active live catalog is sufficient for `USABLE`, while catalog refresh attempts and EPG/secondary enrichment remain independent typed state that cannot silently downgrade an already usable provider.

**Architecture:** Add a pure Kotlin contract to `catalog:api`; do not add a provider protocol implementation, database schema, worker, scheduler, ViewModel workflow, or provider-specific heuristic. Readiness derives from the presence of an accepted active live-catalog revision. Latest sync/enrichment attempts are represented separately so failures preserve previous-good active state.

**Tech Stack:** Kotlin/JVM, `catalog:api`, existing `SourceId` from `core:common`, JUnit 4, Truth.

## Global Constraints

- Base exactly on accepted `main@ec2b7743183b227ef54c16989d061ae5d4775dee` while active #127/#128/#129 heads remain untouched.
- No Room entity/schema/migration change.
- No WorkManager/scheduler change.
- No Xtream/Stalker/Jellyfin implementation.
- No credential reference, URL, Authorization/Cookie/header or provider token in the readiness snapshot.
- `USABLE` means an accepted active live-catalog revision exists; EPG is not a prerequisite.
- Failed refresh/enrichment attempts never erase previous-good active catalog/EPG state in this model.
- 401/403-equivalent auth failure, 429-equivalent rate-limit, timeout and generic network failure remain distinct typed outcomes.
- Progress records trustworthy completed work only; provider-declared totals are intentionally absent.
- Because the self-hosted runner is unavailable, tests are authored before production code but no RED/GREEN execution claim is made until exact-head validation is possible.

---

### Task 1: Lock the public readiness semantics with JVM tests

**Files:**
- Create: `catalog/api/src/test/kotlin/app/muxtv/catalog/ProviderReadinessContractTest.kt`

**Interfaces:**
- Consumes: existing `app.muxtv.common.SourceId`.
- Produces test expectations for `ProviderReadinessSnapshot`, `ProviderActiveCatalog`, `ProviderCatalogSyncAttempt`, `ProviderSecondaryState`, `ProviderSecondaryAttempt`, `ProviderSyncFailure`, and `ProviderUsability`.

- [ ] **Step 1: Add a test proving active catalog alone makes a provider usable**

Construct a snapshot with an active catalog and `EPG` still pending. Assert `usability == USABLE`.

- [ ] **Step 2: Add a test proving EPG failure cannot downgrade a usable provider**

Construct a snapshot with an active catalog and a failed EPG attempt with no active EPG revision. Assert the provider remains `USABLE`.

- [ ] **Step 3: Add a test proving previous-good EPG survives a later failed attempt**

Use `ProviderSecondaryState(activeRevisionNumber = 7, latestAttempt = Failed(...))`; assert `hasActiveData == true` and active revision stays `7`.

- [ ] **Step 4: Add a test proving a failed live refresh preserves previous-good live catalog**

Use active catalog revision `4` plus `ProviderCatalogSyncAttempt.Failed`; assert usability remains `USABLE` and revision remains `4`.

- [ ] **Step 5: Add a test proving no active catalog means not usable even if EPG is ready**

Construct a snapshot with no active catalog and an active EPG revision; assert `NOT_USABLE`.

- [ ] **Step 6: Add failure-taxonomy tests**

Assert authentication required, rate-limited, timeout and network are distinct sealed variants. Assert rate-limit retry metadata is optional and non-negative when present.

- [ ] **Step 7: Add progress validation tests**

Assert completed pages/items cannot be negative and that the public progress object contains no total-count field.

- [ ] **Step 8: Add diagnostic-redaction tests**

Assert `toString()` exposes only coarse state/count/revision metadata and does not include source identifier or free-form provider data.

- [ ] **Step 9: When execution is available, verify RED**

Run:

```powershell
./gradlew.bat :catalog:api:test --tests app.muxtv.catalog.ProviderReadinessContractTest --no-daemon
```

Expected before production code: compile/test failure because readiness contract types do not exist.

- [ ] **Step 10: Commit the test-only contract**

```bash
git add catalog/api/src/test/kotlin/app/muxtv/catalog/ProviderReadinessContractTest.kt
git commit -m "test: define provider readiness contract (#112)"
```

### Task 2: Implement the minimal provider-neutral public model

**Files:**
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/ProviderReadiness.kt`

**Interfaces:**
- Produces:
  - `enum class ProviderUsability { NOT_USABLE, USABLE }`
  - `data class ProviderActiveCatalog(revisionNumber: Long, channelCount: Int, activatedAtEpochMillis: Long)`
  - `data class ProviderSyncProgress(completedPages: Int, discoveredItems: Int)`
  - `sealed interface ProviderSyncFailure`
  - `sealed interface ProviderCatalogSyncAttempt`
  - `sealed interface ProviderSecondaryAttempt`
  - `data class ProviderSecondaryState(activeRevisionNumber: Long?, latestAttempt: ProviderSecondaryAttempt)`
  - `data class ProviderReadinessSnapshot(sourceId: SourceId, activeCatalog: ProviderActiveCatalog?, latestCatalogAttempt: ProviderCatalogSyncAttempt, epg: ProviderSecondaryState)`

- [ ] **Step 1: Implement active catalog value validation**

Require revision number > 0, channel count > 0 and activation epoch >= 0.

- [ ] **Step 2: Implement bounded truth-based progress**

Store only `completedPages` and `discoveredItems`; both must be >= 0. Do not add provider total/progress percentage.

- [ ] **Step 3: Implement typed sync failures**

Use sealed variants:

```kotlin
sealed interface ProviderSyncFailure {
    data object AuthenticationRequired : ProviderSyncFailure
    data class RateLimited(val retryAfterEpochMillis: Long?) : ProviderSyncFailure
    data object Timeout : ProviderSyncFailure
    data object Network : ProviderSyncFailure
    data object InvalidContent : ProviderSyncFailure
    data object Storage : ProviderSyncFailure
    data object Internal : ProviderSyncFailure
}
```

`RateLimited.retryAfterEpochMillis`, when non-null, must be >= 0.

- [ ] **Step 4: Implement separate latest catalog-attempt state**

Represent `Idle`, `Running(progress)`, `Succeeded(revisionNumber)`, `Failed(failure)`, `Cancelled`, and `Superseded`. The attempt does not own or clear `activeCatalog`.

- [ ] **Step 5: Implement reusable secondary/enrichment state**

Represent active previous-good revision separately from latest attempt. `hasActiveData` derives only from `activeRevisionNumber != null`.

- [ ] **Step 6: Derive provider usability solely from active live catalog**

`ProviderReadinessSnapshot.usability` must return `USABLE` iff `activeCatalog != null`. EPG state must not participate.

- [ ] **Step 7: Redact diagnostics**

Override snapshot/state diagnostic strings where necessary so no `SourceId` value or future provider free-form values are emitted. Keep only revision/count/enum-like state.

- [ ] **Step 8: When execution is available, verify GREEN**

Run:

```powershell
./gradlew.bat :catalog:api:test --tests app.muxtv.catalog.ProviderReadinessContractTest --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Run the whole pure Kotlin lane**

```powershell
./gradlew.bat :catalog:api:test :core:common:test :core:model:test --no-daemon
```

Expected: PASS.

- [ ] **Step 10: Commit production contract**

```bash
git add catalog/api/src/main/kotlin/app/muxtv/catalog/ProviderReadiness.kt
git commit -m "feat: add provider readiness contract (#112)"
```

### Task 3: Document ownership and integration rules

**Files:**
- Modify: issue #112 discussion only; no runtime ownership file is required in this package.

**Interfaces:**
- Consumes: accepted immutable source revisions, separate EPG lifecycle, current credential boundary.
- Produces: explicit future-adapter rules for later provider implementations.

- [ ] **Step 1: Record that this model is descriptive, not a second scheduler/state machine**

Future adapters publish snapshots from their own durable run-token/revision stores. Stale-run rejection remains in existing persistence/activation ownership; this API must not recreate that mechanism in memory.

- [ ] **Step 2: Record failure mapping guidance**

401/403 -> `AuthenticationRequired`; 429 -> `RateLimited`; timeout -> `Timeout`; other transport I/O -> `Network`; malformed complete payload -> `InvalidContent`; durable publication failure -> `Storage`.

- [ ] **Step 3: Record readiness mapping**

A successful live catalog activation immediately permits TV usage. EPG can be pending/failed independently, and a failed later catalog refresh leaves the previous active catalog usable.

- [ ] **Step 4: Do not close #112 yet**

Keep #112 open until at least one future adapter or provider-neutral integration layer consumes the contract and demonstrates durable secondary attempt history without coupling live activation to EPG completion.

## Acceptance After Runner Returns

1. Exact branch head passes `:catalog:api:test` and pure Kotlin lane.
2. Full host acceptance confirms no compile/API regression.
3. No Room schema artifact changes.
4. No WorkManager, DI, Android manifest, provider protocol or credential-storage changes.
5. Static API review confirms no provider-total percentage or secret-bearing field.
6. #112 remains open as an integration umbrella until a real adapter consumes the contract.
