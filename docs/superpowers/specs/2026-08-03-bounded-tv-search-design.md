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
- title of the programme that is **currently active under the accepted Now/Next semantics**.

Search must work correctly for non-ASCII channel/programme names, including Cyrillic, remain bounded at every API/UI boundary, avoid whole-catalog/whole-guide materialization, preserve canonical channel identity, and reuse the process-owned Player.

## 2. Comparative research conclusions

The design was re-reviewed against current implementations and failure reports from multiple search-heavy projects. The useful lesson is not to copy one project wholesale, but to combine the parts that match MuxTV's local-first Android TV constraints.

### TV/media clients

**Jellyfin Android TV** is the closest TV interaction reference. Its current Search implementation uses a dedicated ViewModel/repository, trims and deduplicates queries, cancels the previous search job, debounces ordinary text changes, provides an immediate-submit path, preserves the text field with saveable state, and explicitly moves focus away from text input on submit because Amazon devices may otherwise retain a fullscreen keyboard. Its current Compose/Leanback bridge also uses explicit focus boundaries rather than relying on geometric focus alone.

Adopt:

- separate debounced-change and immediate-submit paths;
- normalized-query deduplication;
- cancellation/generation ownership;
- explicit text-input/results focus graph;
- explicit focus escape on IME submit for Fire TV/Amazon safety.

Do not copy:

- parallel per-media-type network searches; MuxTV has one local canonical channel identity and should produce one globally bounded/ranked result set.

**IPTVnator** is the strongest IPTV-domain reference. Recent releases added global search, category search, lazy loading for large playlists, channel-number navigation, and explicitly preserve the search phrase/results when navigating back from a result.

Adopt:

- query/result continuity across Player → Back;
- channel-number-first intent;
- hard/lazy bounds for large playlists;
- hidden content remains an explicit policy, not an accidental search leak.

Do not copy:

- a global Live/VOD/Series scope in this slice; MuxTV currently has only the accepted live-channel/catalog/EPG product boundary.

**VLC Android / NOVA Video Player / Jellyfin Web / Findroid** were surveyed as additional media-client references. They reinforce that Search must stay a client/domain feature rather than become playback ownership, but they do not provide stronger directly transferable Android-TV Search contracts than Jellyfin Android TV for this slice.

### IPTV/EPG/query systems

**Tvheadend** exposes EPG search/filtering with explicit current-time mode, field filters, deterministic sorting and backend limits. This validates modeling current-programme search as a time-dependent query rather than copying programme text into UI state and filtering it there.

Adopt:

- explicit `nowEpochMillis` query semantics;
- backend filtering and hard limits;
- deterministic sorting;
- keep future filter dimensions separate from the default free-text contract.

**Threadfin** strongly separates active/inactive channels, group filtering and channel numbering. For MuxTV this reinforces that Search candidates must still pass the active/visible canonical-channel boundary; search indexing must never make an inactive provider row visible.

### Large local/self-hosted libraries

**Navidrome** rebuilt Search on SQLite FTS5 with two-phase BM25 ranking for large libraries. The transferable lesson is that SQLite full-text indexing is a valid derived-index architecture when ordinary scans stop being sufficient. MuxTV should not copy FTS5/BM25 directly because Android driver compatibility and our ranking problem differ.

**PhotoPrism** demonstrates a mature filter vocabulary and has had to harden escaping/parsing of search operators. The lesson for MuxTV is to keep the first public query language intentionally small: plain user text only. Do not expose FTS operators or advanced Boolean syntax in the first TV slice.

**ErsatzTV** distinguishes the default free-text field from many explicit searchable fields. The lesson is to keep MuxTV's default fields curated (channel name/number/group/current programme), rather than silently making every provider/EPG attribute part of broad search.

**Immich** provides two useful negative lessons. First, an INNER JOIN to optional search enrichment caused ordinary metadata search to disappear when that enrichment was absent; therefore optional EPG must never be required for a channel result. Second, hard-capped semantic result sets without clear pagination/truncation semantics created confusing UX. MuxTV will report truncation explicitly rather than pretending a bounded page is the whole result universe.

