---
status: accepted
last_reviewed: 2026-08-11
---

# Карта модулей

## Фактическая структура

~~~text
app/
└── tv/                    Android TV application и composition root

core/
├── common/                Общие contracts, errors и IDs
├── model/                 Platform-neutral domain models
├── database/              Room v10, migrations, catalog/EPG/Search/Recent/Guide projections
├── designsystem/          TV theme, tokens и focus surfaces
├── ui/                    Shared Compose TV primitives
├── testing/               Shared test support
├── network/               Bounded OkHttp policies и redaction
└── credentials/           Android Keystore credential store

catalog/
├── api/                   Catalog, browse, search, recent и playback ports
├── ingest/                Streaming M3U parser
├── importer/              Source/EPG staging и import
├── refresh/               Secure remote acquisition
├── sync/                  WorkManager scheduling и leases
└── onboarding/            Durable source preparation registry

player/
├── api/                   Playback identity, recovery policy и observations
├── media3/                Единственный production Media3/service adapter
└── fake/                  Deterministic test adapter

feature/
├── home/
├── channels/              Room-backed paged browser
├── guide/                 Bounded Guide TV
├── search/                Bounded top-N Search TV
├── player/
├── sources/
└── doctor/                Redacted Doctor Lite

benchmark/
├── macrobenchmark/        CUJs и Baseline Profile producer
└── jvm/                   JMH measurements

build-logic/               Included build с convention plugins
~~~

В settings.gradle.kts включены ровно 27 application/library/benchmark модулей. build-logic является отдельным included build и не считается двадцать восьмым project path.

## Границы

- app:tv содержит composition root и route wiring, но не бизнес-логику ingestion/playback.
- Pure Kotlin core/catalog/player contracts не импортируют Android, Room, Media3 или Compose.
- core:database реализует repository ports; feature-модули не используют DAO напрямую.
- player:media3 — единственный production owner Media3/ExoPlayer.
- Channels использует Paging для последовательного просмотра больших каталогов.
- Search сохраняет ranked bounded top-N/truncation contract и не превращается в бесконечный browse feed.
- Guide, Search и Doctor являются существующими feature-модулями, а не planned structure.

## Ещё не создано

Отдельные feature:profiles, feature:settings и local-control modules появятся только при активном release/product scope. Новые epg/search/doctor layers не создаются для симметрии: текущие repository boundaries уже покрывают принятый продукт.

Машинно-читаемая карта: [.work/meta/modules.yaml](../meta/modules.yaml).
