# Database query measurement inventory — 2026-08-24

Status: runner-free static preparation. This document does **not** authorize SQL, index, schema, Room pool, FTS or coroutine-context changes.

Reviewed accepted runtime baseline: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Owners:

- #27 — deterministic performance/measurement authority;
- #178 — M0 measurement correctness gate;
- #196 — isolated Room/SQLite experiments after M0;
- #31 — release/physical-device claims.

## Governing rule

Static SQL inspection is useful for choosing what to measure, not for declaring an optimization.

Before any DB-performance conclusion:

1. #178/M0 must be accepted;
2. the same corpus/profile/environment must be used for before/after comparison where practical;
3. `EXPLAIN QUERY PLAN` and latency distributions must be captured together;
4. query semantics/result digests must remain equivalent;
5. one optimization hypothesis is changed at a time.

A valid result is **keep the current query/index/Room defaults**.

## Current database checkpoint

- Room database version: `10`;
- migration chain is contiguous `1 -> ... -> 10`;
- search uses FTS4 today;
- catalog/EPG publication is revision-based and previous-good data remains authoritative until activation;
- no schema migration is authorized by this inventory.

## Measurement profile

For catalog-shaped queries use the deterministic #27 profiles where the fixture can model the query truth:

- 1k channels — functional/small baseline;
- 10k channels — realistic large catalog;
- 50k channels — stress profile, not an automatic release threshold.

For Guide/NowNext add bounded EPG programme density profiles rather than multiplying AVDs. Record channel count, programme rows per channel/window and active EPG-match count in evidence.

For every query family capture at minimum:

- exact source SHA;
- DB schema version;
- corpus/profile identity and seed;
- query parameters/category, never secret-bearing raw provider values;
- warm/cold classification where meaningful;
- p50/p95 and sample count;
- `EXPLAIN QUERY PLAN` text/digest;
- DB/WAL size before/after when writes are involved;
- semantic result digest/count;
- failure/`SQLITE_BUSY` count where observable.

Where the measurement harness can expose them reliably, also capture temporary sort/B-tree evidence, statement/transaction duration, connection wait/hold time and allocation/GC cost outside SQLite.

---

## Q1 — Channels active browse

Source: `ChannelBrowseDao.pageActiveChannels()`.

### Static shape

The query joins:

`canonical_channels -> stream_variants -> provider_channels -> sources`

and left-joins `user_channel_overlays` by `(profileId, canonicalChannelId)`.

It then:

- filters provider rows to each source's `activeRevision`;
- filters hidden/favorites state;
- aggregates `MIN(groupTitle)`, `MIN(channelNumber)` and `COUNT(DISTINCT stream_variants.id)`;
- groups by canonical/overlay display state;
- orders by a computed numeric-channel expression using text checks/cast, then display name and canonical ID.

### Existing relevant index facts

- `stream_variants(providerChannelId)`;
- `stream_variants(canonicalChannelId)`;
- `provider_channels(sourceId)`;
- `provider_channels(sourceId, revisionNumber)`;
- overlay primary key `(profileId, canonicalChannelId)`.

These are facts, not proof that SQLite chooses the desired plan.

### Measure

- first page and sustained paging p50/p95;
- 1k/10k/50k profiles;
- favorites off/on;
- variant multiplicity 1 vs several variants per canonical channel;
- EQP for join order, grouping, `COUNT(DISTINCT)` and computed ORDER BY;
- evidence of temporary B-tree/sort if reported by EQP;
- Paging invalidation/reload cost separately from one SQL execution.

### May authorize

Only after evidence:

- query rewrite;
- persisted sortable projection/index, if the computed order is proven material;
- a narrowly targeted index.

### Does not authorize

- denormalizing channel numbers merely because ORDER BY looks complex;
- changing sort semantics;
- replacing Paging ownership;
- FTS or Room pool changes.

---

## Q2 — Recent channels browse

Source: `ChannelBrowseDao.pageRecentChannels()`.

### Static shape

Uses the same active catalog joins/aggregates as Channels plus `recent_channels`, filtered by profile and ordered by successful playback timestamp descending.

### Measure