**NewPipe** shows why query cost must shape interaction design: remote searches download/provider-query data and therefore filters are selected before expensive searches. MuxTV Search is local Room data, so debounced live search is appropriate. If remote provider search is ever added later, it must be a separate cost/ownership path and must not inherit the local-per-keystroke behavior automatically.

**Kvaesitso** and **Lawnchair** demonstrate provider/backend abstractions for products whose purpose is federated search. That abstraction is deliberately rejected for MuxTV now: there is one local canonical search boundary. A plugin/provider framework would be speculative architecture.

**Audiobookshelf** has had real user demand for diacritic-insensitive search. Combined with SQLite's documented behavior, this reinforces that international text handling is a correctness requirement rather than a cosmetic follow-up.

### Critical SQLite finding

The original design used ordinary `LIKE ... COLLATE NOCASE`. That is insufficient for MuxTV's Russian/non-ASCII data. Default SQLite case-insensitive comparisons fold ASCII only; ordinary `LIKE` does not provide full Unicode case folding. A query such as a lower-case Cyrillic channel name therefore cannot be assumed to match an upper-case stored value.

This changes the architecture decision: **the initial Search needs a Unicode-aware derived text index for correctness, not only for performance.**

Room 3 supports both FTS4 and FTS5. FTS5 availability on Android is driver-dependent and is guaranteed by `BundledSQLiteDriver`; MuxTV currently uses the platform/default Room driver and has intentionally deferred a bundled-SQLite runtime change. Android's platform SQLite includes FTS4, Room 3 supports `@Fts4`, and `unicode61` provides Unicode-aware tokenization/case folding.

Therefore the initial design uses **FTS4 + `unicode61`**, not FTS5 and not ordinary Unicode-broken `LIKE` as the primary text matcher.

## 3. Non-goals

This slice does **not** introduce:

- bundled SQLite or a database-driver migration;
- FTS5/BM25;
- arbitrary substring/trigram search;
- fuzzy spelling correction, transliteration or ML/vector ranking;
- recommendations;
- programme-detail search outside the current programme;
- provider-specific remote search APIs;
- Live/VOD/Series federated search;
- user-visible Boolean/field query syntax;
- a second catalog or EPG source of truth;
- Paging solely for this first TV Search screen;
- a global/custom focus engine;
- Rust/UniFFI/native search.

## 4. Existing contracts to preserve

### Playback catalog

`PlaybackCatalog` remains the active-channel/playback boundary. Search must not turn `PlaybackCatalog.observeChannels()` into an EPG-dependent API. Search opens the existing `AppDestination.Player(channelId)` and never resolves or installs media itself.

### EPG guide

Search must inherit the accepted `EpgGuideRepository` / `RoomEpgGuideRepository` semantics:

- EPG source revision equals `epg_sources.activeRevision`;
- provider/catalog revision equals `sources.activeRevision`;
- match policy equals `CURRENT_EPG_MATCH_POLICY_VERSION`;
- only `decision = MATCHED` rows participate;
- hidden channels remain excluded;
- multiple active matches produce conflict semantics, never a weak winner;
- an open-ended previous programme is current only when its effective end is known from the next programme.

`stop == null` never means "current forever".

### Derived-index rule

The Search FTS/content tables are **derived acceleration/correctness structures**, never publication truth. A result is returned only after joining back through current canonical catalog and current-policy EPG provenance. Stale FTS documents cannot make stale provider/EPG rows active.

## 5. Chosen architecture

Add one read-only public boundary:

```kotlin
interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}
```

Search combines catalog metadata and current EPG metadata behind one bounded query but does not change either owner.

### 5.1 Query contract

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
    }
}
```

Rules:

- profile ID is nonblank;
- `nowEpochMillis >= 0`;
- `limit in 1..200`;
- normalize surrounding whitespace and Unicode compatibility form before query-token encoding;
- blank text returns an empty snapshot and never becomes an unfiltered catalog query;
- public `toString()` redacts profile and query text;
- diagnostics expose only safe length buckets/counts/timing/failure category.

### 5.2 Query language

The public first-slice query is plain text only.

Internally, a `SearchQueryEncoder` extracts Unicode letter/number tokens, quotes them as FTS literals and builds **AND + token-prefix** semantics. User text is bound as a parameter and never concatenated as raw FTS syntax.

Examples of intended semantics:

- `рос` matches a token beginning with `рос…` regardless of Cyrillic case;
- `рос 1` requires both token prefixes in the matched document/candidate path;
- `%`, `_`, `"`, `AND`, `OR`, `NEAR` in user text are treated as user text, not an exposed query language;
- arbitrary middle-of-token substring (`осс` → `Россия`) is not guaranteed in v1.

