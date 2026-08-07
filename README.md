# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `286ece017445b811a7adddd4ba7e85cacc5dd3ea` (PR #131: завершённый bounded Guide TV route поверх принятого Guide data window).

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3. Room schema на принятом `main` — **v10**. `minSdk = 26`.

Рабочий продуктовый контур:

1. secure source onboarding с Keystore-backed access isolation;
2. bounded streaming M3U ingest;
3. immutable source revisions, staging и atomic activation;
4. durable source refresh ownership;
5. bounded XMLTV + secure conditional remote EPG refresh;
6. immutable EPG revisions и deterministic EPG matching;
7. hardened plain/gzip/ZIP transport с bounded compressed/decoded limits;
8. bounded Now/Next;
9. Channels с destination-scoped state и profile-scoped Favorites;
10. bounded Unicode Search Core + Search TV;
11. process-owned Media3 Player;
12. Player/Search/Recent → Back со stable canonical-channel focus restoration;
13. service-owned first-rendered-frame success boundary;
14. profile-scoped bounded Recent, записываемый только после accepted first frame;
15. cross-surface active/current-revision + selected-profile-visible truth contract;
16. centralized Room migration chain and generated schema guard;
17. explicit HLS/MPEG-TS/DASH/progressive playback transport classification;
18. bounded Guide channel/programme data window без full-guide materialization;
19. bounded Guide TV viewport с deterministic D-pad/focus restoration и Guide → Player navigation;
20. bare source host normalization to HTTPS.

### Последние принятые продуктовые этапы

- PR #131 — завершённый Guide TV route/UI (#29);
- PR #129 — bare-host source normalization to HTTPS;
- PR #128 — bounded Guide channel/programme data window;
- PR #127 — explicit playback transport classification;
- PR #124 — centralized Room migration и generated schema guard;
- PR #123 — cross-surface active/profile-visible channel truth.

Связанные с PR #131 validation runs перед merge:

- Self-hosted validation `31210637363` — success;
- Android TV product device matrix `31210636241` — success;
- PR #131 не менял Room schema/migrations, player transport или CI topology.

**Provenance caveat:** аудит #136 обнаружил, что до исправления PR workflows использовали default `actions/checkout` merge ref, но evidence маркировался `pull_request.head.sha`. Поэтому исторические PR runs подтверждают успешную integration acceptance, но не должны называться строгим exact-source-head evidence. PR #137 вводит explicit source-head checkout и проверку `git rev-parse HEAD == SourceCommit`.

## Что ещё не завершено

Текущий critical path:

1. **issue #136 / PR #137** — исправить commit provenance self-hosted/device/measurement evidence и сделать source-head identity исполняемым контрактом;
2. **issue #27 / PR #134** — после принятия #136 повторить verified source-head checks, затем выполнить deterministic 10k/50k repeated measurement series и проверить variance/provenance;
3. **issue #30** — bounded same-channel variant fallback и TV Doctor Lite; issue #26 transport/reconnect dependency уже закрыта, поэтому после #136 именно #27 остаётся продуктовым evidence-gate;
4. **issues #33/#93** — Lounge Light TV-first polish поверх реальных Search/Recent/Guide routes;
5. **issue #31** — R8, Baseline/Startup Profiles, endurance, signing, SBOM/release checklist и physical-device evidence.

Параллельные hardening/evidence packages:

- **issue #112 / PR #133** — provider-neutral readiness contract для будущих native/provider-specific источников; финальное exact-source-head acceptance повторяется после #136;
- **issue #118** — прямой отказ от refresh до user unlock и идемпотентная WorkManager-инициализация;
- **issue #111** — TV remote контракты: long-press, dialog scrollability, focus контраст;
- **issue #113** — portable backup/restore envelope с integrity digest до secrets-модели;
- **issue #101** — разделение Product/Database connected suites внутри существующего AVD harness, только с before/after runner evidence;
- **issue #100** — conditional M3U `ETag`/`Last-Modified` и корректный `304 Not Modified`, когда свободен следующий Room schema owner;
- **issues #39/#40** — user guide/recovery и pre-release/app-store checklist;
- **issues #109/#117/#132** — buffer/FFmpeg/seek-cache decisions только после реального corpus/physical-device evidence и без второго player owner.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR. Issue #30 прямо исключает alternate playback engine.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- sensitive source/playback metadata не попадает в Navigation, public Room projections, logs, traces, screenshots или Recent history;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- accepted playback success означает service-owned first rendered frame, а не `READY`/`isPlaying`;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- derived Search index и Recent history не являются catalog source of truth;
- profile-facing projections обязаны повторно применять active revision + selected-profile visibility;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не заменяет physical-device validation;
- evidence-producing CI обязан исполнять тот commit, который записывает как `SourceCommit`.

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

`DeviceMatrix` сначала выполняет Full host acceptance, затем последовательно поднимает old-edge Android TV image (предпочтительно API26, с явным fallback только если image недоступен) и current API36 image, запускает connected suites, собирает evidence и завершает каждый AVD.

После #136 PR workflows также обязаны явно checkout'ить source-head SHA и до запуска evidence проверять совпадение `git rev-parse HEAD` с заявленным `SourceCommit`.

Deterministic M3U corpus:

```powershell
.\gradlew.bat :core:testing:generateM3uCorpus `
  -PcorpusProfile=small-1k `
  -PcorpusSeed=20260728 `
  -PcorpusSourceCommit=<full-lowercase-40-character-git-sha>
```

General measurement series:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-MeasurementSeries.ps1 `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -ProfileId current-normal `
  -Repetitions 5 `
  -NoDaemon
```

Focused M3U corpus series (PR #134 / issue #27):

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-M3uCorpusSeries.ps1 `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -M3uProfile medium-10k `
  -Repetitions 5 `
  -NoDaemon
```

## Документация

- repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- long-lived roadmap: [`.work/ROADMAP.md`](.work/ROADMAP.md);
- architecture: [`.work/ARCHITECTURE.md`](.work/ARCHITECTURE.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- active implementation plans: [`docs/superpowers/plans/`](docs/superpowers/plans/).

## Лицензия

BSD 3-Clause.
