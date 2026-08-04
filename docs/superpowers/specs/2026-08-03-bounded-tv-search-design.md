# Bounded TV Search Design

**Status:** implementation in progress; Search Core is PR #96  
**Date:** 2026-08-04  
**Issue:** #29  
**Accepted implementation base:** `main@3621c2d3f4eb7b5675ab6107497b4b3edbde9851`  
**Schema ownership:** Search Core owns Room v8 -> v9

## 1. Goal

MuxTV Search is a TV-first, profile-scoped local search over active playable channels. It can match:

- effective/custom channel name;
- provider/raw channel name;
- effective/provider channel number;
- provider group;
- title of the programme that is current under the accepted Now/Next semantics.

Search must remain Unicode-correct for Russian/Cyrillic text, bounded at public and database boundaries, independent from provider credentials/locators, and unable to publish stale catalog or EPG truth.

The Search Core PR does not implement the TV route. Search TV is a separate slice after Core acceptance.

## 2. Research conclusions retained

Comparative review covered Jellyfin Android TV, IPTVnator, Tvheadend, Kodi, MythTV, Navidrome, Immich, NewPipe, ErsatzTV, PhotoPrism, Audiobookshelf, Threadfin, Kvaesitso, Lawnchair, SmartTube, Jellyfin Web/Android/Roku, Findroid, VLC Android, NOVA, Lampa, Ente and related media applications.

The transferable decisions are:

- Jellyfin Android TV: debounced typing + immediate submit, cancellation and explicit TV/IME focus ownership;
- IPTVnator: preserve query/results context over Player -> Back and treat channel numbers as first-class intent;
- Kodi/MythTV/Tvheadend: EPG search belongs to the backend/time domain, not Compose list filtering;
- Navidrome: SQLite FTS is appropriate derived infrastructure, but generic BM25 is not automatically the right IPTV ranking;
- Immich: optional enrichment must never become a mandatory inner join that suppresses ordinary metadata results;
- search-provider/plugin frameworks are deferred until MuxTV actually has multiple search domains/providers.

## 3. SQLite and Unicode decision

Ordinary SQLite `LIKE`/`NOCASE` is not sufficient for non-ASCII case-insensitive Search. MuxTV therefore uses:

- Room **v9**;
- platform/default Room SQLite driver;
- external-content **FTS4**;
- tokenizer **`unicode61`**;
- final active/current-policy validation against canonical Room truth.

Deferred unless separate measurements/ADR justify them:

- FTS5;
- BM25;
- BundledSQLiteDriver;
- FTS `prefix=` indexes;
- trigram/substring index;
- vectors/fuzzy/transliteration;
- Rust/UniFFI/native Search.

API26/API36 device acceptance proves the actual platform `unicode61` runtime used by MuxTV.

## 4. Public API