Token-prefix semantics are chosen because they are indexable, predictable on TV and Unicode-correct. Trigram/contains search is a separate future decision.

### 5.3 Public result

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

`isTruncated` is required. The UI must never imply that a hard-bounded result set is exhaustive when more matching channels exist.

The result does not expose provider IDs, raw FTS documents, locators, match evidence, query text or raw failures.

## 6. Room v9 derived Search index

The initial Search is expected to require **Room v9** because Unicode-correct full-text indexing is a correctness requirement.

### 6.1 Search document content table

Add a normal Room content table with an integer primary key and typed origin metadata, conceptually:

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

Kinds are internal constants, initially:

- `CANONICAL_NAME`;
- `PROVIDER_RAW_NAME`;
- `PROVIDER_GROUP`;
- `PROVIDER_NUMBER`;
- `OVERLAY_CUSTOM_NAME`;
- `OVERLAY_NUMBER`;
- `EPG_PROGRAMME_TITLE`.

Origin columns used only for joining/validation are not public Search state.

### 6.2 External-content FTS4 table

Back the searchable text with Room 3 `@Fts4` using:

- `contentEntity = SearchDocumentEntity`;
- tokenizer `unicode61`;
- only `text` indexed;
- origin/profile/kind fields remain in the content table and are joined through rowid.

Do not expose FTS operators to UI text.

Prefix indexes may be added only after the API26/API36 compatibility spike and size/query-plan measurement. FTS prefix matching itself is part of the contract; specific auxiliary prefix-index sizes are an implementation/performance detail.

### 6.3 Population and lifecycle

Search documents are maintained alongside the existing immutable data lifecycle:

- canonical channel creation/name mutation → canonical-name document;
- provider-channel staging/import → raw-name/group/number documents;
- profile overlay mutation → custom-name/number documents;
- EPG programme staging/import → programme-title document when nonblank;
- revision cleanup → delete matching derived documents in the same cleanup ownership boundary;
- hidden/favorite mutations do not need indexed text changes unless custom name/number changes.

The v8→v9 migration creates the content + FTS tables and backfills currently retained catalog/overlay/EPG text with SQL `INSERT ... SELECT`. Unicode normalization is performed by the FTS tokenizer, so migration does not depend on SQLite `lower()`.

Migration acceptance includes non-zero backfilled Cyrillic examples and exact schema export.

### 6.4 Why not FTS5 now

Room 3 exposes `@Fts5`, but Android availability is driver-dependent and documented as guaranteed with `BundledSQLiteDriver`. Adopting that driver would change the accepted database runtime/packaging boundary. Search does not justify that unrelated architecture change.

FTS5/BM25 remains a future option if later evidence justifies bundled SQLite for broader reasons.

## 7. Candidate query and active-truth validation

FTS finds **candidate documents**, not final channels.

The DAO must convert those documents into distinct canonical-channel candidates and then validate them against current truth.

### 7.1 Channel metadata candidates

- canonical-name document maps directly to canonical channel ID;
- provider documents map through the referenced provider channel/active stream variant;
- overlay documents apply only to the requested profile.

Final candidates must still satisfy:

- provider row is in `sources.activeRevision`;
- at least one active variant exists;
- requested profile does not hide the canonical channel.

### 7.2 Programme candidates

An EPG title FTS hit contributes only when its exact programme row participates in the **current unambiguous current-policy mapping**.

Derive accepted Now/Next semantics:

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

Then require the FTS-hit programme to be that current programme.

Consequences:

