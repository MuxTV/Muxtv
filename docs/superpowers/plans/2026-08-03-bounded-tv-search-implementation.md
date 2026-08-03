# Bounded TV Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Unicode-correct, bounded, profile-aware Android TV channel Search over active catalog metadata and the currently active EPG programme without full-catalog materialization or a second source of truth.

**Architecture:** Split Search into two independently reviewable PRs. PR A (`Search Core`) is based on accepted `main` and introduces the public query contract, Room v9 derived `search_documents` + external-content FTS4/`unicode61`, bounded per-token candidate intersection, active/current-policy validation, deterministic ranking and programme-boundary metadata. PR B (`Search TV`) starts only after Favorites is accepted and adds `:feature:search`, debounce/IME generation ownership, Navigation3 wiring and explicit TV focus/Player-Back restoration.

**Tech Stack:** Kotlin 2.4.x, Coroutines/Flow, Room 3, platform SQLite FTS4, `unicode61`, Compose for TV, Navigation 3, Hilt, Media3, Android TV API 26–36.

## Global Constraints

- Repository: `MuxTV/Muxtv`; `minSdk = 26`.
- Accepted schema before this work is Room v8; Search owns v8 -> v9.
- Keep the platform/default Room SQLite driver; do not introduce `BundledSQLiteDriver` in Search v1.
- Use Room 3 `@Fts4(contentEntity = ..., tokenizer = FtsOptions.TOKENIZER_UNICODE61)`.
- FTS is derived candidate infrastructure only; active catalog/profile/current-policy EPG truth is revalidated before publication.
- Public result limit: default 100, maximum 200.
- Query token limit: 6. Initial internal candidate ceiling: 800 per token; fetch ceiling + 1 to detect truncation.
- Blank query performs no unfiltered search.
- Search diagnostics never include query text, source/provider IDs, locators, credentials, headers, cookies, tokens or raw exceptions.
- Current programme semantics must exactly match accepted `RoomEpgGuideRepository`, including open-ended programme handling and source conflicts.
- No FTS5/BM25, bundled SQLite, vectors, transliteration, fuzzy search, remote providers, Paging, custom global focus engine, Rust/UniFFI or alternate player engine in this slice.
- Do not use arbitrary delays as focus synchronization.
- TDD for behavior changes: failing test -> verify RED -> minimal implementation -> verify GREEN -> refactor.

---

## PR A — Search Core (independent of Favorites UI)

### Task 1: Public Search contract and safe query encoder

**Files:**
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/ChannelSearchRepository.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/SearchQueryEncoder.kt`
- Create: `catalog/api/src/test/kotlin/app/muxtv/catalog/ChannelSearchQueryTest.kt`
- Create: `catalog/api/src/test/kotlin/app/muxtv/catalog/SearchQueryEncoderTest.kt`

**Interfaces:**
- Produces `ChannelSearchRepository.observe(ChannelSearchQuery): Flow<ChannelSearchSnapshot>`.
- Produces `ChannelSearchQuery`, `ChannelSearchResult`, `ChannelSearchSnapshot` and internal-safe encoded query tokens.
- Reuses existing `PlayableChannelSummary` rather than creating another channel identity model.

- [ ] Write RED tests proving: blank/whitespace normalization; limit 1..200; six-token cap; redacted `toString`; punctuation separation; Cyrillic letters preserved; raw operators/quotes/wildcards never survive as FTS syntax; `Россия 1` produces two token-prefix expressions.
- [ ] Run `./gradlew :catalog:api:test` and verify failures are caused by missing Search types/encoder.
- [ ] Implement the minimal API and code-point encoder. Collapse whitespace for `normalizedText`; extract only Unicode letter/number runs; emit private `token*` FTS4 expressions.
- [ ] Re-run `:catalog:api:test`; keep query/provider data out of public string rendering.
- [ ] Commit `feat: add bounded channel search contract`.

### Task 2: Room v9 external-content FTS4 schema and migration compatibility

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/SearchDocumentEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SearchDocumentFtsEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SearchMigration.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/SearchMigration8To9ContractTest.kt`
- Create generated schema after successful compile: `core/database/schemas/app.muxtv.database.MuxTvDatabase/9.json`

**Interfaces:**
- `search_documents`: normal typed content table with integer `rowId` PK, unique `documentKey`, kind/origin/profile metadata and nonblank text.
- `search_documents_fts`: Room external-content FTS4 table, tokenizer `unicode61`, indexed text only.
- `MIGRATION_8_9` creates/backfills derived structures and rebuilds FTS explicitly.