```kotlin
interface ChannelSearchRepository {
    fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot>
}

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

Rules:

- profile ID is nonblank;
- `nowEpochMillis >= 0`;
- public limit is `1..200`;
- whitespace is collapsed, but no asymmetric Unicode normalization is applied;
- blank query returns empty Search and never becomes an unfiltered catalog query;
- at most six Unicode letter/number token runs are processed;
- ignored extra tokens make completeness explicit through `isTruncated` unless the processed AND-query is already provably empty;
- query/profile/programme payload is redacted from diagnostics.

## 5. Safe FTS query encoding

`SearchQueryEncoder` is internal to the database Search implementation.

It:

1. walks Unicode code points;
2. extracts letter/number runs only;
3. caps processed tokens at six;
4. emits each token as one quoted FTS4 prefix term, e.g. `"Россия*"`;
5. never forwards raw user query syntax to `MATCH`.

Punctuation is a separator. Words such as `AND`, `OR` and `NEAR` remain quoted terms instead of FTS operators. Arbitrary middle-of-token substring matching is not promised in v1.

## 6. Room v9 derived index

### 6.1 Content table

The as-built content entity is deliberately smaller than the earlier design draft:

```text
search_documents
- rowid INTEGER PRIMARY KEY AUTOINCREMENT
- documentKey TEXT UNIQUE
- kind TEXT
- canonicalChannelId TEXT nullable
- profileId TEXT nullable
- providerChannelId TEXT nullable
- text TEXT
```

Kinds:

- `CANONICAL_NAME`;
- `PROVIDER_RAW_NAME`;
- `PROVIDER_GROUP`;
- `PROVIDER_NUMBER`;
- `OVERLAY_CUSTOM_NAME`;
- `OVERLAY_NUMBER`;
- `EPG_PROGRAMME_TITLE`.

There are intentionally **no EPG revision/channel/programme-origin columns** in Search documents. EPG title rows are vocabulary terms, not programme ownership records.

### 6.2 External-content FTS

`search_documents_fts` indexes only `text` using FTS4 + `unicode61`, with `search_documents` as external content.

FTS is a candidate accelerator only. A stale derived row can cause extra candidate work, but cannot publish stale content because candidate SQL revalidates authoritative catalog/profile/EPG state.

### 6.3 Rowid safety

Persistent Search content must not use SQLite `REPLACE` where it could silently delete/reinsert a row and change `rowid`.

Canonical metadata refresh uses:

1. bounded key lookup;
2. ordinary INSERT for missing documents;
3. ordinary UPDATE for existing documents while preserving `rowid`.

External-content synchronization then remains consistent with Room's content-sync triggers.

## 7. Compact EPG programme-title vocabulary

MuxTV XMLTV ingestion permits up to 2,000,000 programme occurrences. Indexing one Search row per occurrence is therefore rejected.

Search stores one derived vocabulary row per exact nonblank programme title present in retained/staging EPG rows.

During staging:

1. programme rows remain the authoritative source;
2. the incoming batch is collapsed by title;
3. existing vocabulary titles are queried in bounded chunks of 400;
4. only missing title rows are inserted.

The vocabulary row has no EPG revision/channel ownership. Its `documentKey` is only a stable insertion identity; Search truth is resolved later from active programme rows.

During EPG discard/prune, unreferenced vocabulary is removed **set-wise** against the remaining nonblank `epg_programmes.primaryTitle` set. The cleanup must not perform a correlated full programme scan for every vocabulary row and does not justify adding a `primaryTitle` B-tree before measurements.

Worst-case unique-title storage and cleanup/backfill cost are explicit measurement subjects before merge. A future index or different vocabulary strategy requires evidence rather than assumption.

## 8. Migration v8 -> v9

Migration creates:

- `search_documents`;
- its unique/origin lookup indexes;
- `search_documents_fts` with `unicode61`.

It backfills:

- canonical names;
- provider raw names/groups/numbers;
- profile custom names/numbers;
- **one row per distinct exact nonblank EPG title**, not per programme occurrence.

Because Room removes external-content synchronization triggers while migrations execute, migration explicitly runs the FTS `rebuild` command after content backfill. Room recreates normal sync triggers after migration/open validation.

The committed `9.json` must be generated by Room/KSP, never hand-authored. Trusted prior KSP output has database version 9 and identity hash `1e22d8e43770617000dcbcf5bfdbbdba`; the clean post-Favorites head must reproduce/accept that generated schema.

## 9. Active-truth candidate validation

FTS hit documents do not directly become public results.

A channel candidate must have:

- a provider channel in a source's current active revision;
- at least one stream variant mapped to the canonical channel;
- no hidden overlay for the requested profile.

Provider metadata hits survive only when the referenced provider row is in the source's active revision.

Overlay name/number hits survive only for the requested profile.

### 9.1 Current EPG programme

Programme-title candidates are derived as follows:

1. select EPG matches whose EPG revision is active;
2. require the linked provider/catalog revision to be active;
3. require `CURRENT_EPG_MATCH_POLICY_VERSION`;
4. require `decision = MATCHED` and non-null canonical ID;
5. count active mappings by canonical channel and keep exactly one;
6. resolve the latest programme start `<= now` through the existing source/revision/external-channel/time path;
7. accept it as current only under the same Now/Next contract as Guide;
8. compare that current title with the small FTS hit vocabulary set.

Accepted current semantics:

```text
previous = latest programme start <= now
next     = earliest programme start > now

if previous.stop != null and previous.stop > now:
    current = previous
else if previous.stop == null and next exists:
    current = previous until next.start
else:
    no current programme