- missing EPG never removes an otherwise matching channel;
- future/past/stale programme hits do not leak into results;
- ambiguous `SOURCE_CONFLICT` mappings contribute no programme text;
- stale revision/policy index rows remain harmless because final joins reject them.

This is intentionally unlike the Immich optional-enrichment INNER JOIN failure mode.

### 7.3 Bounded candidate set

The database produces distinct canonical candidates with an internal origin priority and a hard candidate cap derived from the public limit. The implementation may over-fetch a bounded multiple (for example `limit * 4`, capped at an explicit constant) so the repository can apply final Unicode-aware exact/prefix ranking without loading the full catalog.

The final public list never exceeds `limit`.

Fetch at least one extra candidate or otherwise expose candidate-cap exhaustion so `isTruncated` is conservative: if the system cannot prove there are no more results, it reports truncation rather than claiming completeness.

## 8. Ranking

Do not copy Navidrome's BM25 blindly; MuxTV has a small structured channel result domain where field intent matters more than generic document relevance.

Final ranking is deterministic and applied to a **bounded candidate set**.

Initial priority:

1. exact effective channel number;
2. exact effective display/custom name;
3. effective display/custom-name prefix;
4. provider raw-name prefix;
5. provider group match;
6. current-programme title match;
7. stable tie-break by numeric channel number when parseable, display name, canonical channel ID.

Kotlin may perform final exact/prefix comparisons with Unicode-aware JVM string operations on the bounded candidate set. The public API does not expose scores or depend on a BM25 implementation.

A current programme title may be displayed for every result when available, even if the channel matched on name/number/group; match origin remains an internal ranking detail.

## 9. Programme-time invalidation

Search cannot rely only on Room writes because current-programme membership changes as wall time advances.

`ChannelSearchSnapshot.nextBoundaryEpochMillis` is the earliest future boundary that can change the current-programme projection for returned/candidate active mappings.

Candidate boundaries follow the same Now/Next rules:

- effective end of current programme;
- next programme start.

The Search ViewModel owns the clock and schedules exactly one cancellable wake-up for the published boundary. A new query generation cancels the old boundary job. No polling loop is introduced.

Catalog/EPG/derived-index changes naturally invalidate the Room query.

## 10. Search ViewModel

Add a destination/back-stack-scoped `SearchViewModel` in `:feature:search`.

Required behavior:

- initial query blank;
- normalize and deduplicate query generations;
- ordinary typing path debounced by **300 ms** initially;
- explicit IME Search/Done/submit path executes immediately and bypasses the debounce;
- a new normalized query cancels prior repository collection + boundary work;
- stale generations cannot publish over newer queries;
- blank query returns immediately to EmptyQuery;
- same-query Room/time refresh preserves current Content while refreshing rather than flashing through destructive Loading;
- query text + focus anchor survive Search → Player → Back through the existing Navigation3 destination ViewModel/saveable-state ownership;
- process-death restoration needs only query + anchor; results are re-derived from Room rather than serialized into navigation state;
- payload-free typed failures only.

The 300 ms value is a tunable UI default. Jellyfin Android TV currently uses a longer debounce for server-backed multi-group search; MuxTV is local Room and should measure rather than cargo-cult that network-oriented duration.

## 11. TV UI and focus contract

Replace `AppDestination.Search -> PlaceholderRoute("Поиск")` with one restrained TV screen:

- title `Поиск`;
- single-line search text input;
- status/result count;
- one lazy vertical result list;
- row data: number, favorite marker, channel name, group and current programme when available;
- when `isTruncated`, visible copy such as `Показаны первые N — уточните запрос` rather than a fake total count.

No preview pane, artwork rail, recommendations or second content column in this slice.

### Focus rules

1. Search opens with text input focused.
2. `Down` from input enters the first result when results exist.
3. `Up` from first result returns to input.
4. Lower rows keep ordinary vertical traversal.
5. `OK` opens existing Player directly.
6. Player → Back restores query and same surviving canonical channel.
7. Removed result falls back to nearest previous result.
8. No results returns focus to input.
9. Query/data refresh cannot create a global focus owner.

### IME submit / Fire TV safety

On explicit keyboard submit, Search must move focus **off the text field**, mirroring the real Amazon fullscreen-keyboard failure avoided by Jellyfin Android TV.