- [ ] Write RED migration/schema tests that open v8 fixtures, migrate to v9 and require both tables, `unicode61` FTS SQL, external-content relation, non-zero backfill from seeded canonical/provider/overlay/EPG text, and Cyrillic `рос*` matching uppercase/lowercase variants.
- [ ] Verify RED on schema version 8 / missing FTS tables.
- [ ] Add entities and bump `MuxTvDatabase.version` to 9; expose `searchDao()` only after Task 3 defines it.
- [ ] Implement `MIGRATION_8_9`. Because Room removes external-content FTS synchronization triggers while migrations execute, insert/backfill `search_documents` first and then run `INSERT INTO search_documents_fts(search_documents_fts) VALUES('rebuild')` before migration completion. Room recreates sync triggers after migration.
- [ ] Add `MIGRATION_8_9` to `MuxTvDatabaseFactory`.
- [ ] Generate/commit the exact Room `9.json`; never hand-author identity hash.
- [ ] Verify migration on API26/API36 when the device lane is available; the compatibility contract is a correctness gate, not a reason to block coding while runner jobs are queued.
- [ ] Commit `feat: add Room v9 Unicode search index`.

### Task 3: Explicit derived-index write boundary

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/SearchIndexDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomSearchIndexStore.kt`
- Modify existing catalog/source publication boundary files identified by repository inspection.
- Modify: `core/database/src/main/kotlin/app/muxtv/database/ChannelPreferencesDao.kt` (post-#92 integration may be transferred in PR B if #92 is not yet accepted).
- Modify existing EPG revision/import publication boundary files identified by repository inspection.
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/SearchIndexLifecycleTest.kt`

**Interfaces:**
- `SearchIndexStore` is internal; no UI consumes it.
- All application writes target `search_documents`; Room-generated external-content triggers maintain FTS after database creation/migration.
- Document keys are deterministic and idempotent per origin/kind.

- [ ] Characterize the real source catalog and EPG staging/publication transactions before editing them; no new state owner.
- [ ] Write RED lifecycle tests for canonical name, provider raw/group/number, overlay custom name/number, EPG programme title, update idempotence and retained-revision cleanup.
- [ ] Implement bulk upsert/delete DAO methods with bounded lists and deterministic keys.
- [ ] Integrate index maintenance into existing publication/mutation boundaries, not Compose/ViewModel and not arbitrary observers.
- [ ] Ensure index failure participates in the owning transaction where stale index would otherwise become permanent; active-truth validation still prevents stale publication.
- [ ] Commit `feat: maintain derived search documents`.

### Task 4: Bounded token candidate queries and active-truth validation

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/ChannelSearchDao.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/ChannelSearchDaoTest.kt`

**Interfaces:**
- `searchCandidates(profileId, ftsExpression, nowEpochMillis, fetchLimit)` returns at most candidate ceiling + 1 validated rows for one token.
- Candidate origin is internal and sufficient for final structured ranking.

- [ ] Write RED tests for Cyrillic case/prefix; canonical name; provider raw/group/number; overlay profile isolation; hidden channel exclusion; inactive source revision exclusion; stale EPG revision exclusion; stale match policy exclusion; source conflict exclusion; optional EPG never suppressing metadata match.
- [ ] Add explicit tests for current-programme semantics matching `RoomEpgGuideRepository`: bounded stop; open-ended + next; open-ended + no-next; future/past programmes.
- [ ] Implement one-token FTS candidate SQL joined back to active catalog/profile/current-policy truth.
- [ ] Fetch `MAX_CANDIDATES_PER_TOKEN + 1` so overflow is observable rather than silently truncating.
- [ ] Commit `feat: add validated search candidate queries`.

### Task 5: Repository intersection, ranking and temporal boundary

**Files:**
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomChannelSearchRepository.kt`
- Create: `core/database/src/test/kotlin/app/muxtv/database/RoomChannelSearchRepositoryTest.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseComponents` declaration in the factory file.

**Interfaces:**
- Implements public `ChannelSearchRepository`.
- Intersects canonical IDs across at most six bounded token candidate sets.
- Publishes `ChannelSearchSnapshot(results, isTruncated, nextBoundaryEpochMillis)`.

- [ ] Write RED tests proving cross-field tokens (`Россия` from name + `1` from number) intersect correctly; all tokens must match same canonical channel; overflow propagates `isTruncated`; public result cap is deterministic.
- [ ] Write ranking tests: exact effective number -> exact effective/custom name -> display-name prefix -> provider raw-name prefix -> group -> current programme -> stable number/name/id tie-break.
- [ ] Add boundary tests proving earliest future current-programme membership change is returned even for a channel not currently in public results.
- [ ] Implement repository intersection with bounded sets only; no full catalog/EPG materialization.
- [ ] Expose repository from `MuxTvDatabaseComponents`.
- [ ] Commit `feat: implement bounded Unicode channel search`.

### Task 6: Search Core performance and migration evidence

**Files:**
- Extend repository-owned deterministic measurement tooling under existing `core:testing` / measurement paths.
- Add durable report under `docs/performance/` after representative data exists.

