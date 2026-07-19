---
status: accepted
last_reviewed: 2026-07-19
owners: [epg, data, security]
reference_repositories:
  - XMLTV/xmltv
  - iptv-org/epg
  - kodi-pvr/pvr.iptvsimple
---

# XMLTV processing specification

## 1. Scope

MuxTV поддерживает XMLTV как внешний непроверенный EPG format. XMLTV DTD является справочной моделью, но реальные источники часто содержат неполные, расширенные или формально невалидные документы. Runtime parser работает потоково и не выполняет внешнее разрешение DTD/entities.

## 2. Security baseline

Обязательно:

- DOCTYPE запрещён либо игнорируется без external resolution;
- external general/parameter entities disabled;
- external DTD loading disabled;
- XInclude disabled;
- entity expansion limited/disabled;
- parser не выполняет schema fetch;
- compressed/decompressed size, nesting, text length и record count ограничены;
- source URL не наследует stream credentials;
- XMLTV считается потенциальным SSRF/DoS input.

DTD validation может запускаться только offline в developer corpus tooling над доверенными fixtures, но не на телевизоре для remote input.

## 3. Input

- HTTP/HTTPS URL;
- Storage Access Framework file/content URI;
- plain XML;
- gzip;
- single-payload zip;
- UTF-8 default и explicit XML encoding declaration;
- conditional GET;
- multiple EPG sources with explicit priority.

## 4. Streaming pipeline

```text
fetch → bounded decompress → secure pull parse → normalize → staging batches
      → validate → deduplicate → channel index → programme index → commit
```

Parser не строит DOM полного документа. `channel` и `programme` обрабатываются независимо и batch-ами.

## 5. Channel model

Поддерживаемые элементы:

```text
channel@id
display-name[@lang] (multiple)
icon@src/@width/@height (multiple)
url (optional)
```

Rules:

- `id` обязателен для normal channel record;
- exact external `id` сохраняется;
- несколько display-name сохраняются с language/provenance;
- первый icon не всегда лучший: выбирается безопасный preferred candidate по dimensions/scheme/host policy;
- duplicate channel IDs внутри source объединяются только если metadata compatible; иначе source получает conflict warning;
- пустой/malformed channel не ломает programmes других каналов.

## 6. Programme model

Поддерживаются:

```text
programme@start/@stop/@pdc-start/@vps-start/@channel/@showview/@videoplus/@clumpidx
title, sub-title, desc
credits
category, keyword
language, orig-language
length
icon, url
country
episode-num
video, audio
previously-shown, premiere, last-chance, new
subtitles
rating, star-rating, review
```

Не все поля обязаны попадать в UI v1, но parser сохраняет нормализованный поддерживаемый subset и unknown-safe extension metadata при разумном размере.

## 7. Time parsing

Поддерживается XMLTV timestamp:

```text
YYYYMMDDhhmmss ±HHMM
```

Допускается сокращённая точность, если она однозначна по XMLTV rules, но quality flag фиксирует inferred components.

Rules:

- offset в timestamp имеет приоритет;
- timestamp без offset не интерпретируется молча как UTC;
- source может иметь explicit default zone setting;
- при отсутствии offset и source zone программа получает `UnresolvedTimeZone` и не публикуется в active guide до подтверждения;
- DST рассчитывается timezone database, а не fixed offset;
- `stop < start` — invalid record;
- отсутствующий stop может быть inferred только до следующей программы того же channel, с provenance `InferredFromNextStart`;
- overlap сохраняется как conflict, а не silently truncates original;
- display conversion выполняется в profile/device timezone после хранения Instant.

## 8. Programme identity и deduplication

Начальный identity key:

```text
EpgSourceId + externalChannelId + startInstant + normalized content fingerprint
```

Fingerprint включает title/sub-title/episode identifiers/duration, но не description formatting и image URL tokens.

Duplicate policy:

- exact duplicate → one programme, duplicate count;
- same channel/start with compatible metadata → merge fields by completeness/provenance;
- same channel/start with conflicting titles → conflict variants, chosen display candidate with warning;
- changed stop/description on refresh updates same logical record when confidence high;
- source priority применяется только после сохранения provenance.

## 9. Retention

Храним rolling windows:

