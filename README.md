# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое объединяет пользовательские IPTV-источники в единый локальный каталог каналов и EPG. Код лицензирован по BSD 3-Clause; текущий private-репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Текущий принятый implementation foundation в `main` проходит через PR #84, squash merge `9325e0b4b124402a8eb5b1731442bce40a5404a8`.

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Room 3, WorkManager, OkHttp и Media3. Принятая Room schema — **v8**. `minSdk = 26`.

### Рабочий путь

1. пользователь добавляет HTTPS M3U либо отдельно подтверждает HTTP-источник;
2. source access хранится через Android Keystore-backed credential boundary вне public Room projections;
3. M3U потоково импортируется в staging и атомарно активирует immutable source revision;
4. Channels читает canonical catalog и запускает канал;
5. HTTP playback требует exact-origin approval;
6. Player повторно разрешает active variant и устанавливает request в process-owned MediaSession/ExoPlayer;
7. EPG проходит secure remote refresh → immutable revision → deterministic versioned matching → bounded Now/Next;
8. Player → Back восстанавливает stable channel focus.

## Принятый correctness foundation

- PR #63 — bounded streaming XMLTV parser без DOM, с independent limits, XXE/DTD protection и typed timestamp handling;
- PR #64 — immutable EPG Room foundation, monotonic atomic activation, previous-good retention и bounded queries;
- PR #68 — bounded magic-first plain/gzip/ZIP EPG payload decoder;
- PR #72 — secure conditional remote EPG refresh, корректный `304`, bounded decode/import и cancellation preserving previous-good guide;
- PR #74/#75 — durable EPG policies/state/attempts/validators, DB lease, stale reclaim, WorkManager orchestration и access/run-token publication ownership;
- PR #78 — source refresh publication ownership hardened по captured-binding/run-token/cancellation/redaction guarantees;
- PR #80 — Room v7 deterministic explainable channel matching, persisted producer-revision provenance и bounded Now/Next;
- PR #84 — Room v8 matching-policy provenance, stale-aware derived-match repair и current-policy Guide filtering.

Accepted PR #84 evidence:

- Full `30783348416` — success;
- API26/API36 database/device matrix `30783348361` — success;
- 118 instrumentation tests на каждом TV-профиле, 0 failures/errors/skips; `core:database` 88/88.

## Активный продуктовый граф

### PR #90 — Channels Now/Next + scoped state

Чистая Room-v8 пересборка прежнего #81. Текущий head: `0675015e7ba8c588c62be5f40927cbd466fc2338`.

Реализовано:

- destination/back-stack-scoped `ChannelsViewModel` и immutable `StateFlow<ChannelsUiState>`;
- bounded `PlaybackCatalog` + `EpgGuideRepository.getNowNext`;
- programme-boundary reload и stale-generation rejection;
- Media3-backed engine-neutral playback-session projection;
- Navigation 3 saveable/ViewModel-store ownership;
- dedicated TV channel rows;
- stable Player → Back focus restoration с nearest-previous fallback.

Exact-head Full `30785039850` — **success**. В его artifact есть build/unit/lint/instrumentation-compile evidence, но нет фактического API26/API36 TV runtime journey. Поэтому перед merge остаётся product DeviceMatrix для Channels focus → Player → Back и MediaSession/playback-session behavior.

Старый PR #81 закрыт как superseded.

### PR #91 — Favorites

Чистый Favorites slice поверх #90, текущий head `3826443ec6bbf6ca2bd1bead8f2947378961f0bd`: **4 commits / 16 files**, без schema bump и без EPG migration changes.

Реализовано:

- dedicated `ChannelPreferencesRepository`;
- transactional favorite mutation через существующий `user_channel_overlays.isFavorite`;
- `Applied | Unchanged | NotFound`;
- Player favorite action;
- `Все каналы / Избранное` с Room-side filtering;
- empty-state recovery и filter-aware focus restoration;
- исправлен historical #86 navigation compile regression.

После принятия #90 Favorites нужно clean-rebuild на новом `main`, чтобы финальный PR не зависел от stacked ancestry, затем пройти exact-head Full + product DeviceMatrix. Старый PR #86 закрыт как superseded.

## Performance / issue #27

### PR #89 — EPG matching/Guide allocation Stage 2

Чистая Room-v8 пересборка прежнего #85: **2 commits / 2 runtime files**. Exact-head evidence уже green:

- Full `30784628497` — success;
- API26/API36 database/device matrix `30784628471` — success.

PR остаётся draft: performance claim и merge требуют comparable before/after allocation evidence на одном corpus/profile/environment.

### PR #83 — Core allocation Stage 1

Реализованы reusable M3U buffers/decoder, reusable SHA-256 state, playback-header fast paths, direct XMLTV timestamp scanner и Android microbenchmark module. Benchmark pin обновлён с 1.4.1 до `1.5.0-alpha07` после AGP 9.3 `TestedExtension` incompatibility. На current head `9ac00e9a3f24cadffa24ea1d125a2080c3527972` свежего PR workflow evidence пока нет; также нужны clean rebuild/retarget и comparable A/B measurements.

### PR #87 — XMLTV allocation Stage 2

Clean one-commit/one-file slice: reuse normalized captured text и lazy reusable guarded `skip()` buffer. На current head `e617bb9c4198758aa7873a802c7b98bc089a627b` свежего PR workflow evidence пока нет; correctness + allocation evidence обязательны до merge/performance claim.

### Repeated measurement work

Остаётся получить и зафиксировать:

1. 5× `current-normal`;
2. 5× `old-edge-normal`;
3. 5× `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` classification;
6. durable performance report.

## Следующий продуктовый порядок

После принятия #90 и clean Favorites:

1. **Search** — bounded/debounced query boundary по effective channel name/number/group и active programme metadata; без full-catalog filtering в Compose и без преждевременного FTS;
2. **Recent** — profile-scoped bounded durable history, запись только после confirmed successful playback;
3. **Guide** — bounded/lazy viewport по channel IDs + time window + explicit limits;
4. непрерывность D-pad focus/list position/Player Back across filters/routes/restored state;
5. issue #30 — bounded variant fallback + TV Doctor Lite;
6. issue #31 — release hardening и physical-device alpha evidence.

Подробный текущий execution checkpoint: [`docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md`](docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md).

## Native/Rust decision gate

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими correctness dependencies.** Они рассматриваются только после reproducible bottleneck/compatibility evidence из #27/#31 и отдельного ADR, оправдывающего FFI/ABI/packaging/debugging cost измеримым выигрышем.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- locators, query values, cookies, credentials, provider identities и sensitive headers не попадают в Navigation, public Room projections, logs, traces, screenshots или raw exception text;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- WorkManager — durable orchestration boundary, authoritative publication ownership остаётся за DB lease + transactional activation;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix валидирует Android contracts, но не доказывает vendor codec/HDR/passthrough/Fire OS/weak ARM performance.

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

Measurement series example:

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
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- execution plan: [`docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md`](docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md).

## Лицензия

BSD 3-Clause.
