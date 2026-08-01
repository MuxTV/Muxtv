# Deterministic EPG Matching and Now/Next Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind immutable active XMLTV revisions to immutable active catalog revisions with deterministic, explainable channel decisions and expose bounded profile-aware now/next projections without fuzzy matching or full-guide polling.

**Architecture:** Matching is persisted by immutable producer revision pair, not by mutable names or UI profile. A Room v7 table stores only safe typed decisions and nullable canonical identity; matching uses an explicit source relation plus conservative exact evidence. Profile overlays are applied only when projecting now/next. Revision changes invalidate naturally through composite foreign keys; programme boundaries drive time invalidation.

**Tech Stack:** Kotlin, Coroutines/Flow, Room 3/SQLite, existing `catalog:api`, immutable catalog/EPG revisions, Android instrumentation migration contracts.

## Global Constraints

- `minSdk = 26`; keep API 26 and API 36 migration/device coverage.
- Room schema starts from v6; matching persistence is an explicit v6→v7 migration.
- Persisted matching is profile-independent; `hidden`/favorite overlays are applied only to projections.
- EPG `externalId` ↔ provider `tvgId` normalization: Unicode NFC + trim only; preserve case and punctuation.
- Display-name normalization: Unicode NFC + trim + Unicode-whitespace collapse + `Locale.ROOT` case-fold; preserve punctuation; no transliteration/edit distance/fuzzy/ML ranking.
- Current XMLTV schema has no trustworthy structured channel number/LCN; do not invent a number-based alias.
- Provider/source isolation is mandatory through `EpgSourceEntity.providerSourceId` and the exact catalog revision.
- Never persist raw compared strings, provider URLs, credentials, programme text in matching diagnostics.
- No Guide/Search/Favorites/Recent UI in #71; those remain #29.
- No new scheduler or second state architecture.
- #76 is merged as `5c60377e07745ea9b70529bedef234d438e59c7f`; matching may rely on its atomic catalog publication boundary.

---

## File Structure

- Create `core/database/src/main/kotlin/app/muxtv/database/EpgChannelMatchEntity.kt`: persisted revision-pair decision row.
- Create `core/database/src/main/kotlin/app/muxtv/database/EpgMatchingMigration.kt`: Room 6→7 schema migration.
- Create `core/database/src/main/kotlin/app/muxtv/database/EpgMatchingDao.kt`: revision inputs, deterministic replacement and bounded match reads.
- Create `core/database/src/main/kotlin/app/muxtv/database/RoomEpgMatchingStore.kt`: transaction boundary for recomputing one EPG source/revision relation.
- Create `catalog/api/src/main/kotlin/app/muxtv/catalog/EpgGuideRepository.kt`: safe domain contracts for decisions and now/next projection.
- Create `core/database/src/main/kotlin/app/muxtv/database/RoomEpgGuideRepository.kt`: bounded profile-aware projection implementation.
- Modify `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`: register v7 entity/DAO.
- Modify `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`: register migration and expose matching/guide owners.
- Modify `catalog/sync` only after the data boundary is proven: trigger matching after successful EPG/catalog publication; no matching logic in Workers.
- Add migration/schema/matcher/now-next Android contracts under `core/database/src/androidTest/...` and pure normalization/decision tests where possible.

---

### Task 1: Room v7 revision-pair decision boundary

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/EpgChannelMatchEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/EpgMatchingMigration.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/EpgMatchingMigration6To7Test.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/EpgChannelMatchSchemaContractTest.kt`

**Interfaces:**
- Consumes composite revision identities from `epg_channels(sourceId, revisionNumber, externalId)` and `source_revisions(sourceId, revisionNumber)`.
- Produces table `epg_channel_matches` with key `(epgSourceId, epgRevisionNumber, providerSourceId, catalogRevisionNumber, epgExternalChannelId)`.

- [ ] **Step 1: RED migration/schema contracts.** Assert v6→v7 preserves existing producer rows, creates the exact PK/FKs/indices, and cascades when either producer revision/channel disappears.
- [ ] **Step 2: Verify RED.** Run `./gradlew :core:database:compileDebugAndroidTestKotlin --no-daemon --stacktrace --console=plain`; expected failure before production migration is `Unresolved reference 'MIGRATION_6_7'` or absent v7 schema.
- [ ] **Step 3: Add entity.** `EpgChannelMatchEntity` stores decision/reason/canonical ID/candidate count only and enforces `MATCHED=1 candidate`, `UNRESOLVED=0`, `AMBIGUOUS>=2`.
- [ ] **Step 4: Add `MIGRATION_6_7`.** Create only `epg_channel_matches` and the three child-side FK indices; bump database to 7 and register migration.
- [ ] **Step 5: Verify GREEN.** Run compile plus API 26/API 36 migration contracts; export and commit schema 7.
- [ ] **Step 6: Commit** `feat: add revision-keyed epg matching schema`.

---

### Task 2: Conservative normalization and deterministic decision engine

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/EpgMatchNormalizer.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/EpgMatchDecision.kt`
- Test: `core/database/src/test/kotlin/app/muxtv/database/EpgMatchNormalizerTest.kt`
- Test: `core/database/src/test/kotlin/app/muxtv/database/EpgMatchDecisionTest.kt`

