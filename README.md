# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` на момент этой синхронизации — `8fadb411e20c6a854fafd2005c5c5b17e868f858`: provider-readiness pure API contract PR #133 поверх exact-evidence CI, Guide и D1 TV-focus baseline.

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room3, WorkManager, OkHttp и Media3. Room schema — **v10**. `minSdk = 26`.

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
21. exact source-head CI/evidence provenance contract для host/device/measurement PR workflows;
22. immediate dense custom D-pad focus без queued scale animation, с real-key API26/API36 acceptance;
23. provider-neutral readiness contract: active live catalog делает provider `USABLE`, secondary enrichment не откатывает previous-good live/EPG state.

### Последние принятые этапы

- PR #133 — provider-neutral readiness API contract (#112 umbrella remains open);
- PR #138 — immediate dense-TV custom focus D1 (#111 remains open for D2–D4);
- PR #135 — post-Guide/post-provenance repository truth and execution plan;
- PR #137 — exact PR source-head evidence provenance (#136);
- PR #131 — завершённый Guide TV route/UI (#29);
- PR #129 — bare-host source normalization to HTTPS;
- PR #128 — bounded Guide channel/programme data window;
- PR #127 — explicit playback transport classification;
- PR #124 — centralized Room migration и generated schema guard;
- PR #123 — cross-surface active/profile-visible channel truth.

PR #137 сделал совпадение `git HEAD == SourceCommit` исполняемым контрактом для evidence-producing PR workflows. Исторические pre-#136 PR runs считаются integration acceptance, но не strict exact-source-head evidence.

PR #138 source head перед merge прошёл fresh Self-hosted Full и Android TV Product API26/API36 matrix. D1 не является глобальным запретом platform focus scale: правило stable geometry применяется к dense custom surfaces, где scale/queued motion нарушает пространственную стабильность.

PR #133 source head `13ac65c77e8b33538bdd28bf7d16bac8c8b0eda3` прошёл Product DeviceMatrix `31245990038`; внутри `Invoke-TvDeviceValidation.ps1` выполняется Full host acceptance до API26/API36 AVD. Issue #112 остаётся integration umbrella для будущего provider-neutral orchestration layer.

## Что ещё не завершено

Текущий critical path:

1. **issue #27 / PR #134** — завершить manual exact-head provenance RED→GREEN для focused M3U series;
2. **issue #140** — после #134 добавить accepted-main focused evidence lane и выполнить sequential 5× `medium-10k` + 5× `large-50k`;
3. **issue #27** — проверить corpus identity, analyzer provenance и variance; не вводить threshold без устойчивой repeated evidence;
4. **issue #139** — fail-closed reject staged/unstaged tracked changes для claim-eligible manual evidence;
5. **issue #30A** — pure bounded same-channel recovery policy без Media3/Room/UI и без hidden product defaults;
6. **issue #30B** — process-owned Media3 recovery runtime, где loader retries + candidate switching входят в один total user-visible deadline;
7. **issue #30C/#30D** — typed redacted diagnostics при доказанной необходимости persistence + TV Doctor Lite;
8. **issues #33/#93** — Lounge Light TV-first polish поверх стабильных interaction/recovery contracts;
9. **issue #31** — R8, Baseline/Startup Profiles, endurance, signing, SBOM/release checklist и physical-device evidence.

Параллельные hardening/evidence packages:

- **issue #111** — D2 native OK/long-press/repeat + representative Compose Test JUnit4 v2 migration; D3 independent focus/selected/playing/disabled + reduced motion; D4 720p/1080p reachability и long Russian labels;
- **issue #118** — прямой отказ от refresh до user unlock и идемпотентная WorkManager-инициализация;
- **issue #113** — portable backup/restore envelope с integrity digest до secrets-модели;
- **issue #101** — убрать доказанное дублирование Product/Database/standalone host work только с before/after runner wall-time evidence;
- **issue #100** — conditional M3U `ETag`/`Last-Modified` и корректный `304 Not Modified`, когда свободен следующий Room schema owner;
- **issues #39/#40** — user guide/recovery и pre-release/app-store checklist;
- **issues #109/#117/#132** — buffer/FFmpeg/seek-cache decisions только после реального corpus/physical-device evidence и без второго player owner.

### Dependency note

На 2026-08-08 официальный Android Developers Room3 release page по-прежнему указывает **Room3 3.0.0** как stable. Ранее записанный план `3.0.0 → 3.0.1` отменён как неподтверждённый; отдельный Room3 patch upgrade не выполняется, пока такой релиз не появится в официальном источнике.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible residual bottleneck/compatibility evidence и отдельного ADR. Issue #30 прямо исключает alternate playback engine.

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
- claim-eligible manual evidence также должно отклонять staged/unstaged tracked source drift;
- Media3 internal loader retry и MuxTV same-channel variant recovery — разные уровни, но входят в один bounded user-visible recovery deadline;
- temporary fallback не переписывает preferred variant;
- dense custom D-pad focus не должен ставить в очередь scale/translation motion;
- стандартное Android TV focus scale поведение не заменяется глобально без evidence.

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

После #134 этот entrypoint обязан сам fail-closed проверить exact Git HEAD до создания claim evidence. Issue #140 добавит repository-owned accepted-main lane для последовательных 5×10k + 5×50k runs.

## Документация

- repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- long-lived roadmap: [`.work/ROADMAP.md`](.work/ROADMAP.md);
- architecture: [`.work/ARCHITECTURE.md`](.work/ARCHITECTURE.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- active implementation plans: [`docs/superpowers/plans/`](docs/superpowers/plans/), включая `2026-08-08-summer-2026-execution-addendum.md`.

## Лицензия

BSD 3-Clause.
