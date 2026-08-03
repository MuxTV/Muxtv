# Bounded TV Search Design

**Status:** written design review required before production implementation  
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
- title of the programme that is currently active under the accepted Now/Next semantics.

Search must be Unicode/case correct for Russian and other non-ASCII text, bounded at every public/database projection boundary, D-pad/IME safe on TV devices, and must preserve canonical channel identity plus the existing process-owned Player.

## 2. Comparative research conclusions

The design was reviewed against a broad sample of TV, IPTV, media-library and search-first applications. The intent is to combine transferable contracts, not clone one implementation.

### Jellyfin Android TV

Most relevant TV interaction reference. Current Search separates debounced typing from immediate submit, trims/deduplicates normalized queries, cancels stale search work, preserves input with saveable state and explicitly moves focus away from the text field on submit because Amazon fullscreen keyboards can otherwise remain trapped.

Adopt:

- debounced typing + immediate IME submit;
- normalized-query dedupe and generation cancellation;
- explicit input/results focus graph;
- explicit submit focus escape without arbitrary delays.

Do not copy its parallel per-media-type server searches; MuxTV has one local canonical result identity.

### IPTVnator

Useful IPTV reference for global/category search, large-playlist lazy behavior, channel-number navigation and preserving search phrase/results when navigating back from a result.

Adopt:

- query/focus continuity over Player -> Back;
- number-first intent;
- hard/lazy bounds for large playlists.

Do not add Live/VOD/Series federation before those domains exist in MuxTV.

### Tvheadend / Kodi / MythTV

These systems confirm that EPG search is a first-class backend/time-domain operation rather than Compose-side filtering. Tvheadend exposes explicit current-time and limit/sort/filter contracts. Kodi has long supported EPG/global search and continues to fix PVR EPG-search behavior. MythTV separates simple search dimensions, shows result position/count and preserves stored prior searches.

Adopt:

- explicit `nowEpochMillis` semantics;
- backend filtering, deterministic ordering and bounded results;
- continuity and honest result/truncation UI.

Keep advanced field filters and saved-search management out of this first slice.

### Navidrome

Demonstrates that SQLite FTS and a derived index are a sound large-library architecture. Its FTS5/BM25 design is not copied directly because Android driver compatibility and MuxTV's structured ranking differ.

### Immich

Two negative lessons are directly applicable:

- optional enrichment joined as mandatory data can make ordinary metadata results disappear;
- hard-capped result sets without clear truncation/pagination semantics create misleading UX.

Therefore optional EPG can never be required for a channel-name/number/group result, and a bounded MuxTV result set exposes truncation explicitly.

### NewPipe

Shows that search interaction must match query cost. Its remote provider searches are expensive enough that filtering/search timing matters. MuxTV's v1 path is local Room data, so debounced live search is appropriate; any future remote-provider search must be a separate ownership/cost model.

### ErsatzTV / PhotoPrism / Audiobookshelf

They reinforce three decisions:

- default free-text fields should be curated instead of "search everything";
- user-visible query languages quickly create escaping/parser complexity;
- international/diacritic/case behavior is correctness, not cosmetic polish.

### Kvaesitso / Lawnchair

Provider/plugin abstractions make sense when federated search is the product. They are YAGNI for one local MuxTV channel/EPG boundary and are rejected for this slice.

### Threadfin and other IPTV managers

Active/inactive channel separation, groups and channel numbers remain part of canonical visibility policy. A derived index must never bypass active-revision/hidden-channel truth.

## 3. Critical SQLite/Unicode decision

The earlier LIKE-based design was rejected during research.

Stock SQLite `LIKE`, `NOCASE`, `lower()` and `upper()` do not provide full Unicode case folding; case-insensitive behavior is effectively ASCII-focused. That is unacceptable for Russian channel/programme names.

Room 3 supports FTS4 and FTS5. FTS5 availability on Android is driver-dependent and is guaranteed by `BundledSQLiteDriver`; MuxTV currently uses the platform/default Room database builder and has intentionally deferred bundled SQLite as a broader runtime/packaging change.

The initial Search therefore uses:

- **Room v9**;
- derived **FTS4** index;
- SQLite **`unicode61`** tokenizer;
- final active/current-policy validation against canonical Room truth.

`unicode61` is available on SQLite 3.7.13+ and performs Unicode 6.1 simple case folding. Android API26 is well above that SQLite generation; API26/API36 device acceptance still proves actual runtime availability.

FTS5/BM25 remains a later ADR option, not an initial requirement.

## 4. Non-goals

This slice does not introduce:

