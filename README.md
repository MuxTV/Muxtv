# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `e9dd0336716e27e9b51f4eb10da82169112e71d1`: PR #143 / issue #139 завершили tracked-worktree provenance для claim-eligible evidence поверх принятой exact-source-head CI модели.

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3. Room schema — **v10**; Room3 library — `3.0.0`; Media3 — `1.10.1`; `minSdk = 26`.

Рабочий продуктовый контур включает secure source onboarding, bounded streaming M3U/XMLTV ingest, immutable source/EPG revisions, transactional activation, durable refresh ownership, Channels/Favorites/Search/Recent/Guide, process-owned Media3 Player, service-owned first-rendered-frame success boundary, explicit playback transport classification и old-edge/current Android TV device harness.

### Последние принятые этапы

- PR #143 / issue #139 — clean tracked-worktree provenance для claim-eligible evidence;
- PR #142 / issue #140 — accepted-main focused `5×10k + 5×50k` M3U evidence lane;
- PR #138 / issue #111 D1 — immediate dense D-pad focus без queued geometry animation;
- PR #134 / issue #27 — deterministic focused M3U corpus/series harness;
- PR #133 / issue #112 — provider-readiness invariants;
- PR #137 / issue #136 — exact PR source-head evidence provenance;
- PR #131 / issue #29 — завершённый Guide TV route/UI.

Последняя принятая claim-eligible M3U acceptance на `main@e9dd033...`:

- workflow `Accepted main focused M3U evidence` run `31254022042` — success;
- `medium-10k` — 5 последовательных repetitions;
- `large-50k` — 5 последовательных repetitions;
- artifact `focused-m3u-evidence-31254022042-1`, id `9021482310`;
- artifact SHA-256 `ae7973542757c1f94844a4ba92daf22ad2dbcd3978108c1f224ccf21e0a4a0d4`;
- evidence остаётся descriptive: regression threshold и Rust/native rewrite из этого baseline не выводятся.

## Текущая реализация

### #30A / PR #145 — pure bounded same-channel recovery

PR #145 (`work/30a-playback-recovery-policy`, head `4c0074bb5417da261561250a75328cf9739eb9ab`) реализует identity-only recovery policy:

- preferred-first + stable remainder ordering;
- duplicate variant suppression;
- strict canonical-channel boundary;
- explicit positive attempt/duration budgets;
- total recovery deadline;
- `TRY_NEXT_CANDIDATE` / `STOP_RECOVERY`;
- stale generation invalidation;
- fallback success не меняет stored preferred variant.

Pure policy не принимает `PlayableVariant`, locator, user-agent, referrer, credentials, Media3/Android/Room/UI state. Реальный access resolution остаётся за `PlaybackCatalog.resolveVariant(...)`.

Current-head validation:

- Self-hosted Full `31258501384` — **success**;
- artifact `self-hosted-validation-31258501384-1`, id `9021984903`, SHA-256 `2acd95e877699383759b669daf2bd954775248465533a636b23b165e8210d42f1`;
- Product run `31258501378`: API26/API36 product matrix step — **success**;
- workflow red только из-за последующего `Upload product matrix evidence`; это CI publication debt #141, а не product regression.

## Следующие работы

Текущий critical path:

1. завершить и принять **#30A / PR #145**;
2. **#30B** — интегрировать policy в единственный process-owned `MediaSessionService` / `ExoPlayer`; Media3 loader retry и MuxTV candidate switching должны расходовать один total user-visible recovery deadline;
3. **#30C** — durable redacted diagnostics только если #30B докажет необходимость persistence и свободен Room schema owner;
4. **#30D** — TV Doctor Lite поверх typed sanitized observations;
5. параллельно завершить **#111 D2-D4**: native OK/Enter/long-press/repeat, independent focused/selected/playing/disabled states, reduced motion и 720p/1080p reachability;
6. затем Lounge Light D5-D7;
7. перед alpha — low-RAM/full-ingest evidence, R8, Baseline/Startup Profiles, signing/SBOM/release gates и physical Android/Google TV/Fire TV evidence.

Параллельные hardening-пакеты:

- **#141** — bounded retry evidence artifact upload; публикация остаётся обязательной;
- **#144** — CI path routing: JVM-only и infra-only изменения не должны без необходимости занимать device matrices;
- **#146** — Room3 `3.0.0 → 3.0.1` dependency-only hardening без изменения Room schema v10; желательно до следующего Room-owned #30C/#100;
- **#118** — no-refresh до user unlock + idempotent WorkManager init;
- **#113** — portable backup/restore envelope;
- **#101** — Product/Database connected-suite split с before/after wall-time evidence;
- **#100** — conditional source `ETag` / `Last-Modified` + correct `304 Not Modified` при свободном Room schema owner;
- **#39/#40** — user/recovery guide и pre-release/app-store gates.

**Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- sensitive source/playback metadata не попадает в Navigation, public Room projections, logs, traces, screenshots или Recent history;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession` в `MediaSessionService`;
- accepted playback success означает service-owned first rendered frame, а не `READY`/`isPlaying`;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- emulator/API matrix проверяет Android contracts, но не заменяет physical-device validation;
- evidence-producing CI исполняет тот commit, который записывает как `SourceCommit`; claim-eligible focused manual evidence дополнительно требует clean tracked worktree;
- Media3 internal loader retry и MuxTV same-channel variant recovery — разные уровни, но входят в один bounded user-visible recovery deadline;
- fallback на другой variant того же канала не изменяет пользовательский preferred variant.

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

Claim-eligible focused M3U series (`Repetitions >= 5`) fail-closed проверяет exact source commit и staged/unstaged tracked drift до создания evidence directory; untracked repository-owned evidence разрешён.

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
