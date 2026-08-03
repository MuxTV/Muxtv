# Bounded TV Search Design

**Status:** design review required before production implementation  
**Date:** 2026-08-03  
**Issue:** #29  
**Accepted design base:** `main@7e1f18f31ab8628a104f2668d87e6478d7559242` (post-PR #90)  
**Implementation base:** accepted post-Favorites `main`

## 1. Goal

Replace the current Search placeholder with a TV-first, profile-scoped search that finds active visible channels by:

- effective channel name;
- provider/raw channel name;
- effective channel number;
- group title;
- title of the programme that is **currently active under the accepted Now/Next semantics**.

The feature must remain bounded at the API/UI boundary, must not materialize the whole catalog or guide in Compose, and must preserve canonical channel identity and the existing process-owned Player.

## 2. Non-goals

This slice does **not** introduce:

- FTS5 or another search index;
- fuzzy/transliteration/ML ranking;
- recommendations;
- programme-detail search outside the active programme;
- provider-specific search APIs;
- a second catalog or EPG state owner;
- Paging solely for Search;
- a global/custom focus engine;
- a Room migration unless measurement later proves the initial relational design inadequate;
- Rust/UniFFI/native search.

## 3. Existing contracts to preserve

### Playback catalog

`PlaybackCatalog` remains the active-channel/playback boundary. `ChannelQuery` already supports bounded text filtering for effective name, provider raw name and group, but does not cover effective channel number or active programme metadata.

Search must **not** turn `PlaybackCatalog.observeChannels()` into an EPG-dependent API. That would make the catalog boundary time-dependent on programme transitions and duplicate guide ownership.

### EPG guide

Search must inherit the accepted `EpgGuideRepository` / `RoomEpgGuideRepository` semantics:

- EPG source revision equals `epg_sources.activeRevision`;
- provider/catalog revision equals `sources.activeRevision`;
- match policy equals `CURRENT_EPG_MATCH_POLICY_VERSION`;
- only `decision = MATCHED` rows participate;
- hidden channels remain excluded;
- multiple active matches produce conflict semantics, never a weak winner;
- an open-ended previous programme is current only when its effective end is known from the next programme.

The final point is important: `stop == null` does **not** mean “current forever”.

### Navigation and playback

`AppDestination.Search` already exists. Search opens the existing `AppDestination.Player(channelId)`. Search never resolves or installs media itself.

## 4. Chosen architecture

Add one explicit cross-cutting read projection:

```kotlin
interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}
```

This repository is intentionally separate from both `PlaybackCatalog` and `EpgGuideRepository`: Search combines canonical catalog metadata and current EPG metadata for a single read-only use case.

No write API is added.

### 4.1 Query contract

```kotlin
class ChannelSearchQuery(
    val profileId: String,
    text: String,
    val nowEpochMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    val normalizedText: String = text.trim()

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
    }
}
```

Rules:

- `profileId` is nonblank;
- `nowEpochMillis >= 0`;
- `limit in 1..200`;
- blank normalized text returns an empty snapshot and never becomes an unfiltered catalog query;
- one-character queries are allowed, but output is still hard-bounded;
- public `toString()` redacts both profile ID and query text;
- diagnostics may expose only safe metadata such as text-length bucket and limit.

The repository converts the normalized text into a bound LIKE pattern. Literal `%`, `_` and `\` are escaped. User text is never interpolated into SQL.

### 4.2 Result contract

```kotlin
data class ChannelSearchResult(
    val channel: PlayableChannelSummary,
    val currentProgrammeTitle: String?,
)

data class ChannelSearchSnapshot(
    val results: List<ChannelSearchResult>,
    val nextBoundaryEpochMillis: Long?,
)
```

`nextBoundaryEpochMillis` is the earliest future programme-time boundary that can change Search membership or displayed programme metadata for the profile. It is null when no such active guide boundary exists.

The result contract intentionally does not publish locators, source identifiers, match evidence, query text or raw failure information.

## 5. Room projection

Implement a dedicated Search DAO/repository in `core:database`. Do not perform catalog/EPG joins in Compose or the ViewModel.

### 5.1 Active channel projection

Project active visible canonical channels from the existing source/catalog tables:

- canonical channel ID;
- effective display name = overlay custom name or canonical display name;
- representative logo;
- representative group title;
- effective channel number = overlay number or provider number;
- favorite bit;
- active variant count.

Required predicates:

- provider row belongs to `sources.activeRevision`;
- hidden overlay is false/missing;
- at least one active stream variant exists;
- projection is profile-scoped.

No new persisted Search table is introduced.

### 5.2 Current-policy unambiguous EPG projection

Build EPG candidates only from match rows satisfying all accepted provenance constraints:

- active EPG revision;
- active provider/catalog revision;
- current matching-policy version;
- `decision = MATCHED`;
- non-null canonical channel ID.

Group match evidence per canonical channel. Programme metadata contributes to Search only where the effective active match count is exactly one. Two or more active matches preserve `SOURCE_CONFLICT` behavior and supply **no** programme text.

For that single mapping, derive `previous` and `next` candidates exactly like the accepted Now/Next projection:

```text
previous = latest programme where start <= now
next     = earliest programme where start > now
```

Then derive current programme with the same effective-end rules:

```text
if previous.stop != null && previous.stop > now:
    effectiveEnd = previous.stop
else if previous.stop == null && next != null && next.start > now:
    effectiveEnd = next.start
else:
    effectiveEnd = null

current = previous only when effectiveEnd != null && effectiveEnd > previous.start
```

Consequences:

- a stopped programme is not current;
- a future programme is not current;
- an open-ended programme with a following programme is bounded by that next start;
- an open-ended programme with no following programme is **not** treated as current forever;
- overlapping data follows the same deterministic previous/next ordering as Now/Next.

Search must be characterized against the accepted `RoomEpgGuideRepository` behavior so these semantics cannot drift independently later.

### 5.3 Search predicate

A channel matches if normalized text occurs in any of:

1. effective display name;
2. provider/raw channel name;
3. effective channel number;
4. group title;
5. current programme title from the unambiguous projection above.

All LIKE terms use the same escaped bound pattern. Hidden/inactive channels never enter the candidate set.

### 5.4 Deterministic ordering

Ranking is a private DAO concern, not a public relevance-score API.

Initial ordering:

1. exact effective channel-number match;
2. exact effective display-name match (`NOCASE`);
3. effective display-name prefix;
4. provider/raw-name prefix;
5. remaining name/group/current-programme contains matches;
6. stable tie-break by effective numeric channel number where parseable, display name `NOCASE`, then canonical channel ID.

If ranks 2–5 make the SQL disproportionately complex, the first implementation may collapse them into fewer deterministic classes. The public API must not depend on the internal rank representation.

### 5.5 Hard output bound

Every row query has `LIMIT :limit`; the public maximum is 200.

This bounds materialized results, not the cost of a `%contains%` scan. Scan cost is measured before any FTS decision.

## 6. Programme-time invalidation

Search cannot rely only on Room writes: a programme can become current or cease to be current when time crosses a boundary with no database mutation.

The repository therefore combines:

1. a Room-invalidated Search-row flow;
2. a Room-invalidated scalar `nextBoundaryEpochMillis` flow.

The boundary aggregate uses the **same previous/next/effective-end semantics** as section 5.2 across active, current-policy, unambiguous mappings for the profile. Candidate boundaries are:

- effective end of the current programme;
- start of the next programme.

Only future boundaries (`> now`) participate; take the minimum.

The Search ViewModel owns the clock. When a snapshot publishes a boundary, it schedules exactly one cancellable wake-up, rebuilds the query with the new `nowEpochMillis`, and cancels that job whenever query generation changes. No periodic polling loop is required.

Catalog/EPG database changes naturally re-emit the Room flows.

## 7. Search ViewModel

Add a destination/back-stack-scoped `SearchViewModel` in a new `feature:search` module.

Suggested immutable UI state:

```kotlin
sealed interface SearchUiState {
    data object EmptyQuery : SearchUiState
    data object Loading : SearchUiState
    data class Content(val rows: List<SearchRowProjection>) : SearchUiState
    data object NoResults : SearchUiState
    data object Failed : SearchUiState
}
```

Separate screen inputs/state:

- `queryText: StateFlow<String>`;
- focused canonical channel ID + previous index as route/saveable state;
- query generation counter/token internal to the ViewModel.

Behavior:

- initial query is blank;
- nonblank normalized input is debounced by **300 ms**;
- a newer normalized query cancels the previous repository collection and boundary job;
- blank input immediately returns to `EmptyQuery`;
- stale results from an older generation cannot overwrite a newer query;
- same-query data/boundary refresh should preserve existing Content while replacement data loads, avoiding a focus-tree flash;
- failures expose typed/payload-free state, never raw exception/query text.

The 300 ms debounce is a UI default, not a storage contract.

## 8. TV UI and focus contract

Replace the Search placeholder with one restrained screen:

- title `Поиск`;
- one text-entry control;
- result count/status copy;
- one lazy vertical results list;
- rows show existing data only: number, favorite marker, channel name, group and current programme title when available.

No preview pane, programme artwork, recommendation rail or second content column is part of this slice.

Focus rules:

1. Search opens with the query field focused.
2. `Down` from the query field goes to the first result when results exist.
3. `Up` from the first result goes back to the query field.
4. Lower rows keep standard vertical D-pad traversal.
5. `OK` on a result opens the existing Player directly.
6. Player → Back restores query text and the same surviving canonical channel.
7. If that channel disappears, focus falls back to the nearest previous result.
8. If no result survives, focus returns to the query field.
9. Recomposition/query refresh must not create another global focus owner or focus engine.

A Search-local stable-ID focus anchor is acceptable. Do not generalize it into a framework until another distinct screen proves the abstraction useful.

## 9. Expected module/wiring changes after approval

- add `:feature:search` in `settings.gradle.kts`;
- `catalog/api/.../ChannelSearchRepository.kt`;
- `core/database/.../ChannelSearchDao.kt`;
- `core/database/.../RoomChannelSearchRepository.kt`;
- existing database component wiring, with no schema bump expected;
- `feature/search/.../SearchViewModel.kt`;
- `feature/search/.../SearchRoute.kt`;
- app DI wiring;
- replace `AppDestination.Search -> PlaceholderRoute("Поиск")` with `SearchRoute`.

Search logic does not belong in `MainActivity` or navigation lambdas.

## 10. Correctness tests

### API/query

- trim/blank normalization;
- min/max limit;
- redacted `toString()`;
- literal escaping of `%`, `_`, `\`.

### Room

At minimum:

- effective custom-name match;
- provider/raw-name match;
- group match;
- overlay channel-number match;
- provider channel-number match;
- current programme-title match;
- past/future programme excluded;
- explicit-stop current programme behavior matches Now/Next;
- open-ended + next programme behavior matches Now/Next;
- open-ended + no next programme does not become infinite current;
- stale EPG revision excluded;
- stale matching-policy rows excluded;
- ambiguous/source-conflict mapping supplies no programme text;
- hidden channel excluded even when programme text matches;
- inactive catalog revision excluded;
- profile-overlay isolation;
- deterministic ordering and hard limit;
- escaped wildcard characters remain literal;
- next-boundary aggregate follows the accepted Now/Next boundary semantics.

### ViewModel

- blank query does not execute an unfiltered repository search;
- 300 ms debounce coalesces rapid input;
- newer query cancels older generation;
- stale repository/data result cannot overwrite newer query;
- one boundary reload is scheduled and cancelled correctly;
- blanking query cancels pending work;
- same-query refresh does not flash through destructive Loading;
- failure state is payload-free.

### TV instrumentation

- initial query focus;
- type/search → Down → first result;
- first result Up → query;
- OK → Player → Back restores query + same result;
- removed focused result falls back deterministically;
- no-results returns focus to query;
- active-programme search membership changes at a controlled boundary;
- API26/API36 product journey after implementation.

## 11. Performance acceptance and FTS gate

Initial implementation uses ordinary Room/SQLite relational queries with hard-bounded output.

Before introducing FTS5, measure representative Search queries against deterministic 1k/10k/50k catalog profiles and bounded EPG fixtures where applicable. Record:

- wall time;
- result count;
- allocations where meaningful;
- query-plan/index usage;
- exact number, name prefix, contains, programme contains and no-match patterns.

Measurements are descriptive until variance is understood.

FTS5 becomes eligible only if repeated evidence shows the relational query is a material daily-use latency/CPU bottleneck. A later FTS implementation requires a separate migration/index-consistency design and remains derived from canonical Room truth.

## 12. Security/privacy

Search state, logs, diagnostics and semantics must never expose:

- playlist/stream locators;
- query tokens, cookies or Authorization values;
- source credentials;
- raw exception messages;
- user search text in diagnostics.

SQL uses bound parameters only. Safe diagnostics may record query-length bucket, result count, duration and typed failure category.

## 13. Alternatives rejected

### Extend `PlaybackCatalog.observeChannels()` with EPG joins

Rejected: it makes a catalog/playback boundary time-dependent on EPG and duplicates guide ownership.

### Run separate catalog and EPG searches and merge in the ViewModel

Rejected: it over-fetches, makes global limits/ranking ambiguous and moves database join semantics into presentation code.

### FTS5 immediately

Rejected until repeated evidence proves ordinary bounded SQL inadequate.

### Full catalog/guide filtering in Compose

Rejected because it violates issue #29 bounded-memory architecture.

## 14. Self-review result

The initial draft incorrectly treated `stop == null` as sufficient for a programme to remain current. Review against accepted `RoomEpgGuideRepository` found the divergence and this revision removes it.

After correction:

- Search adds one read-only projection, not a new source of truth;
- canonical channel identity, overlays, current EPG provenance and Player ownership remain unchanged;
- no initial Room migration is required;
- output is hard-bounded and full materialization is prohibited;
- programme search and boundary scheduling explicitly inherit accepted Now/Next semantics, including open-ended data;
- hidden/inactive/stale-policy/ambiguous guide data cannot leak into programme search;
- focus remains local and stable-ID based;
- FTS and native/Rust work remain evidence-gated.

## 15. Approval gate

Production implementation must not begin until this corrected design is reviewed and approved. After approval, write a task-level implementation plan and implement Search from the then-accepted `main` on a fresh branch.