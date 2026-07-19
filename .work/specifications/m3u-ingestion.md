---
status: accepted
last_reviewed: 2026-07-19
owners: [catalog, network, security]
reference_repositories:
  - kodi-pvr/pvr.iptvsimple
  - oxyroid/M3UAndroid
  - Davidona/StreamVault-IPTV
---

# M3U/M3U8 ingestion specification

## 1. Scope

MuxTV принимает extended M3U playlists как непроверенный, часто нестандартный текстовый формат. Цель parser — сохранить полезные metadata, диагностировать отклонения и не исполнять произвольные инструкции.

## 2. Поддерживаемый вход

- URL HTTP/HTTPS;
- локальный файл через Storage Access Framework;
- content URI;
- plain, gzip и single-payload zip;
- UTF-8 with/without BOM;
- configurable legacy charset fallback;
- line endings LF/CRLF/CR;
- большие файлы с потоковой обработкой.

## 3. Минимальная запись

```text
#EXTM3U
#EXTINF:-1,Channel name
https://example.invalid/live/channel.m3u8
```

Channel entry формируется только после валидного locator. Незавершённый `#EXTINF` сохраняется как warning, но не создаёт playable variant.

## 4. Recognized global directives

| Directive/attribute | Поведение |
|---|---|
| `#EXTM3U` | marker; отсутствие допускается в lenient mode с warning |
| `x-tvg-url`, `url-tvg` | EPG source proposals, не добавляются автоматически без preview |
| `tvg-shift` | source default time correction |
| `catchup-correction` | source default catch-up correction |
| unknown attributes | сохраняются raw, не влияют на поведение без adapter rule |

Несколько EPG URL разбираются с учётом quoted values и separators; они не получают credentials основного stream автоматически.

## 5. Recognized entry attributes

```text
tvg-id
tvg-name
tvg-logo
tvg-chno
channel-number
group-title
radio
language
country
provider
provider-type
tvg-shift
catchup
catchup-source
catchup-days
catchup-correction
timeshift
media
media-dir
media-size
```

Parser не предполагает единый стандарт написания. Attribute names нормализуются case-insensitively, raw spelling сохраняется.

## 6. Additional directives

### `#EXTGRP`

Используется как fallback group, если `group-title` отсутствует. Несколько групп допускаются через provider-specific separators, но original value сохраняется.

### `#EXTVLCOPT`

Whitelist:

- `http-user-agent`;
- `http-referrer`/`http-referer`;
- `http-cookie`;
- `program`;
- ограниченные network hints.

Directive преобразуется в `RequestPolicyDraft`; секретные значения не попадают в логи. Неизвестные options не исполняются.

### `#KODIPROP`

Сохраняется как compatibility metadata. Автоматически применяются только явно поддержанные безопасные ключи. Plugin URLs, inputstream selectors и custom executable hooks не исполняются.

### URL pipe headers

Синтаксис `url|Header=Value&...` может быть импортирован в expert mode. Разбор выполняется после URL parsing и percent-decoding rules. Headers проходят whitelist/denylist и не передаются на другой origin при redirect.

## 7. Locator types

Разрешённые схемы baseline:

- `http`;
- `https`;
- `rtsp` после отдельной capability проверки;
- `content`/local file только для локального media import;
- provider resolver keys из доверенного adapter.

Запрещены по умолчанию:

- `file://` из remote playlist;
- `javascript:`;
- `intent:`;
- `data:` для media;
- arbitrary `content://` из remote input;
- executable/plugin schemes без установленного и разрешённого adapter.

Относительный URL разрешается только относительно конечного URL playlist после redirects. Для локального файла относительный путь ограничен выбранным document tree.

## 8. Catch-up normalization

Поддерживаются как compatibility inputs:

- `default`;
- `append`;
- `shift`/legacy `timeshift`;
- `flussonic`;
- `xtream`/`xc`;
- `vod`;
- provider-specific declarative template.

Raw catch-up template не форматируется parser-ом. Он преобразуется в typed template AST с whitelist placeholders:

```text
utc, lutc, timestamp, now, start, end, duration,
Y, m, d, H, M, S, catchup-id
```

