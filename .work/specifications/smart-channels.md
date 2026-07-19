---
status: accepted
last_reviewed: 2026-07-19
owners: [catalog, matching, product, ui]
---

# Smart Channels specification

## 1. Цель

Smart Channel представляет один логический телеканал поверх нескольких provider entries и stream variants. Система уменьшает дубли, сохраняет пользовательскую организацию и предоставляет резервирование, но не должна агрессивно объединять разные региональные, временные или редакционные версии.

## 2. Основные принципы

- candidate generation отделена от merge decision;
- имя не является идентификатором;
- `tvg-id` — сильный, но не абсолютный сигнал;
- автоматическое решение объяснимо и версионируется;
- high-impact merge имеет preview и undo;
- ручное решение пользователя приоритетнее алгоритма;
- разные региональные/time-shift/quality variants могут относиться к одному canonical channel только при подтверждённой смысловой идентичности;
- отдельные редакционные версии, например международная/региональная/новостная, не объединяются по похожему логотипу или имени.

## 3. Pipeline

```text
Provider channels
 → normalized features
 → blocking/indexing
 → candidate pairs/groups
 → feature scoring
 → conflict rules
 → proposal
 → auto-accept | user review | reject
 → mutation journal
```

Алгоритм не сравнивает каждую запись с каждой. Blocking keys ограничивают candidate set.

## 4. Normalized features

### Name features

- Unicode normalization;
- case folding;
- punctuation/whitespace normalization;
- transliteration variants как weak signal;
- removal of technical quality suffix in comparison key only;
- extraction of region, language, timezone shift, quality and feed hints;
- alias dictionary with provenance/version.

### Identifier features

- exact/normalized `tvg-id`;
- provider channel id;
- EPG binding;
- official/curated channel database ID if available;
- stream/program identifiers;
- logo perceptual hash — weak/medium evidence only.

### Context features

- country/region;
- language;
- provider category;
- channel number;
- programme schedule similarity;
- stream fingerprint/protocol metadata;
- provider independence;
- observed simultaneous content — optional, privacy-preserving and expensive.

## 5. Blocking keys

Candidates generated when at least one condition holds:

- exact normalized tvg-id;
- exact strong alias/database ID;
- same country/language plus close normalized name;
- same logo hash plus compatible name/context;
- same stable stream/program identifier;
- existing historical canonical alias.

Records with explicit incompatible region/feed markers may be excluded before scoring.

## 6. Hard conflicts

Automatic merge forbidden when any high-confidence conflict exists:

- different confirmed official channel/database IDs;
- incompatible countries/regions without global-feed relationship;
- distinct time-shift feeds where user may want both (`+1`, `+2`, `+7`);
- radio versus television;
- adult/restricted policy conflict that would hide allowed content unexpectedly;
- manual user split/reject rule;
- conflicting manual EPG bindings;
- simultaneous programmes consistently differ despite otherwise similar metadata;
- provider marks separate channel identities and no stronger evidence overrides.

Hard conflict can still be manually overridden with explicit preview.

## 7. Scoring model

Initial feature weights are calibration defaults, not truth:

```text
exact confirmed external ID       +0.45
exact tvg-id                       +0.30
exact normalized name              +0.18
strong alias                       +0.22
compatible country/language        +0.10
same EPG binding                   +0.25
high logo similarity               +0.08
schedule similarity                +0.12
stable stream/program relation     +0.18
region/time-shift conflict         -0.45
manual reject/split                hard reject
confirmed distinct IDs             hard reject
```

Score is bounded 0..1 after conflict rules. Missing data contributes zero, not automatic agreement.

Initial thresholds:

```text
>= 0.92  auto-merge only when no hard conflict and policy permits
0.72–0.92 review proposal
< 0.72   keep separate
```

Auto-merge may be disabled globally until corpus precision target is proven.

## 8. Precision requirement

False positive merge is substantially more harmful than missed duplicate. Therefore:

- auto-merge target precision >= 99.5% on labeled corpus;
- recall is secondary;
- regional/time-shift corpus reported separately;
- every algorithm version produces calibration report;
- production threshold never lowers silently;
- uncertain cases remain separate and may be grouped visually as «возможные дубли».

