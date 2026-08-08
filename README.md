# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `5bb6ee1f754785b2b236d6dcb52fd4458780e758`: exact source-head CI/evidence provenance fix PR #137 поверх принятого Guide TV baseline `286ece017445b811a7adddd4ba7e85cacc5dd3ea`.

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
20. bare source host normalization to HTTPS;
21. exact source-head CI/evidence provenance contract для host/device/measurement PR workflows.

### Последние принятые этапы

- PR #137 — exact PR source-head evidence provenance (#136);
- PR #131 — завершённый Guide TV route/UI (#29);
- PR #129 — bare-host source normalization to HTTPS;
- PR #128 — bounded Guide channel/programme data window;
- PR #127 — explicit playback transport classification;
- PR #124 — centralized Room migration и generated schema guard;
- PR #123 — cross-surface active/profile-visible channel truth.

PR #137 source head `02d6ee4b2641e12d88ace83bcd6af510f18bac08` перед merge прошёл:

- Self-hosted Full — success;
- Android TV Product old-edge/current matrix — success;
- Database old-edge/current matrix — success;
- Measurement variance smoke — success;
- unresolved review threads — 0.

Исторические pre-#136 PR runs по-прежнему считаются integration acceptance, но не strict exact-source-head evidence: именно #137 сделал совпадение `git HEAD == SourceCommit` исполняемым контрактом.

## Что ещё не завершено

Текущий critical path:

1. **PR #135** — завершить repository truth sync на новом exact-evidence baseline;
2. **issue #112 / PR #133** и **issue #27 / PR #134** — restack на `5bb6ee1...` и повторить required exact-source gates;
3. **issue #27** — после принятия #134 выполнить deterministic 5×10k + 5×50k measurement series и проверить variance/provenance;
4. **issue #30** — bounded same-channel variant fallback + typed recovery/diagnostics + TV Doctor Lite; Media3 loader retries и MuxTV variant switching обязаны укладываться в один total recovery budget;
5. **issues #33/#93** — Lounge Light TV-first polish поверх реальных Channels/Search/Recent/Guide/Player/Doctor contracts;
6. **issue #31** — R8, Baseline/Startup Profiles, endurance, signing, SBOM/release checklist и physical-device evidence.

Параллельные hardening/evidence packages:

- **issue #111 / PR #138** — D1 immediate dense focus уже прошёл наблюдённый RED и minimal GREEN находится в exact-source validation; далее native OK/long-press/repeat, state/reduced-motion и 720p/1080p reachability;
- **issue #118** — прямой отказ от refresh до user unlock и идемпотентная WorkManager-инициализация;
- **issue #113** — portable backup/restore envelope с integrity digest до secrets-модели;
- **issue #101** — разделение Product/Database connected suites внутри существующего AVD harness, только с before/after runner evidence;
- **issue #100** — conditional M3U `ETag`/`Last-Modified` и корректный `304 Not Modified`, когда свободен следующий Room schema owner;
- **Room3 3.0.1** — отдельный dependency-only hardening PR без изменения MuxTV Room schema version;
- **issues #39/#40** — user guide/recovery и pre-release/app-store checklist;
- **issues #109/#117/#132** — buffer/FFmpeg/seek-cache decisions только после реального corpus/physical-device evidence и без второго player owner.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR. Issue #30 прямо исключает alternate playback engine.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- sensitive source/playback metadata не попадает в Navigation, public Room projections, logs, traces, screenshots или Recent history;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession` в `MediaSessionService`;
- accepted playback success означает service-owned first rendered frame, а не `READY`/`isPlaying`;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- derived Search index и Recent history не являются catalog source of truth;
- profile-facing projections обязаны повторно применять active revision + selected-profile visibility;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не заменяет physical-device validation;
- evidence-producing CI обязан исполнять тот commit, который записывает как `SourceCommit`;
- Media3 internal loader retry и MuxTV same-channel variant recovery — разные уровни, но входят в один bounded user-visible recovery deadline;
- D-pad focus не должен ставить в очередь scale/translation motion.

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

PR evidence workflows явно checkout'ят source-head SHA и до запуска evidence проверяют совпадение `git rev-parse HEAD` с заявленным `SourceCommit`.

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
- active implementation plans: [`docs/superpowers/plans/`](docs/superpowers/plans/), включая `2026-08-08-summer-2026-execution-addendum.md`.

## Лицензия

BSD 3-Clause.
