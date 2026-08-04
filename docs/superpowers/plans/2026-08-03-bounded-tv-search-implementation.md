# Bounded TV Search Implementation Plan

**Status:** Search Core active in PR #96  
**Accepted base:** `main@3621c2d3f4eb7b5675ab6107497b4b3edbde9851`  
**Issue:** #29  
**Next product slice:** Search TV after Core acceptance

## Goal

Ship Unicode-correct, bounded, profile-aware local channel Search over active catalog metadata and the currently active EPG programme without full-catalog materialization, a second source of truth or a new native/database runtime.

## Current architecture

Search Core is a clean post-Favorites rebuild. It owns Room v8 -> v9 and currently includes:

- bounded public `ChannelSearchRepository` API;
- default 100 / max 200 public results;
- max six processed Unicode tokens;
- internal safe FTS4 quoted-prefix encoder;
- Room v9 `search_documents` + external-content FTS4 `unicode61`;
- transactional catalog/overlay/EPG derived-index hooks;
- compact unique EPG title vocabulary;
- active catalog/profile/current-policy EPG revalidation;
- selective smallest-seed multi-token intersection;
- explicit truncation;
- deterministic structured TV ranking;
- earliest programme-boundary invalidation;
- Favorites + Search database/factory registration in one ownership path.

The old pre-Favorites research PR #94 is closed and superseded. Do not retarget it.

## Global constraints

- `minSdk = 26`;
- keep platform/default Room SQLite driver;
- Search v1 uses FTS4 + `unicode61`;
- FTS remains derived candidate infrastructure only;
- no raw query syntax reaches `MATCH`;
- no query/profile/credential/locator payload in diagnostics;
- current programme semantics must match accepted Now/Next behavior;
- no FTS5/BM25/BundledSQLiteDriver/fuzzy/vector/transliteration/Rust/UniFFI/alternate player engine in this slice;
- no arbitrary focus delays;
- no speculative new indexes without measurements.

---

# Package A — Search Core / PR #96

## A1 — public Search boundary — implemented

Files:

- `catalog/api/.../ChannelSearchRepository.kt`
- `catalog/api/.../ChannelSearchQueryTest.kt`

Implemented contracts:

- profile nonblank;
- timestamp nonnegative;
- limit `1..200`;
- blank query -> empty Search;
- normalized whitespace;
- six-token processing limit;
- query-token overflow is not silently called complete;
- redacted public string rendering.

Remaining:

- [ ] exact-head unit execution on clean post-Favorites branch.

## A2 — safe Unicode FTS query encoder — implemented

Files:

- `core/database/.../SearchQueryEncoder.kt`
- `core/database/.../SearchQueryEncoderTest.kt`

Implemented:

- Unicode code-point letter/number tokenization;
- punctuation separation;
- quoted `"token*"` expressions;
- operator words remain terms;
- supplementary Unicode support;
- no raw wildcard/operator injection.

Remaining:

- [ ] execute JVM contracts non-zero on exact head;
- [ ] prove real API26/API36 `unicode61` Cyrillic behavior through migration/device tests.

## A3 — Room v9 schema/migration — implemented, generated schema pending commit

Files:

- `SearchDocumentEntity.kt`
- `SearchDocumentFtsEntity.kt`
- `SearchMigration.kt`
- `MuxTvDatabase.kt`
- `MuxTvDatabaseFactory.kt`
- `SearchMigration8To9ContractTest.kt`

As-built content table:

```text
rowid
documentKey
kind
canonicalChannelId?
profileId?
providerChannelId?
text
```

There are no EPG programme-origin columns. EPG Search uses a vocabulary row plus authoritative programme lookup.

Migration backfills canonical/provider/overlay text and one row per distinct exact nonblank programme title, then explicitly rebuilds FTS because normal external-content sync triggers are absent during migration execution.

Trusted prior KSP artifact:

- schema version: 9;
- identity hash: `1e22d8e43770617000dcbcf5bfdbbdba`.

Remaining:

- [ ] reproduce/accept exact-head Room/KSP output on PR #96;
- [ ] commit the exact Room-generated `9.json`, never hand-author it;
- [ ] v8 -> v9 migration API26;
- [ ] v8 -> v9 migration API36;
- [ ] inspect migrated FTS/table counts and Cyrillic fixture.

## A4 — derived Search lifecycle — implemented

Files:

- `SearchIndexDao.kt`
- `SearchDocumentFactory.kt`
- `SearchDocumentWritePlan.kt`
- `SourceRevisionDao.kt`
- `EpgRevisionDao.kt`
- `CatalogDao.kt`
- lifecycle/rowid tests.

Implemented ownership:

- source staging inserts provider raw/group/number docs;
- source activation publishes canonical-name docs only after accepted metadata;
- canonical updates preserve FTS content `rowid` through ordinary UPDATE;
- source discard/prune deletes provider docs before origin rows;
- overlay writes replace profile custom-name/number docs;
- EPG staging inserts only missing unique title vocabulary rows;
- EPG discard/prune removes unreferenced vocabulary.

Review correction on PR #96:

- [x] replace correlated `NOT EXISTS(primaryTitle = search_documents.text)` vocabulary cleanup with a set-based retained-title anti-membership query so cleanup does not perform an unindexed programme scan once per vocabulary row.

