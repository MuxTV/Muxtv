# Deterministic EPG Matching and Now/Next Implementation Plan

> **Execution status:** Tasks 1–7 are implemented. Task 8 has the end-to-end integration seam in-tree; only final exact-head runtime evidence and repository-truth closure remain.

**Goal:** Bind immutable active XMLTV revisions to immutable active catalog revisions with deterministic, explainable channel decisions and expose bounded profile-aware now/next projections without fuzzy matching, full-guide polling, or speculative runtime owners.

**Architecture:** Matching is persisted by immutable producer revision pair, not by mutable names or UI profile. A Room v7 table stores only safe typed decisions and nullable canonical identity; matching uses an explicit source relation plus conservative exact evidence. Profile overlays are applied only when projecting now/next. Room table invalidation signals data changes; `nextBoundaryEpochMillis` is the clock contract. The future #29 UI consumer owns its lifecycle-aware one-shot timer rather than #71 introducing a global scheduler.

**Tech Stack:** Kotlin, Coroutines/Flow, Room 3/SQLite, existing `catalog:api`, immutable catalog/EPG revisions, Android instrumentation migration/integration contracts.

## Global Constraints

- `minSdk = 26`; keep old-edge and current Android TV migration/device coverage.
- Room schema starts from v6; matching persistence is an explicit v6→v7 migration.
- Persisted matching is profile-independent; hidden/favorite overlays are applied only to projections.
- EPG `externalId` ↔ provider `tvgId` normalization: Unicode NFC + trim only; preserve case and punctuation.
- Display-name normalization: Unicode NFC + trim + Unicode-whitespace collapse + `Locale.ROOT` case-fold; preserve punctuation; no transliteration/edit distance/fuzzy/ML ranking.
- Current XMLTV schema has no trustworthy structured channel number/LCN; do not invent a number-based alias.
- Provider/source isolation is mandatory through `EpgSourceEntity.providerSourceId` and the exact catalog revision.
- Never persist raw compared strings, provider URLs, credentials or programme text in matching diagnostics.
- No Guide/Search/Favorites/Recent UI in #71; those remain #29.
- No new scheduler, timer owner, second cache or presentation-state architecture.
- Do not impose an arbitrary linked-guide count cap: all currently linked active EPG sources are reconciled. If a real scale problem appears, measure it before adding a bound or queue.
- #76 is merged as `5c60377e07745ea9b70529bedef234d438e59c7f`; matching relies on its atomic catalog publication boundary.

---

## Final File/Boundary Structure

- `core/database/.../EpgChannelMatchEntity.kt`: persisted revision-pair decision row.
- `core/database/.../EpgMatchingMigration.kt`: explicit Room 6→7 migration.
- `core/database/.../EpgMatchingDao.kt`: revision inputs and optimistic replacement.
- `core/database/.../RoomEpgMatchingStore.kt`: deterministic matching/reconcile owner.
- `catalog/api/.../EpgGuideRepository.kt`: bounded now/next API plus payload-free data invalidation signal.
- `core/database/.../EpgGuideDao.kt`: bounded current-revision projection and observable revision/match relation.
- `core/database/.../RoomEpgGuideRepository.kt`: profile-aware current/next projection.
- `catalog/sync`: triggers matching after durable successful EPG/catalog publication; matching logic remains outside Workers.
- `catalog/importer/src/androidTest/.../EpgEndToEndDataPathTest.kt`: real M3U + XMLTV import → Room revision → match → now/next integration seam.

---

### Task 1: Room v7 revision-pair decision boundary — DONE

- [x] RED migration/schema contracts.
- [x] Explicit `EpgChannelMatchEntity` with immutable producer revision pair.
- [x] `MIGRATION_6_7`, database version 7 and migration registration.
- [x] Composite FKs/indices and cascade behavior.
- [x] Generated Room schema 7 committed.
- [x] Old-edge/current migration contract coverage wired.

### Task 2: Conservative normalization and deterministic decision engine — DONE

- [x] Provider ID normalization: NFC + trim, case/punctuation preserved.
- [x] Display-name normalization: NFC + Unicode-whitespace collapse + `Locale.ROOT` case-fold.
- [x] Exact-ID / exact-name deterministic decision tests.
- [x] Ambiguity collapses by distinct canonical IDs rather than provider-row count.
- [x] No transliteration, edit distance, fuzzy score or ML.

### Task 3: Revision-scoped matcher and persisted safe decisions — DONE

- [x] Exact ID beats weaker name evidence.
- [x] Distinct canonical-ID collisions become `AMBIGUOUS`, never arbitrary winners.
- [x] Provider-source isolation.
- [x] Same revision-pair reconcile is idempotent.
- [x] `snapshot → compute outside transaction → replace-if-current` prevents stale publication without holding a long SQLite transaction.
- [x] Reconcile after provider publication processes every linked active EPG source; the speculative hard cap and `CapacityExceeded` branch were removed after a behavioral RED proved they could leave valid guides stale.

