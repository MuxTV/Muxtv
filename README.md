# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и EPG. Приложение не предоставляет телеканалы и не продаёт подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Текущий принятый контур включает:

- защищённое добавление URL-источника и Keystore-backed credentials;
- bounded streaming M3U/XMLTV ingestion с immutable revisions и previous-good preservation;
- Room v10 как Android-first source of truth;
- Channels с Room-backed Paging, Favorites, Recent и Now/Next;
- bounded Unicode Search top-N с явным признаком truncation;
- bounded Guide TV;
- один service-owned Media3 player с ограниченным same-channel recovery;
- redacted Doctor Lite;
- deterministic D-pad/focus restoration;
- self-hosted Fast/Full и API26/API36 device gates;
- measurement, JMH и Macrobenchmark foundation.

Текущая работа и exact-head evidence ведутся только в [canonical ExecPlan](docs/superpowers/plans/2026-08-08-mvp-alpha-1-execution.md). Точная принятая ревизия хранится в [.work/CURRENT-STATE.md](.work/CURRENT-STATE.md) и [.work/meta/status.yaml](.work/meta/status.yaml), а не дублируется в README.

## Архитектурные принципы

- TV-first: полноценный D-pad/remote flow, стабильные ключи и deterministic Back/focus restoration.
- Local-first/privacy-first: locators, headers и credentials не попадают в Navigation, public Room projections, logs, traces или export.
- Source и EPG используют bounded parsing, immutable revisions, staging и atomic activation.
- Один MediaSessionService владеет ExoPlayer, recovery generation и playback lifecycle.
- Features зависят от repository contracts, а не от DAO, raw network clients или Media3 implementation.
- Search остаётся bounded top-N; Paging применяется к просматриваемому каталогу Channels, но не заменяет Search ranking/truncation contract.
- Performance-изменения принимаются только после воспроизводимого before/after evidence.
- Kotlin/Room/Media3 остаются основным путём; Rust, bundled SQLite, libmpv и второй player требуют отдельного evidence-backed ADR.

## Сборка и проверка

Debug APK:

~~~powershell
.\gradlew.bat :app:tv:assembleDebug
~~~

Fast:

~~~powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
~~~

Full:

~~~powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
~~~

Device-проверки выполняются существующими repository-owned API26/API36 harness и workflows. Эмуляторная матрица доказывает Android contracts, но не заменяет physical-device performance/release evidence.

## Документация

- [.work/ARCHITECTURE.md](.work/ARCHITECTURE.md) — нормативная архитектура v2.
- [.work/ROADMAP.md](.work/ROADMAP.md) — продуктовые фазы и текущий checkpoint.
- [.work/CURRENT-STATE.md](.work/CURRENT-STATE.md) — принятая implementation truth.
- [.work/meta](.work/meta) — машинно-читаемые contracts и индексы.
- [Canonical ExecPlan](docs/superpowers/plans/2026-08-08-mvp-alpha-1-execution.md) — единственный изменяемый execution journal.

## Лицензия

BSD 3-Clause. См. [LICENSE](LICENSE).