```

Consequences:

- past/future/stale-policy programmes cannot publish;
- ambiguous active mappings contribute no programme text;
- open-ended + no-next is not current forever;
- missing EPG never suppresses channel name/number/group Search.

## 10. Bounded multi-token algorithm

Different query tokens may match different documents of the same canonical channel, so all terms cannot be required inside one FTS row.

Internal limits:

```text
MAX_QUERY_TOKENS = 6
MAX_CANDIDATES_PER_TOKEN = 800
CANDIDATE_FETCH_LIMIT = 801
MAX_PUBLIC_RESULTS = 200
```

For each token:

1. run a validated probe capped at 801 rows;
2. retain at most 800 candidates and record overflow;
3. choose the smallest probe as the seed;
4. for a broad overflowing non-seed token, re-run it **restricted to the current <=800 seed IDs**;
5. intersect canonical IDs across all required tokens.

This avoids the false-negative pattern where `канал*` has tens of thousands of matches while the precise token `1200*` identifies one channel outside the broad query's first arbitrary 800 rows.

If the most selective seed itself overflows, completeness cannot be proven and `isTruncated = true`.

## 11. Projection and ranking

After intersection, Search fetches active channel summaries only for the bounded canonical-ID set. Now/Next is requested only for the bounded published result set.

Deterministic ranking priority:

1. exact effective channel number;
2. exact effective/custom display name;
3. effective display-name prefix;
4. provider/name-token matches;
5. group;
6. current programme;
7. numeric number when parseable, display name, canonical channel ID.

Generic BM25 is not used in v1 because MuxTV has stronger structured TV intent.

## 12. Programme-time invalidation

Current-programme Search membership changes with wall time even without a Room write.

Search therefore computes the earliest future programme boundary across active, current-policy, unambiguous mappings for the profile. Search TV will schedule exactly one cancellable wake-up for that boundary. No polling loop is permitted.

Boundary SQL is a measurement target on large channel/EPG sets; do not add speculative indexes until representative evidence exists.

## 13. Search TV contract (separate slice)

Search TV will use destination-scoped state and the accepted Navigation3/Player ownership model.

Required behavior:

- initial blank input;
- typing debounce starts at ~300 ms and remains measurement-tunable;
- explicit IME Search/Done bypasses debounce;
- normalized duplicate query does not restart work;
- newer generation cancels prior repository/boundary jobs;
- stale generation cannot publish over current state;
- same-query data refresh keeps Content mounted instead of destructive Loading flashes;
- query + stable canonical focus anchor survive Search -> Player -> Back;
- process death persists only query/anchor, not derived result lists.

TV focus:

1. input initially focused;
2. Down -> first result;
3. first result Up -> input;
4. OK -> existing Player;
5. Player -> Back -> same surviving canonical channel;
6. missing channel -> nearest previous;
7. no results -> input;
8. IME submit leaves the text input immediately to avoid fullscreen-keyboard traps on TV vendors;
9. no arbitrary frame delays/global focus engine.

## 14. Security/privacy

Search must never expose in public diagnostics/logs:

- raw query text;
- profile IDs;
- provider/source identities when not required for internal DB ownership;
- stream/XML locators;
- query-string secrets;
- credentials/access refs;
- cookies/Authorization/sensitive headers;
- raw exception strings containing source payload.

FTS stores only curated display/search text, never stream locators or credential material.

## 15. Search Core acceptance

Before merge PR #96 must have:

1. clean post-Favorites review surface and no stale-stack ancestry;
2. exact-head Room/KSP compile;
3. exact Room-generated v9 schema committed;
4. v8 -> v9 migration on API26/API36;
5. real `unicode61` Cyrillic prefix/case runtime proof;
6. non-zero query encoder, index lifecycle, candidate DAO and repository contracts;
7. existing source/EPG ownership regressions green;
8. existing Favorites registration preserved;
9. no semantic drift from accepted Now/Next behavior;
10. descriptive migration/backfill DB-size and representative 1k/10k/50k query measurements;
11. review of the set-based EPG vocabulary cleanup and global boundary cost before adding any new index;
12. guarded squash merge.

## 16. Explicit non-goals

Search Core does not add:

- Search Compose screen/navigation;
- Recent/Guide storage;
- remote provider federation;
- VOD/series/movie search;
- saved searches/recommendations;
- fuzzy/semantic/vector search;
- FTS5/BM25;
- bundled SQLite;
- native/Rust search;
- alternate playback engine.

Those remain separate evidence- and product-driven decisions.