- past: минимум 24 часа для recently aired/catch-up binding, расширяется по source capability;
- future: фактически предоставленный window в пределах configured cap;
- programme history, referenced by recording/catch-up/favorite action, retained until reference expires;
- cleanup incremental и не блокирует guide.

Hard cap задаётся количеством records/bytes, а не только days.

## 10. Channel-to-EPG matching

Порядок candidates:

1. exact `tvg-id == channel@id`;
2. configured alias;
3. exact normalized `tvg-name/display-name`;
4. country/language/region-aware fuzzy name;
5. logo/metadata similarity;
6. observed schedule evidence — optional and low weight.

Запрещено:

- считать одинаковое имя достаточным для auto-confirm при разных country/region;
- перезаписывать manual binding;
- смешивать channels разных EPG sources без provenance;
- использовать programme title overlap как единственный сильный сигнал.

Thresholds и algorithmVersion находятся в scoring metadata.

## 11. Multiple sources

Для каждого canonical channel может быть:

- primary EPG binding;
- fallback bindings;
- profile-specific manual override.

Field merge policy:

- programme timeline приходит от одного selected source для данного временного диапазона;
- metadata enrichment из второго source допускается только при high-confidence same programme match;
- при source gap возможен fallback, но UI показывает provenance в diagnostics;
- source priority не означает безусловное доверие invalid data.

## 12. Incremental refresh

XMLTV обычно не имеет стабильного delta protocol. MuxTV:

- использует ETag/Last-Modified;
- загружает новую revision в staging;
- upsert/deduplicate по identity;
- удаляет только programmes, вышедшие из retention или подтверждённо отсутствующие после complete revision;
- incomplete/partial download никогда не заменяет active revision;
- source с очень большим guide может commit-ить временные partitions атомарно по documented strategy только после отдельного ADR; baseline — whole revision commit.

## 13. EPG grid loading

UI не загружает весь guide:

- viewport query by channel IDs + time interval;
- prefetch limited windows left/right;
- stable row/channel keys;
- pagination/lazy time extension;
- current programme query optimized separately;
- image loading outside database transaction;
- user-configurable future window ограничен performance budget.

Reference issue history показывает, что hardcoded short window ухудшает планирование, а безграничная загрузка разрушает TV performance. Поэтому используется lazy interval loading, а не fixed 9 hours и не full-guide-in-memory.

## 14. Diagnostics

Source status показывает:

```text
last success
HTTP/cache/decompression result
channels/programmes accepted/rejected
resolved/unresolved timezone count
duplicate/conflict count
matched/unmatched channels
coverage from/to
stale age
warnings by code
```

Пользователь видит действия: retry, edit timezone, inspect unmatched, restore previous revision.

## 15. Corpus

- official XMLTV DTD examples;
- multiple languages/display names;
- missing offset and DST transition;
- overlap/gap/duplicate programmes;
- missing stop;
- gzip/zip;
- malformed XML after large valid prefix;
- duplicate channel IDs;
- huge desc/icon fields;
- DOCTYPE/XXE/Billion Laughs fixtures;
- gzip bomb;
- 1/5/20 GB generated decompressed streams;
- cancellation/process death;
- source revisions with changed programme metadata;
- Kodi compatibility extensions such as `catchup-id`.

## 16. Critical review of references

- XMLTV project/DTD defines the broad format but does not define MuxTV runtime resource/security policy.
- Kodi IPTV Simple provides valuable de-facto mapping behavior, catch-up integration and compression support; Kodi-specific choices such as taking only the first category/icon are not automatically adopted.
- iptv-org/epg demonstrates real-world source diversity, scheduled generation, gzip and multi-source workflows; it is a guide acquisition toolkit, not a trusted client parser specification.
- StreamVault issue history highlights concrete failure modes: `.xml.gz` mistaken for unexpected content, foreign-key failures during refresh, invisible EPG sources and UI crashes. MuxTV therefore separates fetch/decode/parse/staging/commit and keeps the previous revision active.

## 17. Acceptance criteria

- external DTD/entity is never fetched;
- gzip XMLTV imports correctly;
- invalid new revision does not remove current guide;
- timezone ambiguity is visible and repairable;
- refresh preserves manual bindings;
- viewport query remains bounded;
- duplicate/conflicting programmes are deterministic and explainable;
- 20 GB generated XML can be parsed with bounded heap or is rejected by configured limits without crash;
- diagnostics explain why channels remain unmatched.