- page latency with small and saturated Recent history;
- cost of active-revision eligibility joins;
- EQP for recent/profile lookup and active variant existence;
- result equivalence after source revision replacement.

### May authorize

A narrow recent-query/index change only if the query is material in an accepted CUJ.

---

## Q3 — Search candidate selection

Source: `ChannelSearchDao.selectCandidates()`.

Priority: **high** after M0.

### Static shape

The query includes:

- FTS `MATCH` over `search_documents_fts` joined to `search_documents`;
- direct candidate classification by document kind;
- active provider/variant `EXISTS` checks;
- active EPG match CTEs and match-count grouping;
- unambiguous EPG-match filtering;
- current-programme resolution using a correlated previous-programme subquery;
- open-ended programme validity via another `EXISTS`;
- programme-title candidate matching;
- `UNION ALL` direct + programme candidates;
- active-stream `EXISTS`;
- correlated profile overlay hidden lookup;
- optional bounded canonical-ID restriction;
- grouping/order/limit.

This complexity makes Search a strong measurement target; it does **not** prove a bad plan.

### Existing relevant index facts

- FTS4 search table exists;
- active provider/variant indices listed under Q1;
- overlay primary key `(profileId, canonicalChannelId)`;
- EPG programme index `(sourceId, revisionNumber, externalChannelId, startEpochMillis)`;
- EPG match indices include `(epgSourceId, epgRevisionNumber, epgExternalChannelId)`, `(providerSourceId, catalogRevisionNumber)` and `canonicalChannelId`.

### Measure

Use stable query classes rather than arbitrary user text in evidence:

- selective canonical-name query;
- broad/common-token query;
- provider/group query;
- current-programme title query;
- multi-token narrowing path;
- with/without restricted candidate IDs.

Capture:

- candidate query p50/p95;
- result-summary hydration p50/p95 separately;
- FTS hit count and final candidate count;
- number/cost of correlated subquery loops in EQP where available;
- sort/temp B-tree evidence;
- search-result digest/order;
- published boundary/revision used by the result.

#178 must be accepted before these timings are used for decisions.

### May authorize

One isolated experiment at a time:

- SQL/CTE rewrite;
- targeted composite index;
- FTS4 vs FTS5 experiment under #196;
- precomputed/search-document change only with correctness + write-cost evidence.

### Does not authorize

- automatic FTS5 migration;
- adding every column seen in WHERE to an index;
- changing Search matching/ranking semantics;
- merging EPG matching and Search ownership.

---

## Q4 — Search active-summary hydration

Source: `ChannelSearchDao.selectActiveChannelSummaries()`.

### Static shape

Hydrates bounded candidate IDs through active catalog joins, overlay state and aggregate provider metadata.

### Measure

- candidate list sizes across accepted search limits;
- hydration p50/p95;
- EQP for `IN (:canonicalChannelIds)` + active revision joins;
- variant multiplicity impact;
- output order/digest.

Keep this separate from FTS candidate latency so an optimization is attributed to the correct stage.

---

## Q5 — Guide channel window

Source: `GuideWindowDao.channelWindow()`.

Priority: **high** for Guide CUJ.

### Static shape

The query uses active catalog joins and aggregates plus a keyset-like cursor. Ordering is:

1. channel-number presence;
2. integer overlay channel number;
3. display name NOCASE;
4. canonical ID BINARY.

Cursor predicates mirror that ordering and the query groups provider/variant rows before the final limit.

### Measure

- first window and N sequential windows;
- cursor with numbered and unnumbered channels;
- 1k/10k/50k channel profiles;
- variant multiplicity;
- EQP/grouping/temp-sort evidence;
- Guide D-pad/window fetch latency separately from Compose rendering.

### May authorize

A query/sort projection/index change only if the DB stage is a measured Guide bottleneck.

---

## Q6 — Guide programme window

Source: `GuideWindowDao.programmeWindowSnapshot()` / `programmeRows()`.

Priority: **high** for populated Guide.

### Static shape

The transaction first counts active EPG matches and only proceeds for canonical channels with exactly one active match.

Programme rows then:

