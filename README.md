# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `64b64c933da665d00ac403fd410a39309e773d64` (PR #92 Favorites).

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Room 3, WorkManager, OkHttp и Media3. Room schema на принятом `main` — **v8**. `minSdk = 26`.

Рабочий продуктовый контур:

1. secure source onboarding с Keystore-backed access isolation;
2. bounded streaming M3U ingest;
3. immutable source revisions, staging и atomic activation;
4. durable source refresh ownership;
5. bounded XMLTV + secure remote EPG refresh;
6. immutable EPG revisions;
7. deterministic EPG matching с policy-version provenance;
8. bounded Now/Next;
9. Channels с destination-scoped state;
10. process-owned Media3 Player;
11. Player → Back с stable canonical-channel focus restoration;
12. profile-scoped Favorites в Player и Channels.

### Принятые последние продуктовые этапы

- PR #80 — deterministic EPG matching + bounded Now/Next foundation;
- PR #84 — Room v8 matching-policy provenance и stale-policy repair;
- PR #90 — Channels Now/Next, scoped screen state, Media3 playback projection и stable focus;
- PR #92 — durable Favorites, All/Favorites filter, Player favorite action и TV D-pad/focus contracts.

Exact-head acceptance PR #92:

- Self-hosted validation `30873814952` — success;
- Android TV product DeviceMatrix `30873814955` — API26/API36 success, app instrumentation 18/18 на каждом профиле;
- database/device matrix `30873814953` — API26/API36 success, core database 93/93 на каждом профиле, `ChannelPreferencesRepositoryTest` 5/5.

## Что ещё не завершено

Текущий product critical path:

1. **issue #29 / Search Core** — Room v9 derived Unicode FTS4 (`unicode61`), bounded active-truth candidate search и current-programme enrichment;
2. **Search TV** — debounced + immediate-submit TV Search, D-pad/IME focus и Player → Back continuity;
3. **Recent** — отдельная profile-scoped durable playback history, записываемая только после подтверждённого успешного playback; ожидаемый следующий schema bump после Search — Room v10;
4. **Guide** — bounded/lazy channel × time viewport, без full-guide materialization;
5. **issue #30** — bounded playback fallback + typed failure families + TV Doctor Lite;
6. **issue #33** — финальная TV-first visual/interaction polish поверх реальных Search/Guide routes;
7. **issue #31** — R8, Baseline/Startup Profiles, Macrobenchmark, signing, SBOM/release checklist и physical-device evidence.

Параллельно остаётся **issue #27**: повторяемые performance datasets (`current-normal`, `old-edge-normal`, `current-low-ram`) и только затем per-operation hard-gate/warning/descriptive decisions.

Старые allocation PR #83/#87/#89 закрыты без merge. Их идеи разрешено clean-rebuild только после воспроизводимого same-corpus measurement evidence.

PR #88 закрыт как superseded; старую truth-sync историю не следует восстанавливать или ретаргетить.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- playlist/XML locators, query values, cookies, credentials, provider identities и sensitive headers не попадают в Navigation, public Room projections, logs, traces, screenshots или raw exception text;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- один in-process owner encrypted source access;
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

API26/API36 Android TV matrix:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -NoDaemon
```

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
- current execution plan: [`docs/superpowers/plans/2026-08-04-post-favorites-product-execution.md`](docs/superpowers/plans/2026-08-04-post-favorites-product-execution.md);
- Search comparative research remains attached to the active Search work until that code is accepted into `main`;
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md).

## Лицензия

BSD 3-Clause.
