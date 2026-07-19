# `.work` — рабочая система MuxTV

Эта папка является единым источником проектной документации, архитектурных решений, планов и метаданных. Документы вне `.work` не должны дублировать детальные требования; корневой `README.md` остаётся краткой публичной витриной.

## Навигация

- [`PROJECT.md`](PROJECT.md) — миссия, принципы, границы и терминология.
- [`PRODUCT.md`](PRODUCT.md) — пользователи, проблемы, сценарии и продуктовые требования.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — целевая архитектура и правила зависимостей.
- [`ROADMAP.md`](ROADMAP.md) — последовательность реализации.
- [`CURRENT-STATE.md`](CURRENT-STATE.md) — фактическое состояние репозитория.
- [`architecture/`](architecture/) — domain model, source refresh, модули, данные, playback runtime и расширения.
- [`specifications/`](specifications/) — нормативные функциональные и протокольные требования: profiles, M3U, XMLTV, playback errors, Smart Channels, TV Doctor и local control.
- [`design/`](design/) — TV design system, D-pad/focus и accessibility.
- [`security/`](security/) — threat model и network/source policy.
- [`release/`](release/) — signing, GitHub Releases и self-update trust.
- [`platforms/`](platforms/) — особенности Android TV/Google TV/Fire TV; сейчас детализирован Fire TV.
- [`adr/`](adr/) — Architecture Decision Records.
- [`quality/`](quality/) — quality gates, benchmark methodology, тестирование и бюджеты производительности.
- [`research/`](research/) — официальные источники и критические обзоры reference repositories.
- [`plans/`](plans/) — исполнимые планы работ.
- [`meta/`](meta/) — машинно-читаемое состояние, инварианты, версии, scoring и error catalogs.

## Нормативные документы по критическим подсистемам

| Область | Документы |
|---|---|
| Профили | `specifications/profiles.md`, `adr/0004-profile-and-installation-scope.md`, `meta/profiles.yaml` |
| Каталог | `architecture/domain-model.md`, `architecture/source-refresh.md` |
| M3U/XMLTV | `specifications/m3u-ingestion.md`, `specifications/xmltv-processing.md` |
| Playback | `architecture/playback-runtime.md`, `specifications/playback-errors.md`, `meta/playback-error-catalog.yaml` |
| Smart Channels/Doctor | `specifications/smart-channels.md`, `specifications/tv-doctor.md`, `meta/scoring-model.yaml` |
| UI | `design/focus-navigation.md`, `design/design-system.md` |
| Security | `security/threat-model.md`, `security/network-and-source-policy.md` |
| Phone setup | `specifications/local-control.md` |
| Release | `release/self-update-and-signing.md` |
| Performance | `quality/quality-gates.md`, `quality/benchmark-methodology.md` |
| External evidence | `research/official-sources.md`, `research/reference-repositories.md` |

## Правила ведения

1. Любое крупное архитектурное решение оформляется ADR до реализации.
2. `CURRENT-STATE.md` и `meta/status.yaml` отражают только факты, а не намерения.
3. Версии зависимостей изменяются в `meta/dependencies.yaml` и затем в version catalog.
4. Незавершённые идеи не добавляются в обязательный scope; они фиксируются как deferred.
5. Документ считается актуальным, если его `last_reviewed` не старше 90 дней или он проверен в текущем milestone.
6. Любое изменение архитектурных границ обновляет `ARCHITECTURE.md`, `architecture/module-map.md` и `meta/modules.yaml`.
7. Reference repository никогда не считается blueprint: перед заимствованием проверяются official docs, код, tests, issues, license и применимость к MuxTV.
8. Машинно-читаемые meta-файлы не заменяют нормативный Markdown; они должны ссылаться на него и проходить consistency checks.
9. Любое автоматическое исправление каталога обязано быть объяснимым, previewable и обратимым.
10. Профильная модель не содержит предустановленных ролей: один Основной профиль и только пользовательские дополнительные профили.

## Статус документов

Допустимые значения: `draft`, `accepted`, `superseded`, `deprecated`. Принятые документы задают baseline до нового ADR или явной редакции. Планы реализации остаются `draft` до начала соответствующего этапа.