# C03 Production Room Adaptation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove and, only after proof, adopt a production Room representation that reuses immutable catalog/search payloads across source revisions while retaining MuxTV atomic publication and refresh ownership safety.

**Architecture:** Keep production schema v10 unchanged through the design/contract and production-equivalent Room-candidate evidence phases. Add stable logical identity/content hashing at the importer boundary, then exercise a debug-only Room candidate whose source/revision/ownership/read/search/cleanup semantics match the proposed production shape. Only if correctness, write/WAL, read/search, storage, and cleanup evidence pass the adoption gate, integrate the candidate into `MuxTvDatabase` and add migration 10→11.

**Tech Stack:** Kotlin, Room3, SQLite WAL, Android instrumentation tests, Android TV API26/API36 hosted emulator workflows, existing MuxTV measurement JSON/evidence conventions.

**Spec:** `docs/superpowers/specs/2026-09-09-c03-production-room-adaptation-design.md`

## Global Constraints

- Baseline is `main@1a1af7b5f7d8d6f7d8255463bc4dc81ea4c5412a`.
- C03 evidence disposition is `ADAPT`, not automatic adoption.
- Do not rerun or relabel #354 benchmark evidence.
- Do not copy the benchmark-only `c03_b_*` SQLite schema into production.
- Do not change production Room schema before production-equivalent Room evidence proves the representation necessary and viable.
- Preserve immutable staging → guarded atomic publication → previous-good retention.
- Preserve credential, refresh-run owner/generation, and cancellation safety.
- Stable logical identity must never depend on locator/query/token/credential data.
- Correctness evidence precedes performance conclusions.
- Do not mix Xtream, playback/player, EPG policy, or UI changes into #364.
- Production adoption requires actual Room evidence and full API26/API36 migration/device qualification.

---

### Task 1: Stable logical identity and versioned content hashes

**Files:**
- Modify: `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactory.kt`
- Create: `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogEntryPayloadFingerprint.kt`
- Modify: `catalog/importer/src/test/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactoryTest.kt`
- Create: `catalog/importer/src/test/kotlin/app/muxtv/catalog/importer/CatalogEntryPayloadFingerprintTest.kt`

**Interfaces:**
- Produces: `CatalogEntryIdentity.logicalChannelId: String` in addition to legacy physical ids.
- Produces: `CatalogEntryPayloadFingerprint(contentHash, searchContentHash)` through `CatalogEntryPayloadFingerprinter.fingerprint(entry, identity)`.
- Existing legacy `providerChannelId` / `streamVariantId` byte-compatible behavior remains unchanged during evidence phase.

- [ ] **Step 1: Write failing stable-identity tests**

Add tests proving:

```kotlin
val rev1 = factory.create(entry, "source-a", revisionNumber = 1, ordinal = 1)
val rev2 = factory.create(entry, "source-a", revisionNumber = 2, ordinal = 99)
assertThat(rev1.logicalChannelId).isEqualTo(rev2.logicalChannelId)
```

and:

```kotlin
val tokenA = entry(locator = "https://stream.invalid/live?token=A")
val tokenB = entry(locator = "https://stream.invalid/live?token=B")
assertThat(factory.create(tokenA, "source-a", 1, 1).logicalChannelId)
    .isEqualTo(factory.create(tokenB, "source-a", 2, 1).logicalChannelId)
```

Also prove source scoping and that duplicate entries may share logical identity while physical revision ids remain distinct.

- [ ] **Step 2: Run importer unit tests and record RED**

Run:

```bash
./gradlew :catalog:importer:testDebugUnitTest --tests '*CatalogEntryIdentityFactoryTest*' --no-daemon
```

Expected: compile/test RED because `logicalChannelId` does not exist.

- [ ] **Step 3: Implement minimal logical identity**

Domain-separate SHA-256 using existing safe `providerKey`:

```text
catalog-logical-v1 | sourceId | providerKey
```

Do not use revision, ordinal, locator, headers, query, or credentials.

- [ ] **Step 4: Write failing fingerprint tests**

Prove:

- identical payload → identical `contentHash` and `searchContentHash`;
- reorder/revision/ordinal do not affect either hash;
- locator/token change → same logical identity, changed `contentHash`, unchanged `searchContentHash`;
- group/name/number change → changed search hash;
- null and empty serialize distinctly where the persisted model distinguishes them;
- fingerprints and `toString()` never expose raw locator/token data.

