# Immutable EPG Revisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Room schema v5 and a parser-backed staging store that publishes XMLTV guide data only through atomic immutable-revision activation while retaining the current and previous-good revisions.

**Architecture:** Reuse the proven catalog revision pattern rather than creating a second state framework. EPG source metadata owns an active revision pointer; parser records are written into revision-scoped channel/programme rows, remain invisible while `STAGING`, and become readable only after one Room transaction changes statuses and the source pointer. Failed or cancelled imports delete only their staging revision.

**Tech Stack:** Kotlin, Room 3, Android instrumentation tests, Kotlin coroutines, existing `StreamingXmltvParser`, existing self-hosted API 26/API 36 device harness.

## Global Constraints

- Database version advances exactly from 4 to 5 with an exported schema and explicit `MIGRATION_4_5`.
- No locator, URL query, raw XML, credential value, programme title or description may appear in exception/status diagnostics.
- EPG source access remains separate from M3U source access; Room may store only an opaque access reference and optional provider-source relation.
- Staging rows are never visible through active-guide queries.
- Activation is one transaction and retains exactly current plus previous-good revisions.
- An empty revision, a revision with zero resolved programmes, a cancelled import or a failed parser callback cannot replace the active guide.
- Offset-less programme times are never interpreted as UTC. This package counts/rejects unresolved times; source-zone resolution is a later refresh-layer package.
- Network fetch, gzip/zip decode, conditional GET, WorkManager scheduling, fuzzy channel matching, Guide/Search UI and catch-up binding are excluded.
- No Rust/UniFFI, alternate database, Paging or new state-management framework.

---

## File Map

**Create**

- `core/database/src/main/kotlin/app/muxtv/database/EpgSourceEntity.kt` — EPG source pointer and non-secret configuration.
- `core/database/src/main/kotlin/app/muxtv/database/EpgRevisionEntity.kt` — immutable revision lifecycle/statistics.
- `core/database/src/main/kotlin/app/muxtv/database/EpgChannelEntity.kt` — revision-scoped provider channel rows.
- `core/database/src/main/kotlin/app/muxtv/database/EpgProgrammeEntity.kt` — resolved revision-scoped programme rows and active query indices.
- `core/database/src/main/kotlin/app/muxtv/database/EpgRevisionDao.kt` — staging, activation, discard, retention and bounded active queries.
- `core/database/src/main/kotlin/app/muxtv/database/EpgRevisionStore.kt` — public typed storage boundary.
- `core/database/src/main/kotlin/app/muxtv/database/RoomEpgRevisionStore.kt` — DAO adapter.
- `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/EpgRevisionImporter.kt` — parser sink, bounded batching and failure cleanup.
- `core/database/src/androidTest/kotlin/app/muxtv/database/EpgRevisionDatabaseContractTest.kt` — atomicity/retention/visibility contracts.
- `core/database/src/androidTest/kotlin/app/muxtv/database/EpgMigration4To5Test.kt` — migration/schema preservation contract.
- `catalog/importer/src/test/kotlin/app/muxtv/catalog/importer/EpgRevisionImporterTest.kt` — parser-to-staging and cancellation contracts.

**Modify**

- `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt` — register four entities, DAO and version 5.
- `core/database/src/main/kotlin/app/muxtv/database/DatabaseMigrations.kt` — add exact v4→v5 DDL.
- `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt` — register migration and expose store.
- `core/database/schemas/app.muxtv.database.MuxTvDatabase/5.json` — generated Room schema.
- `catalog/importer/build.gradle.kts` — depend on `catalog:ingest` if not already present and add tests.

---

### Task 1: Lock the Room v5 schema and migration contract

**Interfaces**

- Produces `EpgSourceEntity`, `EpgRevisionEntity`, `EpgChannelEntity`, `EpgProgrammeEntity`.
- Produces `MIGRATION_4_5` and `MuxTvDatabase.epgRevisionDao()`.

- [ ] **Step 1: Write failing migration and schema tests**

The test creates a v4 database from the exported schema, inserts existing profile/source/catalog data, runs `MIGRATION_4_5`, validates the Room schema, and verifies all previous rows survive. It also checks these new tables and indices:

```text
epg_sources
epg_revisions
epg_channels
epg_programmes
index_epg_revisions_sourceId_status
index_epg_programmes_sourceId_revisionNumber_externalChannelId_startEpochMillis
index_epg_programmes_sourceId_revisionNumber_startEpochMillis_stopEpochMillis
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin --no-daemon --stacktrace --console=plain
```

Expected: compilation fails because v5 entities/DAO/migration do not exist.

- [ ] **Step 3: Implement entities and exact DDL**

`EpgSourceEntity`:

```kotlin
@Entity(tableName = "epg_sources", indices = [Index("providerSourceId")])
data class EpgSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
    val activeRevision: Long = 0,
)
```

`EpgRevisionEntity` uses composite primary key `(sourceId, revisionNumber)` and `STAGING/ACTIVE/RETAINED`. Statistics include accepted channels/programmes, skipped programmes, warning count and unresolved-time count.

`EpgChannelEntity` uses `(sourceId, revisionNumber, externalId)` and stores a bounded primary display name plus optional language/icon metadata needed by the next matching package.

`EpgProgrammeEntity` uses `(sourceId, revisionNumber, sequenceNumber)` and stores resolved `startEpochMillis`, optional `stopEpochMillis`, external channel ID, primary title/language, optional subtitle/description, and bounded normalized metadata. Diagnostic `toString()` exposes counts/presence only.

- [ ] **Step 4: Register Room v5 and export schema**

Update `MuxTvDatabase` to `version = 5`; add all entities and `abstract fun epgRevisionDao(): EpgRevisionDao`. Register `MIGRATION_4_5` in `MuxTvDatabaseFactory`.

