# Bounded Guide Data Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a profile-visible active-channel keyset window and a bounded EPG programme time window that can back a future TV Guide grid without full catalog/EPG materialization.

**Architecture:** A new `GuideWindowRepository` API owns viewport queries while existing `EpgGuideRepository` continues to own Now/Next. `RoomEpgGuideRepository` implements both interfaces over an extended `EpgGuideDao`; channel and programme reads reuse accepted active-revision/profile-visible semantics and explicit `limit + 1` completeness.

**Tech Stack:** Kotlin, Coroutines Flow, Room 3, SQLite, JUnit4, Truth, Android instrumentation, Hilt.

## Global Constraints

- Room remains v10; no entity/table/index/migration change.
- Channel window maximum is 50 rows and uses keyset, not offset, pagination.
- Programme query maximum is 50 channel IDs, 12 hours and 2,000 returned candidates.
- Never derive a total or `hasMore` from a capped result without an extra row.
- Preserve `READY / NO_GUIDE / SOURCE_CONFLICT` semantics.
- Preserve active current-revision and selected-profile-visible membership before surface projection.
- Open-ended programmes without a following start are not infinite.
- No Guide Compose UI, Paging3, provider heuristic, retry policy or new player work.

---

### Task 1: Public bounded Guide contracts

**Files:**
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/GuideWindowRepository.kt`
- Create: `catalog/api/src/test/kotlin/app/muxtv/catalog/GuideWindowRepositoryContractTest.kt`

**Interfaces:**
- Produces: `GuideChannelCursor`, `GuideChannelWindowQuery`, `GuideChannelWindow`
- Produces: `GuideProgrammeWindowQuery`, `GuideProgrammeKey`, `GuideProgrammeCell`
- Produces: `ChannelGuideProgrammeWindow`, `GuideProgrammeWindow`, `GuideWindowRepository`

- [ ] **Step 1: Write compile-RED tests for query bounds, defensive channel ID copy, cursor/result invariants, non-READY payload rejection and redaction.**
- [ ] **Step 2: Run `./gradlew.bat :catalog:api:test --tests app.muxtv.catalog.GuideWindowRepositoryContractTest --stacktrace --console=plain`; expect unresolved Guide window symbols.**
- [ ] **Step 3: Implement only the immutable API models and validation needed by those tests.**
- [ ] **Step 4: Rerun the focused API test and all `:catalog:api:test`; expect green.**
- [ ] **Step 5: Commit `feat(catalog): define bounded Guide window contracts`.**

### Task 2: Active profile-visible channel keyset window

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/EpgGuideDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgGuideRepository.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/GuideChannelWindowRepositoryTest.kt`

**Interfaces:**
- Consumes: `GuideChannelWindowQuery`
- Produces DAO rows carrying `channelNumberSort`, display name and `PlayableChannelSummary` fields.
- Produces repository `getChannelWindow()`.

- [ ] **Step 1: Seed active revision channels, a staged-only channel, hidden overlay, duplicate variants and numbered/unnumbered rows; assert first page, cursor and second page.**
- [ ] **Step 2: Run the focused instrumentation test; expect compile failure because DAO/repository methods do not exist.**
- [ ] **Step 3: Add one bounded DAO query with active/profile-visible predicate, deterministic keyset comparison and `limit + 1`.**
- [ ] **Step 4: Map rows to `PlayableChannelSummary`; trim the extra row and derive `nextCursor` only when truncated.**
- [ ] **Step 5: Add revision-swap and stale-cursor assertions; staged/previous/hidden channels remain absent.**
- [ ] **Step 6: Run focused and existing Playback/active-truth database tests; expect green.**
- [ ] **Step 7: Commit `feat(database): add Guide channel keyset window`.**

### Task 3: Bounded programme overlap window

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/EpgGuideDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgGuideRepository.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/GuideProgrammeWindowRepositoryTest.kt`

**Interfaces:**
- Consumes: `GuideProgrammeWindowQuery`
- Produces transaction snapshot: accepted match counts plus bounded programme rows with stable EPG key and effective end.
- Produces repository `getProgrammeWindow()`.

- [ ] **Step 1: Write RED fixtures for explicit overlap, excluded non-overlap, open-ended effective end, open-ended terminal exclusion, hidden/stale exclusion, source conflict and `limit + 1` truncation.**
- [ ] **Step 2: Run the focused instrumentation test; expect missing DAO/repository methods.**
- [ ] **Step 3: Reuse current match-count query and add a programme-window query only for single-match IDs.**
- [ ] **Step 4: Compute effective end with explicit stop or correlated next-start subquery and apply one overlap predicate.**
- [ ] **Step 5: Preserve requested channel order and return `READY`, `NO_GUIDE` or `SOURCE_CONFLICT` per ID.**
- [ ] **Step 6: Trim the global extra row, set `isTruncated`, and keep stable programme keys.**
- [ ] **Step 7: Run focused, Now/Next, active-membership and full database instrumentation tests; expect green.**
- [ ] **Step 8: Commit `feat(database): add bounded Guide programme window`.**

### Task 4: Runtime exposure and acceptance

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Add API/DI tests only if existing test modules require them.

**Interfaces:**
- `MuxTvDatabaseComponents.guideWindowRepository: GuideWindowRepository`
- Hilt provider for `GuideWindowRepository`

- [ ] **Step 1: Expose the same `RoomEpgGuideRepository` instance through the new interface.**
- [ ] **Step 2: Add the Hilt provider without creating a second Room repository instance.**
- [ ] **Step 3: Run host Full and database instrumentation compile.**
- [ ] **Step 4: Open/update draft PR with exact scope, RED/GREEN evidence and explicit non-goals.**
- [ ] **Step 5: Run old-edge/current database/product evidence on exact head.**
- [ ] **Step 6: Verify zero schema diff, zero unresolved review threads and final privacy/semantic review.**
- [ ] **Step 7: Squash-merge only after exact-head evidence is green; update issue #29 to leave only Guide state/UI/Player-Back work.**