- bundled SQLite / database-driver migration;
- FTS5/BM25;
- trigram/middle-of-token contains search;
- fuzzy spelling correction or transliteration;
- vector/semantic/ML search;
- recommendations;
- future-programme/detail search outside the current programme;
- remote provider search;
- Live/VOD/Series federation;
- user-visible Boolean/field query syntax;
- a search-provider/plugin framework;
- Paging solely for the first Search screen;
- a global/custom focus engine;
- Rust/UniFFI/native search.

## 5. Existing ownership to preserve

### PlaybackCatalog

Remains the active-channel/playback boundary. Search does not make it time-dependent on EPG and never resolves/installs media. Search opens the existing `AppDestination.Player(channelId)`.

### EpgGuideRepository

Search inherits exactly the accepted guide provenance and current-programme semantics:

- active EPG revision;
- active provider/catalog revision;
- `CURRENT_EPG_MATCH_POLICY_VERSION`;
- `decision = MATCHED` only;
- hidden channel exclusion;
- two or more active mappings => conflict, never a weak winner;
- open-ended previous programme is current only when its effective end is known from the next programme.

`stop == null` never means "current forever".

### Derived-index invariant

FTS/content tables are derived search structures only. A stale FTS row can at worst produce a candidate; it cannot make stale catalog/EPG data visible because every candidate is revalidated against active canonical truth before publication.

## 6. Public API

```kotlin
interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}
```

```kotlin
class ChannelSearchQuery(
    val profileId: String,
    text: String,
    val nowEpochMillis: Long,
    val limit: Int = DEFAULT_LIMIT,
) {
    val normalizedText: String

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        const val MAX_TOKENS = 6
    }
}
```

Rules:

- profile ID nonblank;
- `nowEpochMillis >= 0`;
- `limit in 1..200`;
- trim and collapse user whitespace only; do **not** apply NFKC/NFKD to the query unless the indexed text is normalized identically;
- blank => empty snapshot and no unfiltered catalog query;
- at most six searchable tokens; extra tokens are rejected/ignored deterministically by the encoder according to one tested rule;
- public diagnostics redact profile and text.

```kotlin
data class ChannelSearchResult(
    val channel: PlayableChannelSummary,
    val currentProgrammeTitle: String?,
)

data class ChannelSearchSnapshot(
    val results: List<ChannelSearchResult>,
    val isTruncated: Boolean,
    val nextBoundaryEpochMillis: Long?,
)
```

`isTruncated` is mandatory. If completeness cannot be proven because a candidate cap was reached, Search reports truncation instead of a false total.

## 7. Query encoding

The public query language is plain text only.

`SearchQueryEncoder`:

1. walks Unicode code points;
2. extracts non-empty letter/number token runs compatible with the intended `unicode61` word model;
3. caps tokens at `MAX_TOKENS`;
4. encodes each token as one safe FTS4 token-prefix expression;
5. never forwards raw user syntax as an FTS expression.

FTS4 prefix search uses `token*`. Because application tokens contain only accepted letter/number code points, operators such as `AND`, `OR`, `NEAR`, quotes, `%`, `_` and punctuation from the original user text are never exposed as operators.

Intended behavior:

- `рос` can match `Россия` regardless of Cyrillic case;
- `Россия 1` contains two query tokens;
- punctuation acts as a separator;
- arbitrary middle substring `осс` -> `Россия` is not guaranteed in v1.

## 8. Room v9 derived search index

### 8.1 Content table

Add a normal Room entity conceptually containing:

```text
search_documents
- rowId INTEGER PRIMARY KEY
- documentKey TEXT UNIQUE
- kind TEXT
- canonicalChannelId TEXT nullable
- profileId TEXT nullable
- providerChannelId TEXT nullable
- epgSourceId TEXT nullable
- epgRevisionNumber INTEGER nullable
- epgExternalChannelId TEXT nullable
- epgProgrammeSequence INTEGER nullable
- text TEXT
```

Initial internal kinds:

- `CANONICAL_NAME`;
- `PROVIDER_RAW_NAME`;
- `PROVIDER_GROUP`;
- `PROVIDER_NUMBER`;
- `OVERLAY_CUSTOM_NAME`;
- `OVERLAY_NUMBER`;
- `EPG_PROGRAMME_TITLE`.

### 8.2 External-content FTS4

Use Room 3 `@Fts4` with:

- `contentEntity = SearchDocumentEntity`;
- tokenizer `unicode61`;
- only display text indexed;
- origin/profile/kind fields stay in the content table and are joined by rowid.

