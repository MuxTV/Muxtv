---
status: accepted
last_reviewed: 2026-08-11
---

# Карта модулей

## Фактическая структура

~~~text
app/
├── tv/                    Android TV application и composition root
└── phone/                 Offline preview-only QR scanner; NO network/credentials

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

local-control/
├── core/                  Ephemeral TV-confirmed pairing authority (JVM, no socket)
├── server/                User-initiated LAN pairing/QR bootstrap; no provider APIs
├── onboarding/            Dormant SourceOnboarding adapter; protected transport NOT implemented
├── crypto-lab/            Isolated Tink RFC9180 HPKE experiments; no runtime caller
└── crypto-android-compat/ Hosted API26/API36 instrumentation only; no production caller

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
├── doctor/                Redacted Doctor Lite
└── settings/              TV settings workspace

benchmark/
├── macrobenchmark/        CUJs и Baseline Profile producer
├── jvm/                   JMH measurements
└── competitive/           Competitive benchmark evidence contracts

build-logic/               Included build с convention plugins
~~~

Актуальный набор application/library/benchmark модулей задаётся файлом `settings.gradle.kts`, включая `:local-control:core` и `:local-control:server`. build-logic является отдельным included build. Модули LC02 пока не подключены к TV Activity. Количество относится к данному изменению; фактический состав проверяет `.work/meta/modules.yaml`.

## Границы

- app:tv содержит composition root и route wiring, но не бизнес-логику ingestion/playback.
- Pure Kotlin core/catalog/player contracts не импортируют Android, Room, Media3 или Compose.
- core:database реализует repository ports; feature-модули не используют DAO напрямую.
- player:media3 — единственный production owner Media3/ExoPlayer.
- Channels использует Paging для последовательного просмотра больших каталогов.
- Search сохраняет ranked bounded top-N/truncation contract и не превращается в бесконечный browse feed.
- Guide, Search и Doctor являются существующими feature-модулями, а не planned structure.

## Ещё не создано

Не реализованы `:feature:profiles` и управление IPTV-источниками через телефон. LC03 добавляет TV QR UI, Activity-owned запуск LAN-сервера и минимальную телефонную страницу сопряжения; `:local-control:server` остаётся выключенным до явного действия пользователя. Только LC04 сможет подключать SourceOnboarding после проверки защищённого транспорта. Новые epg/search/doctor layers не создаются для симметрии.

Машинно-читаемая карта: [.work/meta/modules.yaml](../meta/modules.yaml).
