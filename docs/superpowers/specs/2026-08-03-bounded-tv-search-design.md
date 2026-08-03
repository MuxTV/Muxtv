# Bounded TV Search Design

**Status:** design review required before production implementation  
**Date:** 2026-08-03  
**Issue:** #29  
**Accepted base:** `main@7e1f18f31ab8628a104f2668d87e6478d7559242` (post-PR #90)  
**Expected implementation base:** accepted post-Favorites `main`

## 1. Goal

Replace the current Search placeholder with a TV-first, profile-scoped search that can find an active visible channel by:

- effective channel name;
- provider/raw channel name;
- effective channel number;
- group title;
- title of the programme that is active at the query time.

The implementation must remain bounded at the API/UI boundary, must not materialize the complete catalog or guide in Compose, and must preserve the existing canonical channel identity and Player ownership.

## 2. Non-goals

This slice does **not** introduce:

- FTS5 or another search index;
- fuzzy/transliteration/ML ranking;
- recommendations;
- programme-detail search outside the active programme;
- provider-specific search APIs;
- a second catalog or EPG state owner;
- Paging solely for Search;
- a new global focus engine;
- Room schema changes unless measurement proves the bounded SQL design inadequate;
- Rust/UniFFI/native search.

## 3. Existing contracts to preserve

### Playback catalog

`PlaybackCatalog` remains the read/playback boundary for active canonical channels and variants. Its existing `ChannelQuery` already supports bounded text filtering for effective name, provider raw name and group, but it does not cover effective channel number or active programme metadata.

Search must not silently turn `PlaybackCatalog.observeChannels()` into an EPG-dependent API. Doing so would couple the catalog boundary to programme-time invalidation and duplicate guide ownership.

### EPG guide

`EpgGuideRepository` remains the Now/Next guide projection boundary. Existing Room guide queries already enforce:

- active EPG revision;
- active provider/catalog revision;
- current `CURRENT_EPG_MATCH_POLICY_VERSION`;
- `decision = MATCHED`;
- hidden-channel exclusion;
- conflict semantics where multiple active matches are not treated as a weak winner.

Search must use the same provenance/freshness semantics.

### Navigation and playback

`AppDestination.Search` already exists. Player remains process-owned Media3. Search opens the existing `AppDestination.Player(channelId)` and never resolves/installs media itself.

## 4. Chosen architecture

Add one explicit cross-cutting read projection:

```kotlin
interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}
```

This is intentionally separate from both `PlaybackCatalog` and `EpgGuideRepository` because Search combines catalog metadata and active programme metadata for one read-only use case.

No write APIs are added.

### 4.1 Public query

```kotlin
class ChannelSearchQuery(
    val profileId: String,
    val text: String,
    val nowEpochMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    val normalizedText: String

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
    }
}
```

Rules:

- `profileId` must be nonblank;
- `nowEpochMillis >= 0`;
- `limit in 1..200`;
- `text.trim()` is the normalized query;
- blank normalized text produces an empty snapshot and does not run an unfiltered catalog search;
- one-character queries are allowed, but output remains hard-bounded;
- `toString()` redacts profile ID and search text and reports only `hasText`, length class and limit.

The DAO receives a bound, escaped LIKE pattern. `%`, `_` and `\` from user input are escaped; SQL injection through query text is impossible because values remain bound parameters.

### 4.2 Public result

```kotlin
data class ChannelSearchResult(
    val channel: PlayableChannelSummary,
    val currentProgrammeTitle: String?,
    val currentProgrammeEndEpochMillis: Long?,
)

data class ChannelSearchSnapshot(
    val results: List<ChannelSearchResult>,
    val nextBoundaryEpochMillis: Long?,
)
```

`currentProgrammeEndEpochMillis` is projection metadata needed for correct time-based invalidation; it is not persisted as Search state.

`nextBoundaryEpochMillis` is the earliest EPG time boundary that can change Search membership or displayed active-programme metadata for the current profile. It may be null when no active guide boundary exists.

Public `toString()` implementations must not expose channel/programme/user query text.

## 5. Room projection

Add a dedicated Search DAO/repository implementation in `core:database`. Do not reuse Compose-side filtering.

### 5.1 Channel projection CTE

First project active visible canonical channels from the existing source/catalog tables:

- canonical channel ID;
- effective display name = overlay custom name or canonical display name;
- representative logo/group;
- effective channel number = overlay number or provider number;
- favorite bit;
- active variant count.

Required predicates:

- provider channel revision equals `sources.activeRevision`;
- hidden overlay is false/missing;
- at least one active stream variant exists.

The projection is profile-scoped.

### 5.2 Unambiguous active EPG projection

Build a second CTE from current EPG match provenance:

- EPG source revision equals `epg_sources.activeRevision`;
- provider/catalog revision equals `sources.activeRevision`;
- matching policy equals `CURRENT_EPG_MATCH_POLICY_VERSION`;
- decision is `MATCHED`;
- canonical channel ID is non-null.

Group active match evidence per canonical channel and expose programme data only where the effective match count is exactly one. Multiple active matches preserve existing `SOURCE_CONFLICT` semantics and do **not** contribute programme text to Search.

For the single unambiguous mapping, project the programme active at `nowEpochMillis`:

```text
start <= now && (stop is null || now < stop)
```

If malformed/overlapping programme rows can produce more than one active candidate, select deterministically using the same ordering rules as the accepted guide projection rather than returning duplicate Search rows.

### 5.3 Search predicate

A channel matches if normalized text occurs in any of:

1. effective display name;
2. provider/raw name;
3. effective channel number;
4. group title;
5. current unambiguous programme title.

All textual LIKE terms use the same escaped pattern. Hidden and inactive channels never enter the candidate projection.

### 5.4 Deterministic ordering

Search ranking is private implementation detail, not a public API enum. Initial ordering:

1. exact effective channel-number match;
2. exact effective display-name match (case-insensitive);
3. effective display-name prefix match;
4. provider/raw-name prefix match;
5. remaining name/group/programme contains matches;
6. stable tie-break by effective numeric channel number where parseable, display name `NOCASE`, then canonical channel ID.

The first implementation may simplify ranks 2–5 if SQLite expression complexity becomes excessive, but ordering must remain deterministic and tested. No relevance score is persisted.

### 5.5 Hard bound

The final row query always has `LIMIT :limit`, with API max 200.

A bounded result does not imply bounded SQLite scan cost for `%contains%`. That is accepted for the first implementation and is the reason measurement is required before deciding on FTS.

## 6. Time and invalidation semantics

Search must not become stale when the clock crosses a programme boundary without a DB write.

The repository snapshot therefore contains `nextBoundaryEpochMillis` computed as a scalar aggregate over current-policy, active-revision, unambiguous EPG mappings for the profile. The boundary is the minimum future value that can change the active programme projection, including:

- end of the current programme;
- start of the next programme where no bounded current end exists.

Implementation may expose two Room invalidation flows internally (search rows + next boundary) and combine them into one snapshot.

The Search ViewModel owns the wall clock and schedules one cancellable reload for the published boundary, following the same generation/staleness discipline already used by Channels Now/Next. A new query cancels the old boundary job.

EPG/catalog Room invalidation naturally re-emits the repository flow. No polling loop is required.

## 7. ViewModel state

Add a destination/back-stack-scoped `SearchViewModel` in a new `feature:search` module.

Suggested immutable state:

```kotlin
sealed interface SearchUiState {
    data object EmptyQuery : SearchUiState
    data object Loading : SearchUiState
    data class Content(val rows: List<SearchRowProjection>) : SearchUiState
    data object NoResults : SearchUiState
    data object Failed : SearchUiState
}
```

Separate durable screen inputs:

- `queryText: StateFlow<String>`;
- focused canonical channel ID / previous index as saveable route state, not repository state.

Behavior:

- initial query is blank;
- normalize/debounce nonblank text by **300 ms** before creating `ChannelSearchQuery`;
- a new normalized query cancels the previous repository collection and boundary job;
- blank query immediately returns to `EmptyQuery`;
- do not publish raw exceptions or query text through failure objects;
- preserve current Content during a same-query boundary/data refresh when possible instead of flashing Loading.

The 300 ms debounce is a product default, not a storage contract, and can be tuned later without schema/API changes.

## 8. TV UI and focus contract

Replace the Search placeholder with one restrained TV screen:

- title `Поиск`;
- one text-entry control;
- result count/status copy;
- one lazy vertical result list;
- each row displays existing data only: number, name, group/favorite marker and current programme title when available.

Do not add preview panes, programme artwork or a second column in this slice.

### Focus rules

1. Initial Search entry focuses the query field.
2. `Down` from the query field moves to the first result when results exist.
3. `Up` from the first result returns to the query field.
4. Ordinary rows keep default vertical traversal.
5. `OK` on a result opens the existing Player directly.
6. Player → Back restores the query and the same surviving canonical channel.
7. If the focused channel disappears from results after a refresh, fall back to nearest previous result; if no result remains, focus the query field.
8. Recomposition/query refresh must not introduce a new global focus owner or custom focus engine.

A small Search-local stable-ID focus anchor is acceptable. Do not generalize it into a framework until a second distinct screen proves a reusable abstraction is needed.

## 9. Module/wiring changes expected after approval

Expected production files/modules:

- add `feature:search` to `settings.gradle.kts`;
- `catalog/api/.../ChannelSearchRepository.kt`;
- `core/database/.../ChannelSearchDao.kt`;
- `core/database/.../RoomChannelSearchRepository.kt`;
- database component wiring (no schema version change expected);
- `feature/search/.../SearchViewModel.kt`;
- `feature/search/.../SearchRoute.kt`;
- app DI module/wiring;
- replace `AppDestination.Search -> PlaceholderRoute("Поиск")` with `SearchRoute`.

Tests live adjacent to the relevant modules. Do not put Search logic into `MainActivity` or navigation lambdas.

## 10. Correctness tests

### API/query tests

- normalization/blank behavior;
- limit min/max;
- redacted `toString()`;
- LIKE escaping for `%`, `_`, `\`.

### Room tests

At minimum:

- effective custom name match;
- provider/raw name match;
- group match;
- overlay channel-number match;
- provider channel-number match;
- active current-programme title match;
- future/past programme does not match as current;
- stale EPG revision excluded;
- stale matching-policy rows excluded;
- ambiguous/current `SOURCE_CONFLICT` mapping does not supply programme text;
- hidden channel excluded even when programme matches;
- inactive catalog revision excluded;
- deterministic ordering and hard limit;
- profile overlay isolation;
- escaped wildcard characters are literal.

### ViewModel tests

- blank query does not hit repository;
- 300 ms debounce coalesces rapid input;
- newer query cancels older generation;
- repository/data refresh cannot overwrite newer query results;
- EPG boundary schedules one reload and cancellation is clean;
- blanking query cancels pending work;
- failure state is payload-free.

### TV instrumentation

- initial query focus;
- type/search → Down → first result;
- result Up → query;
- OK → Player → Back restores query + same result;
- result removal uses nearest-previous fallback;
- no-results returns focus to query;
- active-programme match appears/disappears after controlled boundary refresh;
- API26 and API36 product journey once implementation is complete.

## 11. Performance acceptance

The first implementation uses ordinary indexed/relational Room SQL plus bounded result output. Before introducing FTS5:

1. measure representative Search queries against deterministic 1k/10k/50k catalog fixtures with EPG where available;
2. record wall time, allocations where meaningful, result count and SQLite query-plan/index usage;
3. include worst useful patterns (number exact, name prefix, contains, programme contains, no-match);
4. keep measurements descriptive until variance is known.

FTS becomes eligible only if repeated evidence shows the bounded relational query is a material daily-use latency/CPU problem. If FTS is adopted later, it requires its own migration/index-consistency design and must remain derived from canonical Room truth.

## 12. Security/privacy

Search must never expose in state/logs/semantics:

- playlist or stream locators;
- query tokens/cookies/Authorization values;
- source credentials;
- provider/source identifiers not already part of an intentional visible label;
- raw exception messages;
- the user's search text in diagnostics.

Search SQL uses bound parameters only. Diagnostic state may record query length bucket, result count, duration and typed failure category.

## 13. Alternatives rejected

### A. Extend `PlaybackCatalog.observeChannels()` to join EPG

Rejected. It makes a catalog/playback boundary time-dependent on EPG programme transitions and duplicates guide ownership.

### B. Run separate catalog and EPG searches and merge in ViewModel

Rejected. It over-fetches, makes the global result limit/ranking ambiguous, duplicates canonical IDs and moves database join semantics into presentation code.

### C. FTS5 immediately

Rejected until repeated evidence shows ordinary bounded SQL is insufficient. FTS adds schema/migration/index-consistency ownership before a bottleneck is proven.

### D. Load all channels/guide rows and filter in Compose

Rejected. Violates the existing bounded architecture and issue #29 acceptance criteria.

## 14. Self-review

- The design adds one read-only projection, not a second source of truth.
- Canonical channel identity, overlays, current EPG provenance and process-owned Player remain unchanged.
- No Room migration is required by the initial design.
- Search result output is hard-bounded; full catalog/guide materialization is prohibited.
- Time-based programme search has an explicit boundary invalidation mechanism rather than a stale static `now`.
- Hidden/inactive/stale-policy/ambiguous guide data cannot leak into programme search.
- TV focus is local and stable-ID based without adding a framework.
- FTS and native/Rust work remain evidence-gated.

## 15. Approval gate

Production implementation must not begin until this design is reviewed and approved. After approval, write a task-level implementation plan and implement Search on a fresh branch from the then-accepted `main`.