**Interfaces:**
- Produces `normalizeProviderId(String): String?` and `normalizeDisplayName(String): String?`.
- Produces typed `Matched(canonicalChannelId, reasonCode)`, `Unresolved(reasonCode)`, `Ambiguous(reasonCode, candidateCount)`.

- [ ] **Step 1: RED normalization tests.** Prove NFC/trim ID behavior, case-sensitive IDs, case/whitespace-insensitive names, punctuation preservation and no transliteration.
- [ ] **Step 2: RED decision tests.** Exact-ID candidate wins; one distinct canonical ID matches; multiple distinct canonical IDs are ambiguous; zero unresolved.
- [ ] **Step 3: Implement minimal normalization.** Use `java.text.Normalizer` and `Locale.ROOT` only for display names.
- [ ] **Step 4: Implement decision collapse.** Collapse by distinct canonical IDs, never row order.
- [ ] **Step 5: Verify GREEN.** Run `:core:database:testDebugUnitTest`.
- [ ] **Step 6: Commit** `feat: add deterministic epg match decision engine`.

---

### Task 3: Revision-scoped matcher and persisted safe decisions

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/EpgMatchingDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgMatchingStore.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/EpgMatchingStoreTest.kt`

**Interfaces:**
- `suspend fun reconcile(epgSourceId: String): EpgMatchingSummary`
- Reconcile reads current `EpgSourceEntity.activeRevision`, linked `providerSourceId`, and that source's `SourceEntity.activeRevision` once inside a transaction.

- [ ] **Step 1: RED Android contracts.** Exact ID beats duplicate names; duplicate exact names across distinct canonical IDs are ambiguous; same name in another provider cannot match; renamed text remains exact-ID matched; same revision pair is idempotent.
- [ ] **Step 2: Add bounded DAO input reads.** Read active EPG channels and active provider rows scoped to one explicit provider source/revision.
- [ ] **Step 3: Implement ladder.** `exact ID -> exact tvgName -> exact rawName -> unresolved`, collapsing every stage to distinct canonical IDs.
- [ ] **Step 4: Replace rows transactionally.** Replace only the exact current revision pair and store safe reason/candidate metadata only.
- [ ] **Step 5: Detect producer movement.** Re-read both active revisions before commit; if either changed, discard computed rows and return `Superseded`.
- [ ] **Step 6: Verify GREEN** on Android contracts.
- [ ] **Step 7: Commit** `feat: persist deterministic epg channel matches`.

---

### Task 4: Safe catalog/API contracts for bounded now/next

**Files:**
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/EpgGuideRepository.kt`
- Test: `catalog/api/src/test/kotlin/app/muxtv/catalog/EpgGuideModelsTest.kt`

**Interfaces:**

```kotlin
data class GuideProgramme(
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val title: String?,
)

data class ChannelNowNext(
    val canonicalChannelId: String,
    val current: GuideProgramme?,
    val next: GuideProgramme?,
    val nextBoundaryEpochMillis: Long?,
)

interface EpgGuideRepository {
    suspend fun getNowNext(
        profileId: String,
        canonicalChannelIds: List<String>,
        nowEpochMillis: Long,
    ): List<ChannelNowNext>
}
```

- [ ] **Step 1: RED model bounds/validation tests.** Reject blank IDs, duplicate requested IDs and unbounded request sizes.
- [ ] **Step 2: Add immutable API models.** No persistence entities escape the database module.
- [ ] **Step 3: Verify GREEN** with `:catalog:api:test`.
- [ ] **Step 4: Commit** `feat: define bounded epg now-next api`.

---

