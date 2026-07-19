---
status: accepted
last_reviewed: 2026-07-19
---

# Модель расширений

## Цели

Расширения должны позволять добавлять providers, EPG и metadata sources без превращения основного приложения в небезопасный runtime для произвольного кода.

## Контракты

```kotlin
interface ProviderAdapter
interface EpgProvider
interface PlaybackResolver
interface CatalogNormalizer
interface MetadataProvider
interface BackupCodec
```

Все контракты:

- версионируются независимо от внутренних классов;
- используют serializable DTO без Room/Android/Compose типов;
- поддерживают capability negotiation;
- возвращают typed errors;
- имеют conformance tests.

## Уровни расширения

### 1. Built-in adapter

Код находится в основном репозитории и проходит полный CI. Используется для M3U, XMLTV, Xtream и других ключевых protocol adapters.

### 2. Declarative extension

Подписанный manifest описывает:

- endpoint templates;
- authentication fields;
- HTTP headers;
- channel/category transforms;
- EPG alias maps;
- refresh policy.

Декларативное расширение не исполняет произвольный код и подходит для большинства provider-specific mappings.

### 3. Companion APK

Отдельный Android package взаимодействует через Binder/AIDL. MuxTV выдаёт capabilities, например:

- `source.read_metadata`;
- `source.resolve_stream`;
- `epg.provide`;
- `catalog.provide_logos`.

Companion APK не получает прямой доступ к database, credentials других providers, player instance или internal files.

## Security model

- package signature и extension ID сохраняются при первом trust decision;
- смена подписи блокирует extension до повторного подтверждения;
- sensitive capabilities требуют отдельного consent;
- network destinations декларируются manifest-ом;
- response size, execution time и request rate ограничиваются;
- crashes companion process не завершают MuxTV;
- extension output проходит ту же validation/normalization pipeline.

## API evolution

Версия контракта имеет `major.minor`:

- новый minor добавляет optional capabilities;
- новый major может менять DTO/semantics;
- приложение поддерживает текущий и предыдущий major в течение одного стабильного release cycle;
- experimental API публикуется только с явным namespace и не обещает совместимость.

## Запрещённые механизмы

- загрузка DEX/JAR из сети;
- выполнение JavaScript из плейлиста;
- JNI-библиотеки стороннего расширения в процессе приложения;
- reflection-доступ к internal packages;
- shared database file;
- хранение extension secrets в plain text.