**Interfaces:**
- Descriptive evidence for v8->v9 backfill, DB-size delta, one-token/multi-token candidate latency, final projection and low-RAM behavior.

- [ ] Measure 1k/10k/50k catalog fixtures and bounded EPG text where available.
- [ ] Record DB-size delta, migration wall time/peak memory, exact-number, Cyrillic-prefix, group, programme, multi-token and no-match cases.
- [ ] Compare ordinary FTS4 prefix scan vs optional FTS `prefix=` indexes only after baseline; do not add prefix indexes without measured benefit.
- [ ] Keep candidate cap 800 descriptive until selectivity/variance evidence supports tuning.
- [ ] Do not introduce FTS5/BM25/bundled SQLite from these measurements without a separate ADR.

---

## PR B — Search TV (starts after Favorites acceptance)

### Task 7: Add `:feature:search` ViewModel generation ownership

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/search/build.gradle.kts`
- Create: `feature/search/src/main/kotlin/app/muxtv/feature/search/SearchViewModel.kt`
- Create: `feature/search/src/test/kotlin/app/muxtv/feature/search/SearchViewModelTest.kt`

**Interfaces:**
- `queryText: StateFlow<String>`.
- `SearchUiState = EmptyQuery | Loading | Content(rows,isTruncated) | NoResults | Failed`.
- Typing debounce 300 ms; IME submit bypasses debounce; newer generation cancels repository/boundary work.

- [ ] RED: blank query performs no repository search; typing coalesces; submit is immediate; duplicate normalized query does not restart; newer generation cannot be overwritten by stale result; boundary job cancels on generation change.
- [ ] Implement with structured coroutines/Flow and payload-free failures.
- [ ] Preserve existing Content during same-query data/boundary refresh to keep focus tree mounted.

### Task 8: TV Search UI, IME and focus ownership

**Files:**
- Create: `feature/search/src/main/kotlin/app/muxtv/feature/search/SearchRoute.kt`
- Add instrumentation under `app/tv/src/androidTest/kotlin/app/muxtv/`.

**Interfaces:**
- Search field -> first result on Down/submit when available.
- First result -> field on Up.
- `OK` opens Player; no Search-side playback resolution.
- Save query + canonical focus anchor, not result list.

- [ ] Implement one-column TV screen: title, query field, honest count/truncation copy, lazy result rows.
- [ ] Immediate IME Search/Done explicitly escapes input focus; if result is not ready, arm one generation-scoped `focusFirstResultWhenReady` intent instead of using delays.
- [ ] Restore same surviving canonical result after Player -> Back; nearest-previous fallback; no-results focuses query field.
- [ ] Include long Russian labels and Cyrillic query instrumentation.

### Task 9: App DI/navigation integration

**Files:**
- Modify: `app/tv/build.gradle.kts`
- Create: `app/tv/src/main/kotlin/app/muxtv/di/ChannelSearchModule.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`

**Interfaces:**
- Replace `AppDestination.Search -> PlaceholderRoute("Поиск")` with `SearchRoute`.
- Search opens existing `AppDestination.Player(channelId)`.

- [ ] Wire `channelSearchRepository` from `MuxTvDatabaseComponents` through Hilt/application composition.
- [ ] Add `:feature:search` dependency.
- [ ] Keep Navigation3 saveable/ViewModel decorators unchanged.
- [ ] Verify Home/nav Search path, Search -> Player -> Back, and no duplicate presentation-state owner.

### Task 10: Acceptance, truth sync and issue #29 progression

**Files:**
- Update: `README.md`
- Update: `.work/CURRENT-STATE.md`
- Update: `.work/meta/status.yaml`
- Update execution checkpoint docs.

- [ ] Merge Favorites #92 only on exact-head evidence; do not pretend queued runs are green.
- [ ] Clean-rebuild repository truth after #92 rather than reviving superseded #88.
- [ ] Merge Search Core and Search TV only with clean base/head/review surfaces and non-zero migration/search/focus evidence.
- [ ] Update issue #29: Now/Next + Favorites + Search complete; Recent then bounded/lazy Guide remain.
- [ ] Search owns Room v9; plan Recent storage migration from v9 to v10.

## Self-Review

- Spec coverage: Unicode correctness, FTS4/`unicode61`, active truth, multi-token intersection, truncation, temporal invalidation, debounce/IME, TV focus, Player/Back, security and measurement are all assigned.
- Stack hygiene: Core PR does not depend on unmerged #92; TV PR starts after #92, avoiding a post-squash stacked rebuild.
- Migration correctness: external-content FTS trigger removal during migration is explicitly handled with an FTS `rebuild` after content backfill.
- Type consistency: public API is defined once in Task 1 and consumed unchanged by Tasks 5/7/9.
- Scope: Recent/Guide/Recovery/alpha remain separate follow-up plans and are not smuggled into Search.
