# TV / IPTV Search Comparative Review

**Date:** 2026-08-03  
**Purpose:** evidence for MuxTV issue #29 Search design  
**Design:** `docs/superpowers/specs/2026-08-03-bounded-tv-search-design.md`

This review compares search implementations and failure reports by transferability to MuxTV, not by repository popularity. MuxTV is a local-first Android TV application with canonical channel identity, profile overlays, immutable catalog/EPG revisions, current-policy EPG matching, Room 3 and a process-owned Media3 Player.

## Evaluation dimensions

Each project was inspected for one or more of:

- TV remote / D-pad / software keyboard behavior;
- query lifecycle, debounce, cancellation and restoration;
- IPTV channel number/group/EPG behavior;
- local database and FTS architecture;
- result bounding/truncation/pagination;
- multilingual/Unicode behavior;
- optional-enrichment joins and stale data failure modes;
- federation/provider abstractions;
- relevance/ranking complexity.

## High-transfer references

| Project | Relevant implementation / behavior | Adopt in MuxTV | Reject / defer |
| --- | --- | --- | --- |
| Jellyfin Android TV | Dedicated Search ViewModel; trimmed/deduplicated query; cancellable debounced search; immediate submit path; saveable input; explicit input/results focus; Amazon fullscreen keyboard focus escape | Debounce + immediate submit, generation cancellation, TV focus graph, Fire TV IME escape | Parallel media-type server searches |
| IPTVnator | Global/category search, lazy loading for large playlists, channel-number UX, preserved query/results on return | Player->Back query/focus continuity, number-first intent, hard/lazy bounds | Live/VOD/Series federation before those domains exist |
| Tvheadend | EPG API search/filtering, explicit current-time mode, deterministic sort and `limit`/`start` | Explicit `now` semantics, backend filtering, hard bound, deterministic ordering | Large advanced filter surface in v1 |
| Kodi | Long-lived PVR/EPG/global-search support; current releases still carry EPG-search fixes; couch/remote-first product | Treat EPG search as a real PVR domain, preserve remote-first interaction | Kodi add-on/back-end federation architecture |
| MythTV | Program Search by title/channel/people/category/keyword; result position; Program Finder; stored searches | Honest result state and possible future filter/history extension | Stored SQL/power-search UI in first slice |
| Navidrome | Search rebuilt on SQLite FTS5 with two-phase BM25 for large music libraries | Derived FTS index is a valid scalable architecture | FTS5/BM25 before Android driver/runtime ADR and measurement |
| Immich | Metadata search broke when optional `smart_info` became an INNER JOIN; semantic result caps/pagination caused UX issues | Optional EPG must never be mandatory; explicit truncation | Vector/semantic top-N search |
| NewPipe | Remote provider searches are expensive; filter/search lifecycle reflects network cost | Keep local Room typeahead separate from any future remote search | Applying local per-keystroke behavior to remote providers |

## Domain and architecture cross-checks

| Project | What it adds to the review | MuxTV decision |
| --- | --- | --- |
| ErsatzTV | Curated default search field plus explicit advanced fields | Search only approved channel/name/number/group/current-programme fields; no public query DSL v1 |
| Threadfin | Active/inactive channel, group and channel-number boundaries | FTS can only propose candidates; final active/visible validation mandatory |
| PhotoPrism | Mature query/filter vocabulary required escaping/operator fixes | Keep user query plain text and encode safe private FTS tokens |
| Audiobookshelf | Real demand for diacritic-insensitive/international search | Unicode behavior is correctness acceptance, not polish |
| Kvaesitso | Search-provider model for federated Android launcher search | Provider framework is YAGNI for one local canonical channel source |
| Lawnchair | Search backend/provider redesign and switchable algorithms/providers | Same conclusion: avoid speculative federation abstraction |
| Emby | Product-level global and library-scoped search, partial-word expectations | Scope must be explicit; MuxTV v1 is live channel/current-EPG only |
| SmartTube | TV-focused search/voice/keyboard constraints | Treat TV IME/focus as device-sensitive; voice/embedded keyboard deferred |
| Swiftfin | tvOS media client; tvOS focus management called out as a platform-specific challenge | Reinforces explicit platform focus ownership; Android TV design still follows Jellyfin Android TV evidence |

## Supplementary implementation surveys

These projects were inspected to broaden the comparison but did not provide a stronger directly transferable Search contract than the references above for this specific MuxTV slice:

| Project | Reason it remains supplementary |
| --- | --- |
| VLC Android | Strong Android/TV local media architecture; no more compelling Search-specific contract extracted than Jellyfin/IPTVnator |
| NOVA Video Player | Android TV media client with metadata/search fixes; Search internals not sufficiently authoritative for a design dependency |
| Jellyfin Web | Mature media Search but web focus/input constraints differ from Android TV |
| Findroid | Kotlin/Compose Jellyfin client; useful Compose cross-check, Android TV Search not the strongest current reference |
| Jellyfin Roku | Couch UI confirms keyboard/search device constraints, but Roku interaction model differs from Android TV |
| Lampa | Important TV media product to survey; available evidence did not justify treating plugin/fork search behavior as authoritative Lampa-core architecture |
| Ente | Local/private semantic photo search; useful contrast but deterministic channel lookup does not need vectors |
| Stremio ecosystem | Federated content discovery is central to product architecture; not transferable to one local canonical IPTV catalog without adding speculative federation |

## SQLite / Room findings that changed the design

### Ordinary LIKE is not enough

SQLite's default case-insensitive `LIKE`/`NOCASE` behavior does not provide full Unicode case folding. This makes a LIKE-only design a correctness risk for Russian channel and programme names.

### FTS4 + unicode61 is the current best fit

For MuxTV's accepted platform/default SQLite boundary:

- Room 3 supports FTS4 external-content entities;
- Android platform SQLite includes FTS4;
- SQLite `unicode61` performs Unicode-aware simple case folding/tokenization;
- FTS4 token-prefix search supports `token*` without requiring dedicated prefix indexes;
- optional `prefix=` indexes trade larger DB/slower writes for faster particular prefix lengths and therefore remain measurement-gated.

### Why not FTS5 now

Room 3 exposes FTS5, but Android availability is driver-dependent and is guaranteed by `BundledSQLiteDriver`. MuxTV currently does not use a bundled driver. Search alone does not justify changing the database runtime/packaging boundary when FTS4/unicode61 covers the required Unicode token-prefix semantics.

## Design lessons extracted from failures

1. **Do not make optional enrichment mandatory.** Channel-name results survive with no EPG.
2. **Do not lie about completeness.** Any candidate/public cap has explicit truncation state.
3. **Do not expose raw FTS syntax.** Search text becomes private encoded Unicode token-prefix queries.
4. **Do not require every token in one document.** Different tokens may match name, number, group or current programme. MuxTV intersects bounded canonical candidate sets per token.
5. **Do not make FTS source of truth.** Every hit is validated against active source revision, profile visibility and current-policy EPG.
6. **Do not use arbitrary delays for TV focus.** Search owns an explicit input/result/submit focus graph.
7. **Do not serialize result lists into navigation state.** Persist query + focus anchor; re-derive results from Room.
8. **Do not create a provider framework until federation exists.** One `ChannelSearchRepository` is enough.
9. **Do not copy BM25 because another library uses it.** Structured channel-number/name intent outranks generic document relevance.
10. **Do not normalize query text differently from indexed text.** Initial encoder trims/collapses whitespace and delegates case/diacritic token behavior to `unicode61`.

## Current recommended architecture

- Room v9 derived `search_documents` content table;
- Room 3 FTS4 external-content table with `unicode61`;
- max six plain-text query tokens;
- bounded per-token candidate query (`MAX_CANDIDATES_PER_TOKEN` initially 800) plus canonical-ID intersection;
- final active/visible/current-policy validation;
- final structured ranking: exact number -> exact effective name -> name prefix -> raw name -> group -> current programme;
- public limit 100 default / 200 max;
- explicit `isTruncated`;
- 300 ms local typing debounce + immediate IME submit;
- Search ViewModel generation/cancellation ownership;
- Search -> Player -> Back query/focus restoration;
- programme-boundary wake-up without polling;
- no bundled SQLite, FTS5, BM25, vectors, provider framework, remote search or Rust in v1.

## Remaining uncertainty to measure, not guess

- FTS4 derived-index DB size on 10k/50k + EPG retained revisions;
- v8->v9 backfill time/peak memory on old-edge/low-RAM profiles;
- cost of up to six bounded per-token FTS/validation queries;
- best candidate cap after observing real selectivity;
- whether FTS4 `prefix=` indexes provide enough latency benefit to justify write/size cost;
- actual Fire TV keyboard behavior on physical hardware;
- whether future user data demonstrates demand for transliteration, middle-token contains, saved searches or advanced field filters.

Those are acceptance/measurement questions. They do not justify speculative architecture before the first Unicode-correct bounded Search ships.