- join active EPG/catalog truth;
- join `epg_programmes`;
- enforce active playable variant existence;
- resolve an open-ended programme end using correlated `MIN(next_programme.startEpochMillis)`;
- repeat the end-resolution CASE in the overlap WHERE predicate;
- order by channel/start/source/revision/sequence;
- apply a bounded row limit.

### Existing relevant index fact

`epg_programmes(sourceId, revisionNumber, externalChannelId, startEpochMillis)` is a plausible access path for next-programme lookup, but only EQP/runtime evidence proves actual use/cost.

### Measure

- small/medium/dense programme windows;
- windows containing explicit stop times vs open-ended programmes;
- number of canonical channels in one Guide window;
- active-match count phase and programme-row phase separately;
- EQP for correlated next-programme lookup and ordering;
- p50/p95 + row count;
- result digest including computed programme bounds.

### May authorize

- deduplicating/restructuring end-time SQL;
- targeted programme/match index experiment;
- a bounded materialized projection only if repeated computation is proven dominant and write/migration cost is justified.

### Does not authorize

- assuming open-ended programmes are the bottleneck;
- changing programme boundary semantics;
- precomputing end time without migration/correctness evidence.

---

## Q7 — Now/Next projection

Source: `EpgGuideDao.projectionSnapshot()` / `programmeCandidates()`.

### Static shape

For accepted single-match channels, current/next resolution uses two correlated subqueries:

- previous programme ordered by start/sequence descending;
- next programme ordered ascending;

with active catalog/variant checks.

### Measure

- visible channel windows of realistic sizes;
- percentage with EPG/no EPG/ambiguous match;
- p50/p95 for match-count and programme-candidate phases;
- EQP for previous/next programme lookups;
- refresh/invalidation frequency during sustained Channels navigation;
- cancellation/relaunch count at repository/ViewModel boundary separately from SQL latency.

### May authorize

A query/index or caller-window stabilization experiment only after evidence identifies whether cost is SQL, invalidation frequency or UI orchestration.

---

## Q8 — Catalog stage and search-document writes

Source: `SourceRevisionDao.stageCatalogBatch()` and search-document upsert path.

### Static shape

Stages canonical/provider/variant records and provider search documents without changing active publication truth.

### Measure

- transaction/batch duration;
- rows per batch;
- total ingest staging duration;
- DB/WAL delta;
- search-document lookup/upsert cost;
- allocation/GC outside SQLite;
- 1k/10k/50k profiles.

### May authorize

Batch/query implementation changes that preserve staging atomicity and boundedness.

---

## Q9 — Catalog activation / canonical display publication

Source: `SourceRevisionDao.publishCanonicalDisplayMetadata()` and activation/cleanup path.

Priority: medium/high for refresh completion.

### Static shape

Canonical display publication performs an `UPDATE` whose value is selected from active stream/provider/source rows, with deterministic provider ordering, affected current/previous revision scope and active-variant existence checks.

Activation also updates revision/source truth and cleans old provider/search/revision rows.

### Measure

- staging -> activation transaction duration;
- number of affected canonical channels;
- publication UPDATE duration;
- cleanup duration by table;
- DB/WAL delta;
- EQP for publication SELECT/EXISTS and cleanup queries;
- correctness digest proving only accepted active revision is public.

### May authorize

A publication-query/index/batching change only when activation is a measured material cost.

### Does not authorize

- weakening immutable revision publication;
- exposing staging rows early;
- replacing deterministic metadata ownership with last-writer behavior.

---

## Experiment decision tree after #178

```text
trustworthy measurement
        |
        +-- query is not material --> keep current implementation
        |
        +-- query is material
               |
               +-- bad/expensive plan visible --> one SQL/index experiment
               |
               +-- plan is reasonable, contention observed --> #196 Room pool/context experiment
               |
               +-- Search index itself dominates --> isolated FTS4 vs FTS5 experiment
               |
               +-- storage/layout cost dominates --> isolated WITHOUT ROWID/table experiment
```

Never combine those branches in one optimization PR.

## Stop condition while runner is unavailable

This inventory is complete enough to define future evidence. Do **not** edit production DAO SQL, entity indices, Room pool settings or schema from this document alone.