# Bounded Guide Data Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a profile-visible active-channel keyset window and a bounded EPG programme time window that can back a future TV Guide grid without full catalog/EPG materialization.

**Architecture:** `GuideWindowRepository` owns viewport queries while `EpgGuideRepository` remains the Now/Next owner. `RoomGuideWindowRepository` consumes a dedicated `GuideWindowDao` and `GuideWindowInvalidationDao`; channel and programme reads reuse accepted active-revision/profile-visible semantics, execute bounded queries and expose explicit `limit + 1` completeness.

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

- [x] Add RED contracts for bounds, defensive copies, cursor/result invariants, non-READY payload rejection and redaction.
- [x] Implement immutable API models and `GuideWindowRepository`.
- [x] Preserve payload-free invalidation and privacy-safe `toString()` surfaces.

### Task 2: Active profile-visible channel keyset window

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/GuideWindowDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomGuideWindowRepository.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/GuideChannelWindowRepositoryTest.kt`

- [x] Seed active, staged-only, hidden, duplicate-variant and numbered/unnumbered fixtures.
- [x] Implement deterministic keyset comparison with explicit numbered/null ordering.
- [x] Request `limit + 1`, trim the extra row and expose a cursor only when truncated.
- [x] Cover revision swap and stale-cursor continuation without exposing previous/staged rows.

### Task 3: Bounded programme overlap window

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/GuideWindowDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomGuideWindowRepository.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/GuideProgrammeWindowRepositoryTest.kt`

- [x] Add fixtures for explicit overlap, non-overlap, open-ended effective end, terminal-open exclusion, hidden membership, conflict and truncation.
- [x] Resolve current match counts and bounded programme rows inside one Room transaction.
- [x] Query programme rows only for exactly-one-match canonical IDs.
- [x] Preserve requested channel order and map `READY`, `NO_GUIDE` and `SOURCE_CONFLICT`.
- [x] Carry stable EPG keys and explicit global truncation.

### Task 4: Runtime exposure and complete invalidation

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/GuideWindowInvalidationDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Create: `app/tv/src/main/kotlin/app/muxtv/di/GuideWindowModule.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/GuideWindowInvalidationTest.kt`

- [x] Expose one `RoomGuideWindowRepository` through database components and Hilt.
- [x] Observe catalog, overlay, match and programme table invalidations without polling or full-table counts.
- [x] Cover same-cardinality overlay update invalidation.

### Task 5: Acceptance and merge truth

- [ ] Confirm host Full compiles API, Room SQL, Hilt and all unit tests on the exact head.
- [ ] Confirm API26/API36 database tests for keyset, overlap and invalidation.
- [ ] Confirm product matrix has no startup/DI regression.
- [ ] Confirm generated Room v10 schema is byte-identical and no migration is introduced.
- [ ] Confirm zero unresolved review threads and final privacy/performance review.
- [ ] Update PR evidence and merge only after exact-head acceptance.
- [ ] Update issue #29 after merge, leaving Guide state/UI/D-pad/Player-Back as the remaining Guide package.