Do not add FTS prefix indexes in the correctness-first commit. SQLite FTS4 can execute prefix queries without them; the `prefix=` option is a size/write-time tradeoff and is considered only after measurement.

### 8.3 Population/lifecycle

Search documents are maintained at existing data ownership boundaries:

- canonical channel create/name update -> canonical-name doc;
- provider staging/import -> raw-name/group/number docs;
- overlay custom name/number mutation -> overlay docs;
- EPG programme staging/import -> nonblank programme-title doc;
- revision cleanup -> delete matching derived docs.

Indexing staged/retained revisions is acceptable because final active-revision joins prevent stale publication. This avoids lengthening the critical revision-activation transaction merely to rebuild a search index. Retention/cleanup keeps derived rows bounded by the same revision lifecycle; index-size growth is measured.

The v8->v9 migration creates content + FTS structures and backfills currently retained catalog/overlay/EPG text. Do not depend on SQLite `lower()` for backfill normalization; `unicode61` owns token case folding.

## 9. Multi-token candidate algorithm

A single FTS document does not necessarily contain all user-visible fields. For example, `Россия` may match a canonical-name document while `1` matches a channel-number document. Requiring all query tokens inside one FTS document would therefore be incorrect.

MuxTV uses **bounded per-token candidate search + canonical-ID intersection**.

Constants:

```text
MAX_PUBLIC_RESULTS = 200
MAX_QUERY_TOKENS = 6
MAX_CANDIDATES_PER_TOKEN = 800
```

The exact candidate cap is an implementation constant and must be measured; 800 is the initial design ceiling, not a public compatibility guarantee.

For each encoded token:

1. DAO queries FTS for that token prefix;
2. matching documents are mapped to candidate canonical channel IDs through their origin metadata;
3. candidates are revalidated against active/visible truth as described below;
4. DAO returns at most `MAX_CANDIDATES_PER_TOKEN + 1` rows so overflow is observable.

The repository intersects canonical IDs across all token result sets. Therefore every query token must match **some searchable field of the same canonical channel**, but different tokens may match different fields/documents.

This correctly supports cases such as:

- name `Россия` + number `1`;
- group `Спорт` + channel-name token;
- channel-name token + current-programme token.

If any token candidate query overflows its cap, `isTruncated` is conservatively true even if the final intersection becomes small. Search never claims exhaustive results when an intermediate bounded set was incomplete.

The algorithm executes at most six bounded FTS/validation queries per generation; its cost is measured on 1k/10k/50k datasets before tuning.

## 10. Active-truth validation for each token

FTS only proposes documents.

### 10.1 Catalog/overlay documents

A candidate survives only if:

- at least one provider/stream row belongs to the source's current active revision;
- at least one active stream variant exists;
- requested profile does not hide the canonical channel;
- overlay text documents belong to the requested profile.

Provider documents from inactive revisions cannot publish a result even when their FTS text still exists.

### 10.2 EPG programme documents

A title hit survives only when its exact programme participates in the current, current-policy, unambiguous mapping.

Accepted guide semantics:

```text
previous = latest programme where start <= now
next     = earliest programme where start > now

if previous.stop != null && previous.stop > now:
    effectiveEnd = previous.stop
else if previous.stop == null && next != null && next.start > now:
    effectiveEnd = next.start
else:
    effectiveEnd = null

current = previous only when effectiveEnd != null && effectiveEnd > previous.start
```

Then the FTS-hit programme must equal that `current` programme.

Consequences:

- future/past/stale programme docs never publish;
- ambiguous match mappings contribute no programme search text;
- missing EPG can never remove an otherwise matching name/number/group channel;
- open-ended + no-next data is not infinite-current.

This explicitly avoids the optional-enrichment INNER-JOIN failure mode observed in Immich.

## 11. Final result projection and ranking

After token intersection, fetch final active channel rows and optional current programme metadata for the bounded canonical-ID set.

The public result list never exceeds `query.limit`.

Do not cargo-cult BM25. MuxTV's domain has strong structured intent. Deterministic final priority:

1. exact effective channel number;
2. exact effective/custom display name;
3. effective/custom display-name prefix;
4. provider raw-name prefix;
5. group matches;
6. current-programme matches;
7. stable tie-break by numeric channel number when parseable, display name, canonical channel ID.

Final exact/prefix comparisons occur only on the bounded candidate set and may use JVM Unicode-aware string operations. Match-origin data remains internal and no public score is persisted.

If final result count exceeds `limit`, truncate deterministically and set `isTruncated = true`.

## 12. Programme-time invalidation

Current-programme search can change with wall time even without a Room write.