### Task 4: Safe bounded now/next API — DONE

- [x] `NowNextQuery` validates nonblank distinct IDs and a bounded request size.
- [x] Safe immutable `GuideProgramme` / `ChannelNowNext` models.
- [x] `READY | NO_GUIDE | SOURCE_CONFLICT` states.
- [x] Diagnostics redact profile/channel/programme payloads.
- [x] `EpgGuideRepository` exposes bounded `getNowNext` plus payload-free `observeDataChanges()`.

### Task 5: Profile-aware bounded now/next projection — DONE

- [x] Reads only current catalog/EPG revision-pair matches.
- [x] Hidden overlay applied at projection time, not matching time.
- [x] Bulk bounded SQL projection; no full-guide materialization and no N×2 Room query loop.
- [x] API26-compatible correlated subqueries rather than requiring window functions.
- [x] Open-ended programme uses the next programme start as effective end when available; without a successor it is not treated as infinite current.
- [x] Multiple current guide mappings produce explicit `SOURCE_CONFLICT` because no preferred-guide contract exists.
- [x] Returns the earliest future programme boundary as `nextBoundaryEpochMillis`.

### Task 6: Reconcile matching after durable producer publication — DONE

- [x] `APPLIED` new EPG revision reconciles that EPG source.
- [x] `APPLIED` new catalog revision reconciles all linked active EPG sources.
- [x] NotModified / stale / ignored / failed publication does not run matching.
- [x] Ordinary derived-state reconcile failure does not convert an already durable network success into WorkManager retry/failure.
- [x] `CancellationException` remains authoritative and is rethrown.
- [x] No second scheduler/work subsystem introduced.

### Task 7: Minimal revision/data invalidation contract — DONE

**YAGNI correction:** #71 does not own a global clock scheduler. Room supplies only a payload-free invalidation signal; `nextBoundaryEpochMillis` tells #29 when a lifecycle-aware consumer must requery because time, rather than data, changed.

- [x] Public `observeDataChanges(): Flow<Unit>` contract.
- [x] Observable Room query references current EPG sources, catalog sources and current match rows without loading programme payload.
- [x] Active catalog revision switch produces a data invalidation signal.
- [x] Do not `distinctUntilChanged()` the invalidation stream: equal aggregate values may still represent changed underlying mapping rows. A JVM regression contract proved the suppression bug before the operator was removed.
- [x] No timer, scheduler, fingerprint table, version counter or new state owner introduced.

### Task 8: #28 end-to-end closure evidence — IMPLEMENTED, FINAL EVIDENCE PENDING

- [x] Add one focused integration seam using existing components rather than a new service.
- [x] Real M3U import creates active catalog data.
- [x] Real XMLTV import creates active immutable EPG data.
- [x] Deterministic matcher binds the active producer revisions.
- [x] Public bounded now/next projection returns current, next and boundary for the resulting canonical channel.
- [x] Add the importer integration test to the permanent Device gate with non-zero test-count validation.
- [x] Keep malformed/cancelled/previous-good/parser edge behavior in the existing dedicated #28/#70 contracts rather than duplicating the entire matrix inside this single E2E test.
- [ ] Remove temporary PR-only hosted smoke workflow.
- [ ] Run exact final-head ordinary Full validation.
- [ ] Run exact final-head old-edge/current Android TV device matrix, including the importer E2E test and database invalidation/matcher contracts.
- [ ] Perform final diff/redaction/review-thread check.
- [ ] Mark PR ready and squash-merge only after both final gates are green.
- [ ] Close #71 and #28 from exact evidence, then synchronize `.work` repository truth separately.

---

## YAGNI Review Result

### Keep — required by current correctness or product acceptance

- immutable catalog/EPG producer revisions and ownership checks;
- revision-pair persisted EPG matching;
- deterministic exact evidence ladder and explicit ambiguity;
- bounded now/next query/projection;
- explicit multiple-guide conflict rather than a hidden arbitrary winner;
- Room data invalidation + returned next-boundary timestamp;
- existing two-edge Android compatibility evidence.

### Remove / defer

- arbitrary linked-guide hard cap without measurement;
- global EPG timer/scheduler owner;
- Hilt/UI exposure of guide APIs before #29 has a consumer;
- fuzzy/ML/manual mapping;
- structured-number alias until the parser/schema truly owns a structured number;
- Guide/Search/Favorites/Recent UI inside #71;
- new cache/state framework;
- Rust/UniFFI or alternate player engine without reproducible evidence from #27;
- broader emulator/physical-device matrices before the feature/release gates that need them.

### Rule for next work

Prefer a small vertical user-visible slice that reuses existing ownership over a new cross-cutting abstraction. Add a new state owner, scheduler, persistence table, performance gate or native boundary only when a current requirement or measured failure cannot be satisfied by an existing boundary.