Implementation requirement:

- if results already exist, focus first result;
- otherwise focus a visible results/status host and mark a one-generation `focusFirstResultWhenReady` intent;
- when matching-generation results arrive, move to first result exactly once;
- if no result arrives, keep a visible focus target and allow `Up` back to the input;
- clearing/changing the query cancels the pending focus intent.

Do not use arbitrary delays to hide IME/focus races.

## 12. Expected production/module changes after approval

- `settings.gradle.kts`: add `:feature:search`;
- Room schema v9 + exported schema/migration;
- `catalog/api/.../ChannelSearchRepository.kt`;
- `core/database/.../SearchDocumentEntity.kt`;
- `core/database/.../SearchDocumentFtsEntity.kt`;
- `core/database/.../ChannelSearchDao.kt`;
- `core/database/.../RoomChannelSearchRepository.kt`;
- search-document population/cleanup hooks at existing catalog/overlay/EPG ownership boundaries;
- database component wiring;
- `feature/search/.../SearchViewModel.kt`;
- `feature/search/.../SearchRoute.kt`;
- app DI wiring;
- replace Search placeholder with `SearchRoute`.

Do not put Search business logic in `MainActivity`, navigation lambdas or Compose list filtering.

## 13. Correctness tests

### Query encoder/API

- whitespace/Unicode normalization;
- blank behavior;
- min/max limit;
- redacted `toString()`;
- FTS syntax characters are literals, not operators;
- multi-token AND-prefix encoding;
- Cyrillic upper/lower query equivalence.

### FTS/platform compatibility

On API26 and API36:

- FTS4 table creation succeeds with the platform/default driver;
- `unicode61` is available;
- `РОССИЯ` matches lower-case `рос*`;
- Latin case behavior remains correct;
- no bundled SQLite dependency appears accidentally.

### Migration/index lifecycle

- v8→v9 creates content + FTS tables;
- active retained canonical/provider/overlay/EPG text backfills;
- source import/staging inserts expected documents;
- EPG import inserts nonblank programme-title docs;
- overlay custom name/number mutation updates its documents;
- revision cleanup removes associated derived docs;
- FTS/content triggers remain consistent after migration.

### Final Room Search

At minimum:

- effective custom-name match;
- canonical/provider raw-name match;
- group match;
- overlay/provider channel-number match;
- lower-case Cyrillic query matches upper/mixed-case data;
- current programme-title match;
- missing EPG does not remove channel-name result;
- past/future programme excluded;
- explicit-stop current behavior matches Now/Next;
- open-ended + next behavior matches Now/Next;
- open-ended + no next is not infinite-current;
- stale EPG revision excluded;
- stale match-policy rows excluded;
- ambiguous/source-conflict mapping contributes no programme text;
- hidden channel excluded even when FTS matches;
- inactive catalog revision excluded;
- profile overlay isolation;
- deterministic ranking;
- hard result/candidate bounds and conservative `isTruncated`;
- programme boundary semantics match accepted guide projection.

### ViewModel

- blank query runs no unfiltered search;
- 300 ms typing debounce coalesces rapid input;
- immediate submit bypasses debounce;
- duplicate normalized query does not restart work unnecessarily;
- newer generation cancels older work;
- stale result cannot overwrite newer query;
- same-query refresh preserves Content;
- one programme-boundary reload is scheduled/cancelled correctly;
- query + anchor survive Player/Back;
- pending submit-focus intent belongs to one generation only;
- failures reveal no query/provider payload.

### TV instrumentation

- initial input focus;
- type → debounced result → Down → first row;
- IME submit → focus leaves input;
- first row Up → input;
- OK → Player → Back restores query + same result;
- removed focused result falls back;
- no-results returns/focuses input appropriately;
- truncation copy is visible when candidate set exceeds limit;
- Cyrillic mixed-case search journey;
- active-programme membership changes at a controlled boundary;
- API26/API36 product journey.

Physical Fire TV/Amazon keyboard behavior remains an alpha device acceptance item but the focus contract is implemented and emulator-testable before that.

## 14. Performance acceptance