- [ ] **Step 5: Run migration tests GREEN**

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin :core:database:test --no-daemon --stacktrace --console=plain
```

- [ ] **Step 6: Commit**

```text
feat: add Room v5 EPG revision schema
```

### Task 2: Implement staging invisibility and atomic activation

**Interfaces**

- Consumes the four v5 entities.
- Produces `EpgRevisionDao`, `EpgRevisionStore`, `RoomEpgRevisionStore`.

- [ ] **Step 1: Write failing database contracts**

Cover:

1. stage source/revision/channels/programmes;
2. active queries return no staging rows;
3. activation rejects zero resolved programmes;
4. activation changes previous ACTIVE→RETAINED, new STAGING→ACTIVE and source pointer in one transaction;
5. active now/next query sees only the new revision;
6. a third activation deletes only the oldest revision and retains current+previous;
7. discard deletes one staging revision without touching active rows;
8. foreign-key delete of an EPG source removes all EPG rows.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin --no-daemon --stacktrace --console=plain
```

- [ ] **Step 3: Implement DAO transaction**

Required typed result:

```kotlin
sealed interface EpgRevisionActivationResult {
    data class Activated(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val programmeCount: Int,
    ) : EpgRevisionActivationResult
    data object EmptyRevisionRejected : EpgRevisionActivationResult
    data object NotStaging : EpgRevisionActivationResult
}
```

Activation order inside `@Transaction`:

```text
count resolved staged programmes
read previous active pointer
mark previous ACTIVE as RETAINED
mark requested STAGING as ACTIVE with statistics
update epg_sources.activeRevision
prune channels/programmes/revisions except current+previous
return typed result
```

Any failed check rolls the transaction back.

- [ ] **Step 4: Implement bounded active projections**

Add:

```kotlin
suspend fun activeProgrammes(
    sourceId: String,
    externalChannelIds: List<String>,
    fromEpochMillis: Long,
    toEpochMillis: Long,
    limit: Int,
): List<EpgProgrammeEntity>
```

The query joins `epg_sources.activeRevision`; it never accepts an arbitrary revision from UI code.

- [ ] **Step 5: Run database contracts GREEN**

```powershell
.\gradlew.bat :core:database:connectedDebugAndroidTest --no-daemon --stacktrace --console=plain
```

- [ ] **Step 6: Commit**

```text
feat: add atomic EPG revision activation
```

### Task 3: Bind `StreamingXmltvParser` to bounded staging batches

**Interfaces**

- Consumes `StreamingXmltvParser`, `EpgRevisionStore`.
- Produces `EpgRevisionImporter.import(...) : EpgImportResult`.

- [ ] **Step 1: Write failing importer tests**

Use fake store + canonical XMLTV fixtures to prove:

- channels/programmes are flushed in configurable batches, default 250;
- only `XmltvTimestamp.Resolved` programmes are staged;
- unresolved timestamps increment `unresolvedTimeCount` and `skippedProgrammeCount` without UTC conversion;
- malformed timestamps remain parser warnings;
- successful parse activates after the final batch;
- parser failure, sink failure or cancellation calls `discardRevision` and never calls activate;
- result and exception diagnostics contain no XML payload/title/channel ID/URL/access reference.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :catalog:importer:test --no-daemon --stacktrace --console=plain
```

- [ ] **Step 3: Implement minimal importer**

```kotlin
class EpgRevisionImporter(
    private val parser: StreamingXmltvParser,
    private val store: EpgRevisionStore,
    private val batchSize: Int = 250,
) {
    suspend fun import(
        source: EpgSourceDefinition,
        input: InputStream,
        startedAtEpochMillis: Long,
        activatedAtEpochMillis: Long,
    ): EpgImportResult
}
```

The importer creates source + staging revision, forwards parser callbacks into bounded channel/programme buffers, flushes them, then activates. `finally` discards the staging revision unless activation committed.

- [ ] **Step 4: Run importer and fixture consumers GREEN**

```powershell
.\gradlew.bat :catalog:importer:test :core:testing:test --no-daemon --stacktrace --console=plain
```

- [ ] **Step 5: Commit**

```text
feat: stage XMLTV into immutable EPG revisions
```

### Task 4: Verify Android compatibility and repository integrity

- [ ] **Step 1: Run Full**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

- [ ] **Step 2: Run DeviceMatrix**

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch feat/epg-immutable-revisions `
  -SourceCommit <exact-head-sha> `
  -NoDaemon
```

Acceptance: API 26 (or explicit documented old-edge fallback) and API 36 execute non-zero migration/database cases sequentially.

- [ ] **Step 3: Inspect schema and evidence**

Verify exported `5.json`, migration report, active/retained counts, no skipped database test and no sensitive value in artifacts.

- [ ] **Step 4: Update PR and issue #28**

Record exact head, Full run, DeviceMatrix run, schema identity, migration outcome and remaining fetch/refresh/matching/UI packages.

- [ ] **Step 5: Squash merge**

```text
feat: add immutable EPG revisions
```

## Self-review

- Spec coverage: migration, atomic activation, previous-good retention, staging discard, parser binding, unresolved-time safety and API edge/current tests are assigned.
- Deliberate exclusions: fetch/decompression, WorkManager, matching and UI have no placeholder implementation in this package.
- Type consistency: importer consumes only `EpgRevisionStore`; UI cannot select revisions directly; active queries derive the revision from `epg_sources.activeRevision`.
- Complexity check: localized child tables, fuzzy matching and denormalized guide projections are deferred until a concrete consumer requires them; v5 stores the bounded primary subset needed for matching and now/next.