Remaining:

- [ ] exact-head lifecycle tests;
- [ ] measure vocabulary cleanup/backfill cost on representative large EPG data;
- [ ] do not add `primaryTitle` B-tree until that evidence exists.

## A5 — active-truth candidate DAO — implemented

Files:

- `ChannelSearchDao.kt`
- `ChannelSearchDataSource.kt`
- `ChannelSearchDaoTest.kt`

Implemented validation:

- active source revision;
- playable canonical mapping;
- requested profile hidden state;
- requested profile overlay ownership;
- active EPG revision;
- active provider/catalog revision;
- `CURRENT_EPG_MATCH_POLICY_VERSION`;
- `MATCHED` only;
- exactly one active mapping per canonical channel;
- exact current programme under accepted Now/Next rules;
- missing EPG never removes metadata result.

Programme Search starts from current-policy active mapping and existing source/revision/channel/time lookup, then compares the resolved current title against FTS vocabulary hits. It deliberately avoids joining millions of programme rows to title hits or creating a speculative title index.

Remaining:

- [ ] exact-head DAO tests non-zero;
- [ ] review API26 query behavior/planner evidence where available;
- [ ] characterize global boundary query cost on large data before adding indexes.

## A6 — selective multi-token repository — implemented

Files:

- `RoomChannelSearchRepository.kt`
- `RoomChannelSearchRepositoryTest.kt`

Algorithm:

1. probe each of at most six tokens with 801-row limit;
2. retain at most 800 + overflow flag;
3. choose smallest probe as seed;
4. recheck overflowing broad tokens only inside current seed IDs;
5. intersect all required token matches;
6. fetch active summaries only for bounded IDs;
7. rank deterministically;
8. project Now/Next only for published <=200 rows.

This prevents a query such as `канал 1200` from losing channel 1200 solely because `канал*` has tens of thousands of matches.

Remaining:

- [ ] exact-head JVM repository contracts;
- [ ] confirm broad-token restricted recheck remains bounded on Room/API26;
- [ ] measure one-token, multi-token, exact-number, Cyrillic-prefix, programme and no-match cases.

## A7 — clean review/acceptance — active

Repository hygiene already completed:

- [x] Favorites #92 accepted;
- [x] post-Favorites truth sync #95 accepted;
- [x] old Search PR #94 closed unmerged;
- [x] PR #96 rebuilt directly from current main;
- [x] initial clean surface: 1 commit / 29 Search files / behind 0.

Remaining merge gates:

- [ ] exact-head compile/KSP;
- [ ] generated schema v9 committed;
- [ ] API26/API36 migration + `unicode61` runtime;
- [ ] non-zero Search unit/instrumentation contracts;
- [ ] source/EPG/Favorites regression contracts;
- [ ] review threads resolved;
- [ ] descriptive DB-size/backfill/query measurements;
- [ ] final compare against then-current main;
- [ ] ready-for-review;
- [ ] SHA-guarded squash merge;
- [ ] update issue #29 and repository truth with accepted Search Core merge SHA/evidence.

---

# Package B — Search TV

Start only after Search Core is accepted so UI does not own migration/index churn.

## B1 — feature module and ViewModel

Create `:feature:search` with destination-scoped state.

Required behavior:

- blank query performs no repository search;
- typing debounce starts at ~300 ms;
- explicit IME Search/Done is immediate;
- normalized duplicate query does not restart work;
- newer generation cancels old repository/boundary work;
- stale generation cannot overwrite current state;
- same-query data/time refresh keeps current Content mounted;
- payload-free failures;
- process death retains query/focus anchor only, not result lists.

## B2 — TV route/focus

Screen:

- one search field;
- result/status copy;
- one lazy vertical result list;
- row number/favorite/name/group/current-programme;
- explicit truncated copy.

Focus contract:

- initial -> input;
- Down input -> first result;
- Up first result -> input;
- OK -> existing Player;
- Player -> Back restores query + same canonical channel;
- removed result -> nearest previous;
- no results -> input;
- IME submit immediately escapes text input;
- no arbitrary delay/global focus engine.

## B3 — app integration

- Hilt/application wiring from accepted `MuxTvDatabaseComponents.channelSearchRepository`;
- replace Search placeholder route;
- no Search-side playback resolution;
- preserve Navigation3 saveable/ViewModel decorators;
- instrument Home/Search/Player/Back and long Cyrillic queries on TV APIs.

## B4 — Search TV acceptance

- [ ] unit generation/debounce/cancellation tests;
- [ ] API26 D-pad/IME path;
- [ ] API36 D-pad/IME path;
- [ ] Search -> Player -> Back stable identity;
- [ ] no-results/truncated/long-RU-string behavior;
- [ ] clean review surface + guarded merge.

---

# Following product sequence

After Search:

1. **Recent / expected Room v10** — profile-scoped history written only after confirmed successful playback;
2. **bounded Guide** — channel × time viewport, never full-guide materialization;
3. **issue #30** — bounded playback fallback + TV Doctor Lite;
4. **issue #33** — final TV UX/Lounge polish on real routes;
5. **issue #31** — R8/Baseline/Startup/Macrobenchmark/signing/SBOM/physical-device alpha work.

Issue #27 remains parallel repeated performance evidence and does not block daily-use product work.