Unknown placeholder делает catch-up capability `UnsupportedTemplate`, но не ломает live channel.

## 9. Name/group normalization

Normalization отделена от parsing.

Parser возвращает exact raw values. Normalizer может:

- trim Unicode whitespace;
- normalize Unicode form;
- удалить служебные quality suffixes только в candidate key;
- выделить region/language/quality hints;
- разбить multiple groups;
- обнаружить replacement characters и encoding damage.

Original display name всегда доступен в diagnostics.

## 10. Error model

### Fatal source errors

- decompression limit exceeded;
- binary/unsupported payload;
- no valid entries;
- security policy violation;
- I/O failure without usable cache.

### Record warnings

- malformed attribute quoting;
- duplicate attribute;
- missing `#EXTINF`;
- invalid channel number;
- unsupported scheme;
- invalid/oversized logo URL;
- unknown catch-up mode;
- encoding replacement;
- orphan directive;
- duplicate exact locator.

Каждый warning имеет code, line/index, redacted context и severity.

## 11. Resource limits

Начальные limits, подлежащие corpus calibration:

```text
compressed source: 256 MB
uncompressed source: 2 GB
single line: 256 KiB
attributes per entry: 128
attribute value: 64 KiB
entries: 500,000
redirects: 5
archive entries: 16, usable payloads: 1 by default
```

Парсер не хранит весь playlist и все raw lines одновременно.

## 12. Duplicate input behavior

- одинаковый locator в одном source может обозначать alias; entries сохраняются, а duplicate proposal строится позже;
- одинаковый `tvg-id` не считается гарантированной identity;
- одинаковое имя не означает один канал;
- один entry может иметь один primary locator; альтернативы создаются отдельными variants или adapter metadata;
- exact duplicate lines схлопываются только с audit count.

## 13. Caching и refresh

- сохраняются ETag/Last-Modified;
- original downloaded payload может храниться bounded time для debugging только локально;
- parser output versioned;
- изменение parser/normalizer version не должно автоматически менять active catalog без controlled reprocess;
- source cache никогда не является backup пользовательских overlays.

## 14. Security

- playlist считается untrusted;
- HTTP headers фильтруются;
- Authorization/Cookie не следуют cross-origin redirect;
- private/LAN destinations регулируются source network policy;
- logo URLs скачиваются отдельным ограниченным client;
- remote playlist не может читать локальные файлы;
- log redaction удаляет userinfo, query secrets и sensitive headers;
- parser не исполняет scripts, Kodi plugins, VLC commands или extensions.

## 15. Corpus tests

Corpus включает:

- корректные минимальные/extended playlists;
- Kodi IPTV Simple examples;
- common catch-up variants;
- quoted commas/equal signs;
- CR-only and BOM files;
- Windows-1251 fixture;
- gzip/zip;
- malformed quoting;
- very long lines;
- duplicate IDs/names/URLs;
- redirects and relative URLs;
- tokenized URLs;
- hostile schemes, archive traversal и decompression bomb fixtures;
- cancellation every N records;
- 1k/10k/100k/500k generated entries.

## 16. Критическая оценка reference implementations

- Kodi IPTV Simple — наиболее полезный справочник де-факто атрибутов, catch-up templates и multiple source behavior, но его semantics привязаны к Kodi inputstreams и не копируются напрямую.
- M3UAndroid демонстрирует пользу разделения TV/phone apps, parser build и benchmark modules, но его более широкий module graph не является автоматической целью MuxTV.
- StreamVault подтверждает востребованность multiple providers, QR setup, manual EPG overrides и compatibility controls; одновременно его issue history показывает риск слишком раннего объединения Live TV, VOD, DVR, plugins и множества provider protocols.

## 17. Критерии приёмки

- корректный playlist импортируется потоково;
- malformed record не обрушает весь source, если остаются валидные entries;
- опасная схема не открывается;
- catch-up metadata сохраняется типизированно;
- exact raw metadata и warnings доступны для диагностики;
- 100k entries не требуют линейного хранения raw input в heap;
- parser deterministic для одинаковых bytes/version/settings;
- credentials не появляются в logs, crash reports и warning snippets.