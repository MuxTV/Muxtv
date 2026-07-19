---
status: accepted
last_reviewed: 2026-07-19
owners: [architecture, data, catalog, epg]
---

# Domain model и инварианты каталога

## 1. Принцип

MuxTV не использует M3U-запись как конечную модель канала. Входные данные провайдера являются наблюдением, пользователь взаимодействует с устойчивым `CanonicalChannel`, а конкретные URL представлены отдельными `StreamVariant`.

```text
Source
  └─ ProviderChannel
       └─ StreamVariant

CanonicalChannel
  ├─ StreamVariant A
  ├─ StreamVariant B
  └─ StreamVariant C

UserProfile
  └─ UserChannelOverlay
```

## 2. Идентификаторы

Все внутренние IDs — opaque typed IDs, не отображаемые пользователю.

| Entity | Stable identity |
|---|---|
| `Source` | generated UUID, сохраняется при URL/имени edit |
| `ProviderChannel` | `SourceId + ProviderChannelKey` |
| `StreamVariant` | `ProviderChannelId + VariantFingerprint` |
| `CanonicalChannel` | generated UUID, сохраняется при merge/split/rename |
| `EpgSource` | generated UUID |
| `EpgChannel` | `EpgSourceId + external channel id` |
| `EpgProgram` | source/channel/start + normalized content fingerprint |
| `UserProfile` | generated UUID |
| `UserChannelOverlay` | `ProfileId + CanonicalChannelId` |

URL не является стабильным ID: signed URL, token, CDN, query и redirect могут меняться.

## 3. Source

`Source` описывает пользовательски добавленный источник и его refresh policy.

Обязательные поля:

```text
id
kind: M3U | XTREAM | STALKER | JELLYFIN | DECLARATIVE_EXTENSION
name
endpoint reference
credential reference
refresh policy
network policy
state
last successful revision
```

Credentials хранятся отдельно. Domain events и логи содержат только `CredentialRef`.

## 4. ProviderChannel

Представляет канал таким, как его объявил конкретный source.

Сохраняются raw и normalized значения:

- original display name;
- tvg-id/tvg-name;
- provider channel number;
- raw groups;
- logo reference;
- language/country/provider hints;
- catch-up metadata;
- original attribute map для диагностируемой совместимости;
- provenance: source revision, line/index, parser warnings.

Raw metadata никогда не перезаписывает user overlay.

## 5. StreamVariant

Один вариант воспроизведения.

```text
id
providerChannelId
resolverKind
opaque locator
request policy reference
container/protocol hints
quality hints
catch-up capability
health aggregate
state: Active | TemporarilyUnavailable | Retired
```

`opaque locator` может быть URL template или provider-specific resolver key. Domain не предполагает, что URL постоянен.

Variant fingerprint строится из нормализованного host/path structure, provider identifiers, program id и устойчивых header names. Secrets, token values и volatile timestamps исключаются.

## 6. CanonicalChannel

Логический канал, видимый пользователю.

Инварианты:

- может существовать без активных variants в течение retention window;
- имеет zero-or-more provider memberships;
- merge не меняет identity выбранного winner channel;
- split создаёт новый canonical ID только для отделяемой части;
- user overlays привязаны к canonical ID и мигрируют по явному merge map;
- автоматическое удаление canonical channel запрещено, если есть profile overlay/history/favorite/manual EPG binding.

Canonical metadata имеет provenance и priority:

1. explicit user override;
2. confirmed curated metadata;
3. highest-confidence EPG metadata;
4. selected provider metadata;
5. normalized fallback.

## 7. UserChannelOverlay

Содержит только пользовательские изменения:

```text
displayNameOverride
logoOverride
numberOverride
favorite
hidden
customGroupMemberships
preferredVariantId
playbackPreferenceOverride
manualEpgBindingId
```

Отсутствующее поле означает наследование, а не пустое значение. Это позволяет обновлять provider metadata без потери персонализации.

## 8. Groups

Различаются:

- `ProviderGroup` — как пришла из source;
- `CanonicalGroup` — нормализованная глобальная категория;
- `UserGroup` — созданная в профиле коллекция.

ProviderGroup не используется как permission boundary. Категория может быть ошибочной, смешанной или переименованной провайдером.

## 9. EPG

`EpgBinding` хранит:

```text
canonicalChannelId
epgChannelId
scope: GlobalSuggested | ProfileOverride
method: ExactId | Alias | Fuzzy | Manual
confidence
algorithmVersion
provenance
confirmedAt
```

Manual binding имеет приоритет и не пересчитывается автоматически. Изменение algorithm version может пересчитать только неподтверждённые bindings.

## 10. Merge и split

### Merge

- выбирается surviving `CanonicalChannelId`;
- второй ID помещается в alias/tombstone map;
- overlays объединяются детерминированно;
- конфликты manual overrides требуют preview;
- history перепривязывается на surviving ID;
- действие записывается в `CatalogMutation` и обратимо до compaction.

### Split

- создаётся новый canonical ID;
- пользователь выбирает variants/memberships для переноса либо используется high-confidence proposal;
- overlays не копируются молча;
- favorites/order/manual EPG предлагаются в preview;
- undo хранит точную inverse mutation.

## 11. Tombstones и retention

Удалённые provider entries получают tombstone с revision/time/reason. Они не физически удаляются, пока:

- source refresh не подтвердил отсутствие несколько раз;
- не истёк retention window;
- нет profile references;
- нет unresolved merge/split history;
- нет active recording/catch-up reference.

Начальный retention:

- provider channel: 30 дней;
- stream variant: 14 дней после последнего успеха или 30 дней после исчезновения;
- canonical channel с user data: бессрочно до явного удаления пользователем;
- EPG programmes: configurable rolling window.

## 12. Domain events

Минимальный набор:

```text
SourceRefreshStarted
SourceRevisionStaged
SourceRefreshCommitted
SourceRefreshRejected
CanonicalMergeProposed
CanonicalChannelsMerged
CanonicalChannelSplit
EpgBindingProposed
EpgBindingConfirmed
VariantHealthChanged
ProfileOverlayChanged
```

Event payload не содержит raw credentials или URL query secrets.

## 13. Инварианты транзакций

- staging revision не видна readers до commit;
- commit меняет active revision одним database transaction;
- overlay rows не удаляются provider cascade;
- canonical membership и variant membership согласованы;
- один variant принадлежит одному provider channel;
- manual EPG binding не может ссылаться на удалённый EPG source без состояния `Unresolved`;
- все destructive mutations имеют preview и inverse record до compaction.

## 14. Критерии приёмки

- переименование канала провайдером не уничтожает favorite/order/history;
- изменение tokenized URL не создаёт новый канал;
- исчезнувший на один refresh канал не пропадает из пользовательского каталога;
- merge/split обратимы;
- profile overlays изолированы;
- любой displayed field объясним через provenance;
- source refresh может быть отклонён без частичного изменения active catalog.