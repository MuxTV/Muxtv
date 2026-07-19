---
status: accepted
last_reviewed: 2026-07-19
---

# Карта модулей

## Начальная физическая структура

```text
app/
└── tv/                    Android TV application и composition root

core/
├── common/                Result, errors, dispatchers, clock, IDs
├── model/                 platform-neutral domain models
├── database/              Room 3 database, DAO, migrations
├── network/               OkHttp policies, auth, source downloads
├── designsystem/          TV tokens и reusable focus components
├── ui/                    generic TV UI primitives
└── testing/               fakes, fixtures, test DSL

catalog/
├── api/                   source/catalog ports
├── m3u/                   streaming M3U parser и adapter
├── normalization/         aliases, canonical names, categories
└── matching/              duplicate candidates и confidence

epg/
├── api/                   EPG ports и models
├── xmltv/                 streaming XMLTV parser
└── matching/              channel-to-EPG matching

player/
├── api/                   PlaybackEngine и diagnostics contracts
├── media3/                production Media3 adapter
└── fake/                  deterministic test player

feature/
├── onboarding/
├── home/
├── live/
├── guide/
├── search/
├── sources/
├── doctor/
├── profiles/
└── settings/

local-control/
├── server/                embedded Ktor server и pairing
└── web/                   static phone control UI

benchmark/
baseline-profile/
build-logic/
```

## Граф зависимостей

```text
app-tv
 ├─ feature-*
 ├─ player-media3
 ├─ catalog-*
 ├─ epg-*
 ├─ local-control-server
 └─ core-*

feature-* → application ports + core-model + core-designsystem
application ports → domain models
adapters → ports + platform libraries
```

Запрещённые зависимости:

- `core-model → android.*`;
- `feature-* → Room DAO`;
- `feature-* → ExoPlayer`;
- `catalog-* → Compose`;
- `player-media3 → provider implementation`;
- любые циклические module dependencies.

## Правила выделения нового модуля

Новый Gradle-модуль создаётся только если выполняется хотя бы одно условие:

1. требуется отдельный platform target;
2. компонент имеет независимый контракт и альтернативную реализацию;
3. необходим отдельный test/benchmark boundary;
4. зависимость тяжёлая и не должна транзитивно попадать в другие features;
5. компонент может собираться или выпускаться отдельно.

Пакеты внутри модуля предпочтительнее новых модулей, если граница не даёт измеримой пользы.