The repository/screen therefore publishes/schedules the earliest future boundary across active, current-policy, unambiguous programme mappings for the requested profile, not only the currently displayed results. This is necessary because a channel not currently matching the query can begin matching when its next programme starts.

Candidate boundaries follow accepted Now/Next rules:

- effective end of current programme;
- next programme start.

SearchViewModel schedules exactly one cancellable wake-up for the published boundary. A newer query generation cancels it. No polling loop.

## 13. ViewModel and state

Create destination-scoped `SearchViewModel` in a new `:feature:search` module.

State:

```kotlin
sealed interface SearchUiState {
    data object EmptyQuery : SearchUiState
    data object Loading : SearchUiState
    data class Content(val rows: List<SearchRowProjection>, val isTruncated: Boolean) : SearchUiState
    data object NoResults : SearchUiState
    data object Failed : SearchUiState
}
```

Required behavior:

- initial query blank;
- typing path debounced by 300 ms initially;
- explicit IME Search/Done submit bypasses debounce and executes immediately;
- normalized duplicate query does not restart work;
- a newer generation cancels prior token queries/repository collection/boundary job;
- stale generation cannot publish over a newer query;
- blank clears immediately;
- same-query Room/time refresh preserves Content while replacing data rather than flashing destructive Loading;
- query + canonical focus anchor survive Search -> Player -> Back through existing Navigation3 ViewModel/saveable-state ownership;
- process death persists only query/anchor, not derived results;
- payload-free failures only.

## 14. TV UI and IME/focus contract

Replace the Search placeholder with one restrained screen:

- title `Поиск`;
- single-line search text input;
- result/status copy;
- one lazy vertical results list;
- row: number, favorite marker, channel name, group, optional current programme;
- if truncated: explicit copy such as `Показаны первые N — уточните запрос`.

No preview pane, artwork rail, recommendations or second content column.

Focus rules:

1. initial focus on input;
2. Down from input -> first result when available;
3. Up from first result -> input;
4. lower rows use normal vertical traversal;
5. OK -> existing Player;
6. Player -> Back restores query + same surviving canonical channel;
7. removed result -> nearest previous;
8. no results -> input;
9. no new global focus engine.

### Explicit submit / Fire TV safety

On IME submit, focus must leave the text field immediately. This mirrors the real Amazon fullscreen-keyboard failure handled by Jellyfin Android TV.

- results already exist -> focus first result;
- otherwise focus a visible result/status host and record one-generation `focusFirstResultWhenReady` intent;
- matching-generation result arrival consumes that intent exactly once;
- no result keeps a visible focus target and permits Up back to input;
- query change/clear cancels the intent.

Do not hide IME/focus races using arbitrary delays.

## 15. Expected implementation surface after approval

- `settings.gradle.kts`: add `:feature:search`;
- Room schema v9 + exported schema + v8->v9 migration;
- `catalog/api/.../ChannelSearchRepository.kt`;
- `core/database/.../SearchDocumentEntity.kt`;
- `core/database/.../SearchDocumentFtsEntity.kt`;
- `core/database/.../ChannelSearchDao.kt`;
- `core/database/.../RoomChannelSearchRepository.kt`;
- search-document population/cleanup hooks at catalog/overlay/EPG ownership boundaries;
- database component wiring;
- `feature/search/.../SearchViewModel.kt`;
- `feature/search/.../SearchRoute.kt`;
- app DI;
- replace `AppDestination.Search -> PlaceholderRoute("Поиск")`.

Search logic does not belong in `MainActivity`, navigation lambdas or Compose-side full-list filtering.

## 16. Correctness acceptance

### Query encoder/API

- blank/whitespace behavior;
- token cap;
- punctuation/operator text cannot become FTS syntax;
- token-prefix encoding;
- Cyrillic upper/lower equivalence;
- `Россия 1` produces two tokens;
- no incompatible query-only Unicode normalization;
- redacted `toString()`.

### FTS/platform/API26/API36

- FTS4 external-content table creates on the accepted/default Room driver;
- `unicode61` is available;
- lower-case Cyrillic prefix matches upper/mixed-case indexed text;
- Latin/diacritic behavior characterized;
- no BundledSQLiteDriver dependency introduced accidentally.

### Migration/index lifecycle

- v8->v9 schema creation;
- retained canonical/provider/overlay/EPG backfill;
- provider/EPG staging inserts derived docs;
- overlay custom name/number update replaces relevant docs;
- cleanup removes revision-owned docs;
- external-content FTS synchronization/integrity remains valid;
- migration has non-zero Cyrillic fixtures.