### Task 5: Profile-aware bounded now/next projection

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgGuideRepository.kt`
- Extend: `core/database/src/main/kotlin/app/muxtv/database/EpgMatchingDao.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/EpgNowNextRepositoryTest.kt`

**Interfaces:**
- Consumes only persisted `MATCHED` rows for the current catalog/EPG revision pair.
- Applies `user_channel_overlays` visibility at projection time, not match time.

- [ ] **Step 1: RED tests.** Cover current/next, hidden exclusion, revision switch, open-ended current programme, no-guide channel and strict request bounds.
- [ ] **Step 2: Add one bounded SQL path.** Join requested canonical IDs → current revision-pair matches → active EPG programmes. Reject excessive IDs; never materialize the full guide.
- [ ] **Step 3: Implement effective end.** Explicit stop wins; otherwise use the next programme start. An open-ended row without a subsequent programme is not considered infinite.
- [ ] **Step 4: Compute boundary.** Return the earliest future start/end that can change any requested projection as `nextBoundaryEpochMillis`.
- [ ] **Step 5: Verify GREEN** on Android contracts.
- [ ] **Step 6: Commit** `feat: add bounded profile-aware now-next projection`.

---

### Task 6: Reconcile matching after either producer publishes

**Files:**
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/EpgRefreshWorker.kt`
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorker.kt`
- Add Hilt/database wiring as required.
- Test: focused Worker/reconcile contracts.

**Interfaces:**
- On an `APPLIED` successful EPG refresh with a new revision: reconcile that EPG source.
- On an `APPLIED` successful source refresh: reconcile only EPG sources linked to that provider source.
- `SUPERSEDED`/`IGNORED` never trigger matching.

- [ ] **Step 1: RED orchestration tests.** Prove only successful durable publication reconciles affected relationships.
- [ ] **Step 2: Add bounded linked-source lookup.** Resolve only EPG source IDs related to the provider source.
- [ ] **Step 3: Invoke matching after durable `APPLIED`.** Matching failure must not roll back already-published catalog/EPG revision or replay network refresh; expose a typed recoverable reconciliation result.
- [ ] **Step 4: Verify GREEN** on focused tests.
- [ ] **Step 5: Commit** `feat: reconcile epg matches after revision publication`.

---

### Task 7: Revision/boundary invalidation API

**Files:**
- Extend `catalog:api` guide contract with a lightweight invalidation signal if required by #29.
- Implement from Room active-revision changes plus returned next boundary; no periodic polling loop.
- Test deterministic boundary transitions.

- [ ] **Step 1: RED revision test.** Unchanged revision pair emits no spurious data invalidation.
- [ ] **Step 2: RED switch test.** Catalog or EPG active revision switch invalidates immediately.
- [ ] **Step 3: RED time test.** Time invalidation occurs only at the earliest next programme boundary.
- [ ] **Step 4: Implement minimal Flow/timer owner** outside Compose.
- [ ] **Step 5: Verify GREEN** with focused unit/Android tests.
- [ ] **Step 6: Commit** `feat: expose epg revision and boundary invalidation`.

---

### Task 8: #28 end-to-end closure evidence and truth sync

**Files:**
- Add synthetic integration contract covering XMLTV → refresh/import → activation → matching → now/next.
- Update `.work/CURRENT-STATE.md`, `.work/meta/status.yaml`, `README.md` after merge evidence.

- [ ] **Step 1: Integration RED.** Require the end-to-end binding that does not exist before Tasks 1–7.
- [ ] **Step 2: Prove previous-good behavior.** Malformed input, cancellation and supersede keep previous-good catalog/EPG active.
- [ ] **Step 3: Prove diagnostic safety.** Evidence contains codes/counts but no raw provider/search/programme/credential values.
- [ ] **Step 4: Exact-head verification.** Run Full plus API26/API36 matrix on the final candidate.
- [ ] **Step 5: Close #71 and #28 only after exact-head evidence.** Synchronize repository truth in a separate documentation commit/PR.

---

## Self-review

- Spec coverage: exact identity/name precedence, ambiguity, provider isolation, deterministic revision pairing, bounded now/next, open-ended programmes, profile-hidden projection, revision/boundary invalidation and redaction all have explicit tasks/contracts.
- Intentional deferrals: channel-number alias (no structured XMLTV field), fuzzy/ML/manual mapping UI, Guide/Search/Favorites/Recent UI.
- Type consistency: persisted match is profile-independent; projection is profile-aware; producer revisions remain the authoritative invalidation identity.
- Performance stance: no Rust/UniFFI or full-guide materialization; measure SQL/query behavior after the deterministic Kotlin/Room baseline exists.
