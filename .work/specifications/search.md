---
status: accepted
last_reviewed: 2026-07-19
owners: [search, catalog, epg, ui, profiles]
---

# Search specification

## 1. Цель

Search finds channels, current/future programmes, groups and user collections quickly with remote/voice/text input. Baseline is deterministic local search; LLM/cloud is not required.

## 2. Search corpus

Installation-scoped searchable entities:

- canonical channel name/aliases;
- provider names as secondary aliases;
- EPG programme title/sub-title/description/categories/participants where available;
- canonical/provider groups;
- countries/languages;
- source labels in expert mode.

Profile-scoped:

- custom groups;
- display name overrides;
- favorites/recent boost;
- hidden/restricted filtering;
- search history/preferences.

Restricted/hidden content is filtered before result rendering. Search cannot bypass profile policy.

## 3. Query pipeline

```text
input
 → Unicode/case/whitespace normalization
 → keyboard layout/transliteration alternatives
 → lightweight intent parser
 → token/prefix/fuzzy retrieval
 → policy filter
 → profile/context ranking
 → grouped results
```

Raw query remains local. Voice input uses system transcription output; MuxTV does not require cloud speech service of its own.

## 4. Supported query intents

Rule-based intents:

```text
channel name/alias
programme title
«сейчас» / «идёт сейчас»
«сегодня вечером» / time window
«после 21:00»
category/genre: новости, спорт, фильмы, детям
language/country
favorite/recent/custom group
channel number
```

Examples:

```text
футбол сейчас
новости
фильмы после 21:00
матч тв
каналы на русском
избранное
123
```

Ambiguous natural phrase falls back to text search, not fabricated intent.

## 5. Indexes

Baseline uses SQLite FTS where compatible with selected Room/SQLite setup plus normalized side tables.

Separate indexes:

- channel aliases/display names;
- programme text/time/channel;
- groups/categories;
- transliteration/normalized keys.

Index rows reference stable entity IDs and revision/version. Source/EPG commit schedules incremental/rebuild job after active revision switch; active UI may use previous index until new one is ready.

## 6. Ranking

Initial ranking features:

```text
exact display-name match
exact alias/tvg-id match
prefix match
normalized token match
fuzzy distance
current programme match
future time relevance
favorite/recent boost
current group/context
language/profile preference
source/provider confidence
```

Ranking is explainable and versioned. Favorite/recent boosts cannot make weak unrelated match outrank exact match.

Suggested hierarchy:

1. exact channel/profile override;
2. exact programme in requested time;
3. exact alias/prefix;
4. token match;
5. fuzzy/transliteration;
6. description-only match.

## 7. Time interpretation

Use profile/device timezone and injected clock.

Supported baseline:

- now;
- today/tomorrow;
- morning/day/evening/night configured locale intervals;
- explicit `HH:mm`/after/before;
- current EPG coverage only.

Query result shows actual date/time. DST boundaries rely on timezone library/Instant storage. No hidden assumption that provider timezone equals profile timezone.

## 8. Transliteration and keyboard tolerance

- Cyrillic↔Latin transliteration variants as weak aliases;
- common keyboard-layout mistype mapping may be offered after corpus evidence;
- normalization does not overwrite displayed text;
- language-specific stemming/tokenization considered only with tested libraries/data;
- fuzzy distance bounded by token length and candidate set;
- no unbounded scan across full programme descriptions per keystroke.

## 9. Result groups

```text
Channels
On now
Later
Programmes
Groups
Recent searches
```

Only non-empty groups shown. Focus order stable as query updates; current focused stable result retained if still present. New results do not jump above focused item while user navigates without controlled update.

## 10. TV UX

- search input activated explicitly;
- system keyboard/voice optional;
- Back first exits editing/keyboard, then search route;
- results visible while typing with debounce;
- loading/empty/error states have reachable actions;
- recent queries removable individually/all;
- selecting channel starts playback; selecting programme opens detail/channel/catch-up action according capability;
- numeric-only query may offer direct channel switch;
- long descriptions not shown in result list;
- focus restoration follows `design/focus-navigation.md`.

## 11. Performance

Budgets calibrated on 100k channels/large EPG:

- common prefix/exact results p95 target <= 150 ms from local index after debounce;
- result page bounded (e.g. top 20/group) with pagination/details;
- query cancellation on input change;
- DB work off main thread;
- index update not inside source/EPG commit transaction;
- programme retention keeps index bounded;
- description search may be delayed/optional under weak device profile.

## 12. Privacy

- query/history local per profile;
- no mandatory analytics/upload;
- voice provider privacy belongs to selected system input and is not hidden by MuxTV;
- history excluded from backup unless user includes it;
- restricted content terms/results do not leak when switching profiles;
- diagnostic logs record timings/counts, not raw query by default.

## 13. Tests

- exact/prefix/fuzzy/transliteration ranking;
- favorite boost cannot beat unrelated exact match;
- Russian/English locale/time phrases;
- timezone/DST boundaries;
- profile display override and custom group;
- hidden/restricted result filtering;
- source/EPG revision index switch;
- stale index fallback/rebuild;
- rapid query cancellation;
- focus stability as result groups change;
- keyboard Back contract;
- 100k/large EPG latency/memory benchmark;
- no query/history cross-profile leakage.

## 14. Acceptance criteria

- exact channel/programme found predictably;
- queries such as «футбол сейчас» and «фильмы после 21:00» work without LLM;
- search cannot reveal restricted profile content;
- query updates do not randomly steal focus;
- result ranking is deterministic/versioned;
- large corpus search is indexed/bounded, not full scan;
- search remains usable offline with cached catalog/EPG;
- query data stays local by default.