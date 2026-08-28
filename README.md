# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и EPG. Приложение не предоставляет телеканалы и не продаёт подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**. Текущий принятый контур включает:

- защищённое добавление URL-источника и Keystore-backed credentials;
- bounded streaming M3U/XMLTV ingestion с immutable revisions и previous-good preservation;
- Room v10 как Android-first source of truth;
- Channels с Room-backed Paging, Favorites, Recent и Now/Next;
- bounded Unicode Search top-N с явным признаком truncation;
- bounded Guide TV;
- один service-owned Media3 player с ограниченным same-channel recovery и единым service-owned seek authority;
- redacted Doctor Lite;
- Lounge Light TV shell с evidence-proven transient rail geometry и deterministic D-pad/focus restoration;
- GitHub-hosted Windows/Linux CI, включая canonical Android TV API26/API36 emulator gates;
- принятый M0 measurement-correctness boundary для published Search result sets; 50k timing остаётся manual stress evidence;
- measurement, JMH и Macrobenchmark/Baseline Profile foundation.

Текущий reviewed baseline — `main@76816014180b30872cd0517b1d2f692d1850ae0f` после принятого M0/#178. Stabilization execution описан в [Post-U1 Stabilization and M0 plan](docs/superpowers/plans/2026-08-28-post-u1-stabilization-execution.md). Точная принятая ревизия хранится в [.work/CURRENT-STATE.md](.work/CURRENT-STATE.md) и [.work/meta/status.yaml](.work/meta/status.yaml); live `HEAD`, PR и Issue всегда сверяются с Git/GitHub во время выполнения.

## Архитектурные принципы

- TV-first: полноценный D-pad/remote flow, стабильные ключи и deterministic Back/focus restoration.
- Local-first/privacy-first: locators, headers и credentials не попадают в Navigation, public Room projections, logs, traces или export.
- Source и EPG используют bounded parsing, immutable revisions, staging и atomic activation.
- Один MediaSessionService владеет ExoPlayer, recovery generation, semantic seek mutation и playback lifecycle.
- Features зависят от стабильных repository/player contracts, а не от DAO, raw network clients или Media3 implementation; подтверждённые boundary leaks исправляются отдельными архитектурными PR до provider expansion.
- Search остаётся bounded top-N; Paging применяется к просматриваемому каталогу Channels, но не заменяет Search ranking/truncation contract.
- M0/#178 принят как correctness gate. Performance/DB/buffer/cache изменения по-прежнему принимаются только по owner issue и воспроизводимому before/after evidence; hosted emulator correctness не является absolute performance claim.
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

PR/release device-проверки выполняются GitHub-hosted workflows на ephemeral Android TV emulators с ровно двумя canonical identities: `MuxTV_TV_OLD_API26` и `MuxTV_TV_CURRENT_API36`. 720p/1080p/density profiles переиспользуют эти устройства и не создают отдельные AVD. Эмуляторная матрица доказывает Android contracts, но не заменяет physical-device performance/codec/HDR/audio/release evidence.

## Документация

- [.work/ARCHITECTURE.md](.work/ARCHITECTURE.md) — нормативная архитектура v2.
- [.work/ROADMAP.md](.work/ROADMAP.md) — продуктовые фазы и текущий checkpoint.
- [.work/CURRENT-STATE.md](.work/CURRENT-STATE.md) — durable reviewed implementation snapshot.
- [.work/meta](.work/meta) — машинно-читаемые contracts и индексы.
- [Post-U1 Stabilization and M0 plan](docs/superpowers/plans/2026-08-28-post-u1-stabilization-execution.md) — исполненная U1/M0 последовательность и текущий architecture/provider train.
- [Historical MVP alpha execution journal](docs/superpowers/plans/2026-08-08-mvp-alpha-1-execution.md) — предыдущий execution journal; сохраняется как историческое evidence и не заменяет live Git/GitHub state.

## Лицензия

BSD 3-Clause. См. [LICENSE](LICENSE).