- [ ] **Step 5: Run fingerprint tests and record RED**

```bash
./gradlew :catalog:importer:testDebugUnitTest --tests '*CatalogEntryPayloadFingerprintTest*' --no-daemon
```

Expected: compile RED because production fingerprinter types do not exist.

- [ ] **Step 6: Implement minimal versioned canonical hashing**

Use tagged/length-delimited UTF-8 fields with explicit null markers. `contentHash` includes all persisted catalog/playback payload fields; `searchContentHash` includes canonical identity + raw name/group/number only.

- [ ] **Step 7: Run importer unit suite GREEN**

```bash
./gradlew :catalog:importer:testDebugUnitTest --no-daemon
```

- [ ] **Step 8: Commit**

```bash
git add catalog/importer/src/main catalog/importer/src/test
git commit -m "feat: add stable catalog payload fingerprints"
```

---

### Task 2: Production-equivalent Room candidate schema without production migration

**Files:**
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionCandidateDatabase.kt`
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionCandidateEntities.kt`
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionCandidateDao.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/C03ProductionCandidateCorrectnessTest.kt`
- Test-only dependency wiring only if required by the existing debug/androidTest source-set graph.

**Interfaces:**
- Candidate Room database owns source, revision, refresh-owner, immutable catalog payload, immutable search payload, revision membership, and provider FTS proof tables.
- Candidate DAO exposes `beginRevision`, bounded `stageBatch`, `activateIfRefreshOwnerMatches`, `discardRevision`, active digest/browse/search probes, and bounded orphan compaction.
- Production `MuxTvDatabase` remains version 10 and unchanged.

- [ ] **Step 1: Write compile-RED Room contract**

The androidTest creates `C03ProductionCandidateDatabase` with Room and expects the DAO API described above.

- [ ] **Step 2: Run focused androidTest compile and record RED**

```bash
./gradlew :core:database:compileDebugAndroidTestKotlin --no-daemon
```

Expected: RED due missing candidate Room types.

- [ ] **Step 3: Add minimal candidate entities**

Implement production design fields and indexes, not benchmark `c03_b_*` table names. Keep payload immutable; no update methods for payload/search payload rows.

- [ ] **Step 4: Add candidate DAO staging transaction**

Stage algorithm must use `INSERT IGNORE` + immutable-equivalence verification for payload/search payload rows, then insert membership/order.

- [ ] **Step 5: Add guarded activation/discard transactions**

Activation must check credential + one RUNNING run token before pointer switch. Superseded activation transactionally discards staging membership and leaves previous active unchanged.

- [ ] **Step 6: Run focused correctness test GREEN**

Use in-memory Room first; do not record performance from in-memory DB.

- [ ] **Step 7: Assert production schema did not change**

```bash
./gradlew :core:database:testDebugUnitTest --tests '*CurrentDatabaseMigrationsTest*' --no-daemon
```

Expected: schema version remains 10 and existing migration chain stays GREEN.

- [ ] **Step 8: Commit**

```bash
git commit -m "test: add production-equivalent C03 Room candidate"
```

---

### Task 3: Correctness matrix before performance

**Files:**
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionScenarioFixture.kt`
- Expand: `core/database/src/androidTest/kotlin/app/muxtv/database/C03ProductionCandidateCorrectnessTest.kt`
- Reuse current production A through `RoomSourceRevisionStore`.

**Interfaces:**
- Scenario fixture returns baseline/incoming items and an expected active digest/count/order.
- Correctness runner can execute A and B but does not yet compare performance.

- [ ] **Step 1: RED tests for content scenarios**

Add deterministic assertions for:

```text
delta-0
delta-1
delta-10
delta-100
reorder
remove-10
token-churn
```

For each A/B result assert active digest/count equality and previous-good digest/count equality.

- [ ] **Step 2: RED tests for identity/duplicate semantics**

Assert logical identity is stable across reorder and token churn, duplicate multiplicity is preserved, and order equals incoming membership order.

- [ ] **Step 3: RED tests for failed/partial staging**

Inject a staging failure after at least one batch. Assert active digest is unchanged and staged membership/payload becomes unreachable and compactable.