## 9. Proposal model

```text
proposalId
candidateCanonicalIds/providerIds
score
algorithmVersion
positive evidence[]
conflicts[]
expected result
profile impact summary
createdAt/state
```

UI explanation example:

```text
Похоже, это один канал:
• одинаковый tvg-id
• совпадает программа передач
• различается только качество потока
```

UI also shows sources/quality/region and what will happen to favorites, numbering, EPG and preferred stream.

## 10. Merge mutation

- one canonical ID survives;
- loser IDs become aliases/tombstones;
- memberships/variants transfer;
- profile overlays are merged with explicit deterministic rules;
- favorite true wins;
- user display names conflict → require choice;
- number conflict → preserve profile ordering and request choice if both explicit;
- manual EPG conflict → merge blocked until resolved;
- preferred variant preserved if still valid;
- mutation and exact inverse are journaled.

## 11. Split mutation

User may split variants/provider memberships from Smart Channel.

- new canonical ID created;
- user chooses display metadata and overlay inheritance;
- split creates persistent negative-link rule for the selected identities/features;
- future algorithm versions do not re-merge automatically;
- undo restores previous exact state before journal compaction.

## 12. Variant classes inside one Smart Channel

Variants may be tagged:

```text
Quality: SD/HD/FHD/UHD/unknown
Feed: main/backup/region/time-shift/commentary
Protocol: HLS/DASH/TS/RTSP/other
Source: provider identity
Capability: live/catch-up/timeshift/audio/subtitle
```

Quality alone does not create separate canonical channel. Time-shift/region feed defaults to separate unless relationship is explicit and user wants it grouped as selectable feed.

## 13. Selection behavior

Smart Channel supplies candidate variants to playback orchestrator. It does not directly control Media3.

- profile preference filters/ranks candidates;
- manually pinned variant has priority;
- temporary failure triggers recovery/failover policy;
- successful fallback does not permanently replace manual pin unless user confirms;
- repeated evidence updates health score;
- source credentials/policies remain variant-scoped.

## 14. User controls

Channel context menu:

- «Источники и качество»;
- set preferred variant;
- allow/disallow automatic fallback;
- merge with another channel;
- split selected source/feed;
- reject duplicate suggestion;
- inspect explanation/history;
- restore automatic selection.

Simple mode exposes only useful actions; expert mode exposes evidence and raw source labels.

## 15. Algorithm versioning

Stored decisions include `algorithmVersion` and `featureSchemaVersion`.

- accepted manual merges survive upgrades;
- manual rejects/splits survive upgrades;
- auto-merges can be audited but are not silently reversed;
- new version evaluates pending/unconfirmed candidates;
- mass reconsideration requires preview, backup checkpoint and migration plan.

## 16. Privacy and computation

Default matching is local. Schedule similarity uses locally imported EPG. Stream sampling/fingerprinting:

- is opt-in or performed only during explicit TV Doctor checks;
- uses bounded samples;
- never uploads content/fingerprints by default;
- respects provider limits;
- avoids simultaneous decoder sessions on weak TVs.

## 17. Test corpus

Labeled pairs/groups include:

- same channel SD/HD/FHD;
- same name, different countries;
- regional feeds;
- `+1/+2/+7` feeds;
- renames/rebrands;
- transliterated names;
- logos reused across network families;
- same tvg-id incorrectly reused by provider;
- blank metadata;
- one stream duplicated across multiple entries;
- channel families with similar names;
- manual split/merge replay;
- algorithm upgrade stability.

Metrics:

```text
precision/recall/F1
auto-merge precision
review acceptance rate
false-positive severity by class
runtime and peak memory
candidate pairs per input size
```

## 18. Acceptance criteria

- no merge based solely on display name;
- hard conflicts prevent auto-merge;
- every proposal explains evidence;
- merge/split are transactional and reversible;
- manual decisions persist across refresh and algorithm upgrades;
- profile overlays remain correct;
- auto-merge precision threshold is evidenced before enabling;
- matching 100k channels uses bounded candidate generation, not O(n²).