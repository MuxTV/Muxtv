# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `8fced4dc282eaf07e8160f463c8276d7e48ba01b` (PR #106 first-rendered-frame success signal).

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3. Room schema на принятом `main` — **v9**. `minSdk = 26`.

Рабочий продуктовый контур:

1. secure source onboarding с Keystore-backed access isolation;
2. bounded streaming M3U ingest;
3. immutable source revisions, staging и atomic activation;
4. durable source refresh ownership;
5. bounded XMLTV + secure remote EPG refresh;
6. immutable EPG revisions и deterministic EPG matching;
7. bounded Now/Next;
8. Channels с destination-scoped state и profile-scoped Favorites;
9. bounded Unicode Search Core + Search TV;
10. process-owned Media3 Player;
11. Player/Search → Back со stable canonical-channel focus restoration;
12. service-owned first-rendered-frame success boundary с exact profile/channel identity и direct recorder fan-out.

### Последние принятые продуктовые этапы

- PR #92 — durable Favorites, All/Favorites filter, Player favorite action и TV D-pad/focus contracts;
- PR #104 — Room v9 bounded Unicode Search Core + Search TV;
- PR #105 — truth-sync после принятия Search TV;
- PR #106 — service-owned first-rendered-frame success boundary для durable Recent.

Exact-head acceptance PR #106 (`6bc33d8b61d0f687d52cdf6f65ca216035ef369d`):

- Self-hosted Full validation `30946905694` — success;
- Android TV product matrix `30946905920` — old-edge/current success;
- merge commit — `8fced4dc282eaf07e8160f463c8276d7e48ba01b`.

## Что ещё не завершено

Текущий product critical path:

1. **PR #107 / Recent / Room v10** — profile-scoped bounded successful-playback history, записываемая только после accepted first rendered frame; Channels `Недавние`; migration 9→10; exact generated Room schema и old-edge/current device acceptance до merge;
2. **issue #114** — единый active/current-revision + selected-profile-visible truth contract для rows/counts/pagination/Playback/Search/Recent/Guide;
3. **Guide** — bounded/lazy channel × time viewport без full-guide materialization;
4. **issue #30** — bounded playback fallback + typed failure families + TV Doctor Lite;
5. **issues #33/#93** — TV-first visual/interaction polish поверх реальных Search/Recent/Guide routes;
6. **issue #31** — R8, Baseline/Startup Profiles, Macrobenchmark, signing, SBOM/release checklist и physical-device evidence.

Параллельно:

- **PR #119 / issue #110** hardens compressed XMLTV transport without replacing the accepted streaming decoder/parser or immutable EPG revision model;
- **issue #27** owns repeated performance datasets (`current-normal`, `old-edge-normal`, `current-low-ram`) and evidence-driven thresholds;
- **issue #101** owns optional connected-suite split inside the existing self-hosted AVD harness;
- **issue #100** owns conditional M3U validators/`304 Not Modified` only after the next Room schema owner is free.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- sensitive source/playback metadata не попадает в Navigation, public Room projections, logs, traces или screenshots;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- derived Search index никогда не является source of truth;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не заменяет physical-device validation.

## Сборка и проверка

Debug APK:

```powershell
.\gradlew.bat :app:tv:assembleDebug
```

Полная локальная проверка:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Old-edge/current Android TV matrix:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -NoDaemon
```

`DeviceMatrix` автоматически запускает Full host gate, затем последовательно поднимает old-edge Android TV image (предпочтительно API26, с явным fallback только если image недоступен) и current API36 image, собирает evidence и останавливает каждый AVD.

Deterministic M3U corpus:

```powershell
.\gradlew.bat :core:testing:generateM3uCorpus `
  -PcorpusProfile=small-1k `
  -PcorpusSeed=20260728 `
  -PcorpusSourceCommit=<full-lowercase-40-character-git-sha>
```

Measurement series:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-MeasurementSeries.ps1 `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -ProfileId current-normal `
  -Repetitions 5 `
  -NoDaemon
```

## Документация

- repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- long-lived roadmap: [`.work/ROADMAP.md`](.work/ROADMAP.md);
- architecture: [`.work/ARCHITECTURE.md`](.work/ARCHITECTURE.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md).

Active implementation plans live under `docs/superpowers/plans/` and must not override accepted `main` truth until their PRs pass final exact-head acceptance.

## Лицензия

BSD 3-Clause.
