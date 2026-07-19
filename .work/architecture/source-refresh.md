---
status: accepted
last_reviewed: 2026-07-19
owners: [data, catalog, background-work]
---

# Source refresh и атомарное обновление каталога

## 1. Цель

Обновление M3U/XMLTV/provider source не должно оставлять базу в частично обновлённом состоянии, блокировать просмотр или уничтожать пользовательские overlays.

## 2. State machine

```text
Idle
 → Queued
 → Downloading
 → Decoding
 → Parsing
 → Validating
 → Staging
 → Diffing
 → Matching
 → ReadyToCommit
 → Committing
 → Completed

Любое промежуточное состояние
 → Cancelled | FailedRetriable | FailedPermanent
```

`Completed` означает committed revision, а не только успешную загрузку.

## 3. Revision model

Каждый refresh создаёт immutable `SourceRevision`:

```text
revisionId
sourceId
startedAt/completedAt
fetch metadata: status, ETag, Last-Modified, content type, encoding
content digest after decompression
parser version
normalization version
record counts
warnings/errors
state
```

Source указывает на `activeRevisionId`. Пока новый revision не committed, UI продолжает читать старый.

## 4. Scheduling

- unique work key: `source-refresh:{SourceId}`;
- повторный запрос для того же source объединяется с текущим либо создаёт replace request по явной команде;
- periodic refresh использует WorkManager только для deferrable работы;
- пользовательский refresh показывает progress и может использовать foreground/user-initiated execution;
- декомпрессия/парсинг больших источников должны быть chunkable и resumable, чтобы не зависеть от неограниченного long-running worker;
- refresh не запускается параллельно с destructive source edit transaction.

## 5. Fetch

Политика запроса:

- conditional GET через ETag/Last-Modified, если source корректно их поддерживает;
- redirect budget и host transition policy;
- connect/read/overall timeout;
- maximum compressed size;
- maximum decompressed bytes и compression ratio;
- content type является hint, но sniffing ограничен whitelist форматов;
- credentials и custom headers применяются через request policy reference;
- redirect не получает Authorization/Cookie при смене origin без явного разрешения;
- response временно пишется в app-private storage, не в RAM целиком.

`304 Not Modified` завершает refresh без нового content revision, но обновляет health metadata.

## 6. Decode

Поддержка:

- plain text/XML;
- gzip;
- zip с ровно одним выбранным допустимым payload либо явным выбором entry;
- xz рассматривается после benchmark и threat review;
- BOM и charset hints;
- UTF-8 default;
- fallback legacy charset только по явной source setting или детектированию с confidence и preview.

Защита:

- запрещён path traversal archive entries;
- лимиты entries, total output, ratio и nested archive depth;
- nested archives по умолчанию запрещены;
- checksum вычисляется потоково.

## 7. Parse и staging

Parser выдаёт bounded batches. Для каждого batch:

1. syntax validation;
2. normalization без потери raw values;
3. запись в staging tables;
4. накопление статистики и warnings;
5. cooperative cancellation.

Staging tables содержат `revisionId`; active tables не модифицируются.

## 8. Validation gates

Revision отклоняется до commit при:

- отсутствии валидных записей;
- превышении error ratio;
- неожиданном падении channel count сверх policy;
- массовом изменении identity, похожем на parser/encoding failure;
- malformed content, который нельзя безопасно ограничить;
- database constraints failure;
- отмене пользователем.

Начальные guardrails:

```text
valid records >= 1
fatal parse errors = 0
record-level error ratio <= 5%
channel count drop > 50% → require confirmation, если предыдущий count >= 20
identity churn > 40% → reject as suspicious unless source reset confirmed
```

Пороги настраиваются после corpus calibration и фиксируются в metadata.

## 9. Diff

Diff классифицирует:

- added;
- updated metadata;
- changed locator only;
- moved group/number;
- temporarily missing;
- retired;
- identity uncertain.

Token/query-only URL changes не считаются новым каналом. Все решения сохраняют confidence и reason codes.

## 10. Matching

Перед commit выполняются:

- сопоставление staged provider channels с предыдущей revision;
- обновление canonical memberships;
- duplicate candidate generation;
- EPG re-evaluation только для unconfirmed bindings;
- preservation map для overlays/history.

Автоматический merge canonical channels не выполняется внутри обычного refresh без заранее принятой policy. Refresh может создавать proposals.

## 11. Commit

Один Room transaction:

1. проверяет, что active revision не изменился после начала diff;
2. применяет provider rows и tombstones;
3. обновляет memberships/variants;
4. сохраняет unresolved references;
5. переключает `activeRevisionId`;
6. записывает audit summary;
7. планирует post-commit index/cache jobs.

Если transaction падает, active revision остаётся прежней.

## 12. Post-commit

После commit отдельно выполняются:

- FTS/search index update;
- image prefetch с бюджетом;
- EPG match proposals;
- health probe queue;
- cleanup expired staging/revisions;
- UI notification с summary.

Ошибка post-commit job не откатывает catalog revision, но отображается как degraded state и retryable task.

## 13. Source edit

Изменение URL/credentials/headers не создаёт новый SourceId. Перед сохранением выполняется validation probe. Пользователь видит:

- изменяемые поля;
- возможность test connection;
- влияние на cached catalog;
- выбор «сохранить старый каталог до успешного refresh» — default true.

## 14. Cancellation и recovery

- cancellation до commit удаляет или помечает staging revision abandoned;
- cancellation во время database transaction откладывается до безопасной границы;
- после process death незавершённый revision определяется по state и lease timestamp;
- stale lease освобождается;
- incomplete temp files удаляются bounded cleanup job;
- retry использует exponential backoff с provider-aware `Retry-After`.

## 15. Наблюдаемость

Refresh summary:

```text
correlationId
sourceId
old/new revision
fetch timings and sizes
parsed/accepted/rejected counts
added/updated/missing counts
warnings by code
commit duration
```

Raw URLs, query tokens, credentials и full playlist content в диагностический пакет не входят.

## 16. Критерии приёмки

- просмотр и каталог доступны во время refresh;
- process death на любом шаге не повреждает active catalog;
- падение channel count не применяется молча;
- overlays сохраняются при переименовании/перемещении;
- повторный identical payload не создаёт churn;
- tokenized URL refresh обновляет variant, а не identity;
- пользователь видит понятный summary и warnings;
- cancellation оставляет систему в согласованном состоянии.