### Multi-token/final Room Search

- same-document multi-token;
- cross-document `Россия` + `1` intersection;
- channel token + group token;
- channel token + current-programme token;
- profile overlay isolation;
- custom/canonical/raw/group/number matching;
- current programme matching;
- optional/missing EPG cannot remove catalog match;
- past/future/stale/conflicted programme docs excluded;
- open-ended guide semantics match accepted Now/Next;
- inactive revision / hidden channel excluded;
- candidate cap overflow produces conservative truncation;
- deterministic ranking and public limit;
- earliest profile programme boundary correct.

### ViewModel

- debounce;
- immediate submit;
- normalized-query dedupe;
- cancellation/generation ownership;
- stale result rejection;
- same-query refresh preserves Content;
- boundary wake-up/cancellation;
- query/focus state over Player/Back;
- submit-focus intent is generation-scoped;
- safe failures.

### TV instrumentation

- input initial focus;
- type -> results -> Down;
- IME submit -> focus leaves input;
- first row Up -> input;
- Player -> Back restores query/result;
- removed result fallback;
- no-results recovery;
- explicit truncation UI;
- Cyrillic mixed-case journey;
- current-programme membership change at controlled boundary;
- API26/API36 product journey.

Physical Amazon/Fire TV keyboard validation remains an alpha-device requirement, but the focus contract is implemented/testable before that.

## 17. Performance acceptance

Unicode correctness justifies the derived index. Performance still determines tuning.

Measure deterministic 1k/10k/50k catalog profiles + bounded EPG fixtures:

- per-token FTS candidate wall time;
- number of token queries per generation;
- candidate intersection cost;
- final active/current-policy projection cost;
- candidate/result counts;
- allocations where meaningful;
- DB size delta;
- v8->v9 backfill duration and peak memory;
- exact-number/name-prefix/group/programme/multi-token/no-match queries;
- query plans where applicable.

Run comparable Search measurements on current-normal, old-edge-normal and current-low-ram profiles where practical.

Tuning order:

1. correctness/query-plan fixes;
2. candidate-cap tuning;
3. measured FTS4 `prefix=` indexes;
4. only then consider a broader SQLite-driver/index architecture.

FTS5/BM25/trigram requires a separate ADR covering bundled-SQLite APK/runtime cost, compatibility and measured benefit.

## 18. Security/privacy

Never index or expose:

- stream/playlist locators;
- URL query tokens;
- cookies/Authorization/sensitive headers;
- credentials/access refs;
- raw exception strings;
- raw FTS origin metadata in UI/diagnostics;
- user query text in diagnostics.

Search indexes only approved display metadata. SQL uses bound parameters; raw user text is never executable FTS syntax.

## 19. Alternatives rejected

### Ordinary LIKE/NOCASE

Rejected for Unicode/Cyrillic correctness.

### FTS5 + BundledSQLiteDriver immediately

Rejected because it expands the database runtime/packaging boundary without a demonstrated need beyond capabilities already covered by FTS4/unicode61.

### One FTS expression requiring all tokens inside one document

Rejected because different query tokens can legitimately match different fields/documents of the same canonical channel.

### Flatten one profile-specific mega-document per channel

Rejected initially because it duplicates effective active/profile projections and creates heavier recomputation ownership. Bounded per-token canonical intersection preserves normalized sources of truth and is easier to validate.

### Extend PlaybackCatalog with EPG

Rejected because it makes catalog/playback ownership time-dependent on EPG.

### Merge independent catalog/EPG searches in ViewModel

Rejected because it moves canonical identity/limits/truncation rules into presentation code.

### Provider/plugin framework

Rejected as speculative federation architecture.

### Vector/semantic search

Rejected for deterministic channel lookup and because hard top-N semantic behavior introduces relevance/truncation complexity with no demonstrated user need.

## 20. Self-review result

Three material issues were found and corrected before implementation:

1. ordinary SQLite LIKE/NOCASE was not Unicode-correct for Russian text;
2. open-ended programme semantics originally risked drifting from accepted Now/Next;
3. a single-document multi-token FTS query would fail legitimate cross-field searches such as channel name + number.

The current design therefore uses platform-compatible FTS4/unicode61, exact accepted guide semantics, bounded per-token candidate queries and canonical-ID intersection.

It also preserves optional EPG behavior, explicit truncation, TV/IME focus ownership, query continuity and a hard separation between derived index and active truth.

## 21. Approval gate

Production Search implementation must not begin until this revised written design is reviewed and approved. After approval, create a task-level implementation plan and implement from the then-accepted `main` on a fresh branch.