- [ ] **Step 4: RED test for stale run owner/generation**

Stage under token A, replace RUNNING owner with token B, attempt activation with A. Expect `Superseded`, no active pointer change, no staging membership left.

- [ ] **Step 5: RED test for cancellation**

Cancel after partial staging, run existing importer-style non-cancellable discard boundary, assert candidate cannot later activate and previous-good remains active.

- [ ] **Step 6: Make only correctness support changes required for GREEN**

Do not tune indexes/performance until the entire correctness matrix passes.

- [ ] **Step 7: Run focused correctness matrix GREEN**

```bash
./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.C03ProductionCandidateCorrectnessTest --no-daemon
```

- [ ] **Step 8: Commit**

```bash
git commit -m "test: prove C03 Room candidate correctness"
```

---

### Task 4: File-backed A/B Room evidence and write accounting

**Files:**
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionRoomMeasurementRunner.kt`
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionRoomMeasurementModel.kt`
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/C03ProductionRoomMeasurementJsonWriter.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/C03ProductionRoomMeasurementTest.kt`

**Interfaces:**
- A opens current `MuxTvDatabase` with WAL.
- B opens `C03ProductionCandidateDatabase` with WAL.
- Both use identical deterministic 10k scenario inputs.
- Mutation counters are collected in separate untimed audit passes so triggers do not inflate timed WAL evidence.

- [ ] **Step 1: RED report-contract test**

Require report fields for exact source commit, method version, device identity, scenario/variant, correctness digest/count, previous-good, logical identity digest, row breakdown, WAL/DB state, stage/publication timings, cleanup timings, and redaction status.

- [ ] **Step 2: Implement threshold-free file-backed runner**

Do not copy `RefreshDeltaDatabaseMeasurementRunner` candidate schema. Reuse only measurement conventions (fresh DB, WAL, warmup/measured iteration shape, separate audit pass).

- [ ] **Step 3: Record mutation categories**

At minimum:

```text
revision metadata
source pointer/status
membership
catalog payload
search payload/provider search documents
canonical publication rows
cleanup deletes
```

- [ ] **Step 4: Run one smoke iteration locally/hosted**

Use one warmup + one measured iteration only as harness validation; do not use smoke numbers for disposition.

- [ ] **Step 5: Commit**

```bash
git commit -m "test: measure production C03 Room write elision"
```

---

### Task 5: Active browse/search and cleanup/storage evidence

**Files:**
- Expand candidate DAO active browse/search queries.
- Expand `C03ProductionRoomMeasurementRunner.kt`.
- Reuse query-plan patterns from `CatalogDatabaseMeasurementRunner`.
- Add correctness tests for active search visibility during staging.

**Interfaces:**
- A/B active browse/search return the same deterministic result sets.
- B provider search eligibility is gated by ACTIVE membership.

- [ ] **Step 1: RED active browse agreement test**

Assert candidate B matches A for active channel count, canonical ids, grouping/number fields, variant multiplicity, and deterministic order.

- [ ] **Step 2: RED active search publication test**

Stage a revision containing search-visible changes without activating it. Assert public search still returns the previous active result. Activate and assert the new result appears.

- [ ] **Step 3: RED repeated-revision storage test**

Run repeated delta-0, delta-1, reorder, and token-churn revisions. After retention cleanup + bounded orphan compaction, assert payload/search counts are bounded by reachable active/retained content rather than refresh count.

- [ ] **Step 4: Implement indexes/query shapes until correctness is GREEN**

Do not add a materialized active projection unless measured evidence later justifies it.

- [ ] **Step 5: Add timed read/search phases**

Measure active browse/playback summary and the existing representative search candidate/summary scenarios. Capture `EXPLAIN QUERY PLAN` for B.

- [ ] **Step 6: Add cleanup and memory observations**

Record cleanup rows/time and Android memory/GC information only where the current device harness can collect it deterministically; mark unavailable fields explicitly rather than guessing.

- [ ] **Step 7: Commit**

```bash
git commit -m "test: measure C03 active reads and cleanup"
```

---

### Task 6: Focused hosted production-candidate evidence

**Files:**
- Create: `.github/workflows/c364-production-room-adaptation.yml`
- Create or reuse a focused shell/PowerShell runner under `tools/ci/`.
- Do not modify `.github/workflows/c03-refresh-delta.yml` to repurpose #354 evidence.

**Interfaces:**
- Manual/PR focused evidence runs the new C364 Room candidate test only.
- Evidence artifact name contains exact head/run/attempt.

- [ ] **Step 1: Add workflow contract RED check if repository conventions require one**

- [ ] **Step 2: Run correctness-only candidate lane first**

Both candidate correctness and redaction must be GREEN before the full performance matrix is accepted.

- [ ] **Step 3: Run canonical API36 A/B evidence**

Use 1 warmup + at least 5 measured iterations per regular scenario. Preserve raw per-iteration samples and aggregate distributions.

- [ ] **Step 4: Evaluate adoption gate**

Do not use one noisy median. Compare correctness first, mutation/WAL reductions, read/search distributions, cleanup/storage bounds, and limitations.

- [ ] **Step 5: Record disposition in #364 / PR**

If evidence is inadequate, stop before migration and record `DEFER`/`REJECT` with artifact hashes. Only a supported candidate proceeds to Task 7.

---

### Task 7: Production adoption and migration 10→11 — conditional on Task 6 evidence

**Files:**
- Add production entities in `core/database/src/main/kotlin/app/muxtv/database/`.
- Modify `MuxTvDatabase.kt`.
- Add `MIGRATION_10_11` in the established migration location.
- Modify `CurrentDatabaseMigrations.kt` to version 11.
- Modify `RoomSourceRevisionStore.kt` and `SourceRevisionDao.kt` (or split focused DAOs if file size/ownership warrants it).
- Modify active browse/playback/search DAOs to membership→payload read paths.
- Add exported `core/database/schemas/.../11.json` through the normal Room schema task.
- Add migration/current-schema/androidTests.

**Interfaces:**
- Public `SourceRevisionStore` activation/cancellation contract remains source-compatible unless a contract test proves a required additive field.
- Existing consumers continue to see the same catalog/search projections.

- [ ] **Step 1: Write migration RED tests first**

Cover:

- current active + retained source;
- duplicate provider-key occurrences;
- locator/tokenized values treated as content, not identity;
- provider search rows;
- stale/incomplete staging recovery;
- foreign keys and active pointer preservation.

- [ ] **Step 2: Verify RED against schema 10**

Run unit migration-chain test and device migration compile/test before adding migration implementation.

- [ ] **Step 3: Add production entities and migration 10→11**

Transform old physical revision rows into immutable payload + memberships. Never destructively recreate the database.

- [ ] **Step 4: Adapt staging/activation/discard**

Port the already-proven candidate transaction semantics, retaining credential/run-token ownership checks at the same publication boundary.

- [ ] **Step 5: Adapt active browse/playback/search queries**

Port the measured candidate query shapes and required indexes, not an unmeasured rewrite.

- [ ] **Step 6: Run full database unit/android tests GREEN**

- [ ] **Step 7: Generate/verify exported Room schema**

- [ ] **Step 8: Commit**

```bash
git commit -m "feat: adopt immutable catalog payload reuse in Room"
```

---

### Task 8: Full migration/device qualification and exact-head review

**Files:**
- Existing `.github/workflows/database-migration-device-matrix.yml` should run unchanged unless a narrowly justified test-path addition is needed.
- Update #364/PR evidence summary only after runs finish.

**Interfaces:**
- Canonical migration matrix: Android TV API26 x86 and API36 x86_64.
- Exact-head validation includes focused C364 evidence, Hosted CI/Validation, current catalog measurement correctness, and database migration matrix.

- [ ] **Step 1: Run migration matrix API26/API36**

Require both devices GREEN with schema validation and data-preservation contracts.

- [ ] **Step 2: Run focused C364 evidence on exact head**

Confirm adopted production Room behavior still agrees with the pre-migration candidate evidence.

- [ ] **Step 3: Run full exact-head CI/validation gates**

No stale earlier SHA is accepted as final qualification.

- [ ] **Step 4: Review scope and secrets**

Confirm no Xtream/playback/EPG/UI changes and no raw locator/query/token values in artifacts/logs.

- [ ] **Step 5: Final disposition**

Only then mark #364 production adoption complete. If device/migration evidence fails, keep the production change unmerged and retain schema 10 on main.
