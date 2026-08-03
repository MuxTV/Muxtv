# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий private-репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Repository truth синхронизирован на `main` commit `c38b3c04d8e4229dd1fb8a8ec40d60d18bb0b2fa`; принятый implementation foundation проходит через merge PR #80 (`12dce1ac95b5a2215c53f485bf70ffd13fad46b3`).

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp и Media3. Принятая Room schema на `main` — **v7**. `minSdk = 26`.

### Рабочий IPTV путь

1. добавить HTTPS M3U или отдельно подтвердить HTTP-источник;
2. сохранить access в Android Keystore-backed credential store вне Room public projections;
3. потоково импортировать M3U в staging и атомарно активировать immutable source revision;
4. открыть Channels и запустить канал;
5. для нового HTTP host/port подтвердить exact origin;
6. Player заново разрешает active variant и устанавливает request в process-owned MediaSession/ExoPlayer;
7. Player → Back восстанавливает stable channel focus.

### Принятый EPG/source correctness foundation

- PR #63 — bounded streaming XMLTV parser без DOM, с independent limits, XXE/DTD protection и typed timestamp handling;
- PR #64 — immutable EPG Room foundation, monotonic atomic activation, previous-good retention и bounded queries;
- PR #68 — bounded magic-first plain/gzip/ZIP EPG payload decoder;
- PR #72 — secure conditional remote EPG refresh, корректный `304`, bounded decode/import и cancellation preserving previous-good guide;
- PR #74/#75 — durable EPG policies/state/attempts/validators, DB lease, stale reclaim, WorkManager orchestration и access/run-token publication ownership;
- PR #78 — source refresh publication ownership hardened по тем же captured-binding/run-token/cancellation/redaction guarantees;
- PR #80 — Room v7 deterministic explainable channel matching, persisted match provenance по immutable producer revisions, bounded Now/Next и end-to-end integration path.

Последнее принятое exact-head evidence для PR #80:

- Full: `30766566746` — success;
- API26/current database/device matrix: `30766566756` — success.

## Что ещё не завершено

### P0 — распрямить открытый stacked graph

1. **PR #81 / issue #29** — Channels Now/Next + destination-scoped state: код clean-rebuilt на current `main`, exact-head Full уже green; перед merge ещё требуется exact-head TV/device, playback-session и focus/Player-back acceptance evidence.
2. **PR #86 / issue #29** — Favorites: rebuild/retarget после merge #81, затем Full + TV/device validation.
3. **PR #84 / issue #82** — matching policy provenance / Room v8: versioned matching policy, stale-aware repair и migration contract; перед merge обязателен committed generated v8 schema и green exact-head API26/current migration matrix.
4. **PR #85** — EPG allocation Stage 2: retarget только после #84; claims принимаются только с allocation evidence.
5. **PR #83 и PR #87** — allocation-only Core/XMLTV stages: rebase/retarget на принятую correctness-базу и сравнивать только на сопоставимых measurement profiles.

Независимые ветки не следует искусственно сериализовать: device evidence для #81, correctness work в #84 и performance evidence для #87 могут идти параллельно. Зависимые #86/#85 не следует постоянно ребейзить до стабилизации их parent PR.

### P1 — issue #29 daily-use discovery

После принятия #81/#86:

1. bounded/debounced Search по channel name/number/group и active programme metadata через отдельный query boundary;
2. profile-scoped bounded Recent, который обновляется только после successful playback, а не при открытии Player/failed resolve;
3. bounded/lazy TV Guide viewport;
4. D-pad/focus/Player Back continuity между routes, filters и restored state.

FTS не вводится заранее: сначала нужны bounded Room queries и измерения.

### P2 — issue #30

Bounded variant fallback + TV Doctor Lite:

- bounded attempt/time ladder без retry storms;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback failure families;
- temporary fallback не меняет preferred variant;
- реальные HLS fixtures привязываются к production fallback consumer;
- diagnostics/export остаются redacted.

### P3 — issue #31

Alpha hardening:

- R8/resource shrinking;
- Compose compiler metrics;
- Macrobenchmark + Baseline/Startup Profiles;
- process/native memory evidence и API37 memory-limiter stress;
- physical Android/Google TV, constrained hardware и Fire TV evidence;
- upgrade/Keystore/Room recovery;
- signing, changelog, SBOM/licenses и release checklist.

## Performance / issue #27

Measurement foundation уже существует: deterministic M3U corpora, bounded HLS/XMLTV fixtures, M3U/Room/Player adapters, immutable report identity, `current-normal` / `old-edge-normal` / `current-low-ram`, fresh-AVD sequential orchestration.

Осталось получить и зафиксировать:

1. пять независимых repetitions `current-normal`;
2. пять `old-edge-normal`;
3. пять `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation classification: `hard-gate` / `warning-only` / `descriptive-only`;
6. durable performance report и repository truth sync.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими correctness-задачами.** Они допускаются только после reproducible bottleneck/compatibility evidence из #27/#31 и отдельного ADR, который оправдывает FFI/ABI/packaging/debugging complexity измеримым выигрышем.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- playlist/XML locators, query values, cookies, credentials, provider identities и sensitive headers не попадают в Navigation, public Room projections, logs, traces, screenshots или raw exception text;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- один in-process owner encrypted source access;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не доказывает vendor codec/HDR/passthrough/Fire OS/weak ARM performance.

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

- текущая repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- current-profile variance smoke: [`docs/performance/2026-07-30-current-variance-smoke.md`](docs/performance/2026-07-30-current-variance-smoke.md);
- открытые functional/performance packages ведутся через GitHub Issues и отдельные reviewable PR.

## Лицензия

BSD 3-Clause.