Search indexing is now justified first by Unicode correctness. Performance still must be measured before tuning index topology/ranking.

Use deterministic 1k/10k/50k catalog profiles plus bounded EPG fixtures and record:

- FTS candidate query wall time;
- final active/current-policy join wall time;
- result/candidate counts;
- allocations where meaningful;
- DB size delta from Search content + FTS tables;
- migration/backfill duration and peak memory;
- query-plan/index usage;
- exact-number, name prefix, group, programme, multi-token and no-match cases.

Run the same Search set on current-normal, old-edge-normal and current-low-ram evidence profiles where practical.

Performance tuning order:

1. query/index-plan correction;
2. bounded candidate cap tuning;
3. measured FTS4 prefix indexes;
4. only then consider a broader driver/index architecture.

FTS5/BM25/trigram becomes eligible only under a separate ADR that also addresses bundled-SQLite cost, APK/ABI/runtime compatibility and measured benefit. Navidrome demonstrates that FTS5/BM25 can be worthwhile for large libraries; it does not prove that MuxTV should pay that cost now.

## 15. Security/privacy

Search state, logs, diagnostics and semantics must never expose:

- playlist/stream locators;
- URL query tokens, cookies or Authorization values;
- source credentials;
- provider/source identifiers not intentionally visible to users;
- raw FTS document/origin metadata;
- raw exception messages;
- the user's search text in diagnostics.

All SQL/FTS queries use bound parameters. The query encoder never forwards raw user syntax as an FTS expression.

Search documents index only already-approved searchable display metadata; credentials, locators, headers and access references are never indexed.

## 16. Alternatives rejected

### Ordinary `LIKE ... NOCASE` as primary Search

Rejected for correctness: stock SQLite does not provide full Unicode case folding, so Cyrillic case-insensitive search is not reliable.

### FTS5 + BundledSQLiteDriver immediately

Rejected: FTS5 is attractive but would force a broader database-runtime/packaging decision unrelated to the first Search need. FTS4 + unicode61 satisfies the current Unicode/token-prefix requirement on the accepted platform boundary.

### Extend `PlaybackCatalog.observeChannels()` with EPG joins

Rejected: it makes the catalog/playback boundary programme-time dependent and duplicates guide ownership.

### Separate catalog and EPG searches merged in ViewModel

Rejected: it over-fetches, makes one global limit/ranking/truncation contract ambiguous and moves database identity joins into presentation code.

### Search-provider/plugin framework

Rejected: Kvaesitso/Lawnchair need providers because federated search is their product. MuxTV has one local canonical search source today.

### Semantic/vector search

Rejected: Immich's relevance/cap behavior demonstrates the product complexity of semantic top-N results. Channel lookup has strong deterministic identifiers and does not need ML ranking.

### Full catalog/guide filtering in Compose

Rejected: violates issue #29 bounded-memory architecture.

## 17. Self-review result

The earlier design had two material weaknesses that this revision corrects:

1. it originally used ordinary SQLite LIKE as the primary text matcher, which is not sufficient for Unicode/Cyrillic case-insensitive Search;
2. before the prior correction it also treated open-ended EPG too loosely; the current design explicitly inherits accepted Now/Next effective-end semantics.

After comparative review:

- Search has one read-only public boundary;
- FTS is a derived index, not source of truth;
- platform-compatible FTS4/unicode61 is chosen for Unicode correctness without bundled SQLite;
- output and candidate sets are hard-bounded;
- truncation is explicit;
- current programme is optional enrichment and can never remove channel metadata matches;
- query changes use debounce + immediate-submit dual paths;
- query/focus continuity is preserved over Player/Back;
- Fire TV fullscreen-keyboard focus escape is part of the contract;
- ranking is structured and deterministic rather than speculative BM25/ML;
- current-policy/active-revision/hidden semantics are revalidated after every FTS candidate hit;
- Rust/native, FTS5/trigram and provider frameworks remain deferred behind evidence/ADR.

## 18. Approval gate

Production implementation must not begin until this revised written design is reviewed and approved. After approval, create a task-level implementation plan and implement Search from the then-accepted `main` on a fresh branch.