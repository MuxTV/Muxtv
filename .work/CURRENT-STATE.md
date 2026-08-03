---
status: accepted
last_reviewed: 2026-08-03
architecture_version: 3
implementation_source_commit: 9325e0b4b124402a8eb5b1731442bce40a5404a8
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Рабочий Android TV контур уже проходит source onboarding → immutable catalog → Channels → process-owned Media3 Player, а EPG-контур проходит bounded XMLTV → secure remote refresh → immutable EPG revision → deterministic versioned channel matching → bounded Now/Next.

После merge PR #84 correctness foundation включает не только producer-revision provenance, но и explicit matching-policy provenance + stale-aware repair. Следующий продуктовый приоритет — daily-use TV UI (#29), параллельно с reproducible performance evidence (#27), а не новый storage/runtime/native framework.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp, Media3.
- Room schema на принятом `main`: **v8**.
- PR #80 → `12dce1ac95b5a2215c53f485bf70ffd13fad46b3`: deterministic EPG matching + bounded Now/Next.
- PR #84 → `9325e0b4b124402a8eb5b1731442bce40a5404a8`: matching-policy provenance, stale repair, Room v8.
- #71, #28 и #82 закрыты как completed.
- Exact-head #84 evidence: Full `30783348416` — success; API26/current database/device matrix `30783348361` — success.
- #84 matrix: по 118 instrumentation tests на API 26 и API 36, 0 failures/errors/skips; `core:database` — 88/88 на каждом профиле.
- Self-hosted Android TV runner/matrix — постоянная evidence-инфраструктура, а не отдельный product milestone.

## Что уже закрыто в production foundation

### Source/catalog/playback

- URL/access policy и credential isolation через Keystore-backed storage.
- Bounded streaming M3U ingest.
- Immutable source revisions, staging, atomic activation и previous-good preservation.
- Durable source refresh ownership/lease/run-token protection; stale worker не может опубликовать устаревший результат.
- Stable canonical channel identity + profile overlays.
- `PlaybackCatalog` остаётся read/playback boundary.
- Exact-origin HTTP approval и sensitive-header isolation.
- Один process-owned MediaSessionService/ExoPlayer переживает Activity recreation/reconnect.

### EPG

- Bounded SAX/streaming XMLTV parser без DOM.
- Independent input/depth/element/attribute/text/channel/programme/collection limits.
- Byte-level DOCTYPE rejection + rejecting entity resolver + Android-compatible SAX hardening.
- Plain/gzip/ZIP bounded payload decode.
- Secure conditional remote acquisition (`ETag`/`Last-Modified`, correct `304` semantics).
- Immutable EPG revisions, current + previous-good retention.
- Durable EPG policy/state/attempt/validator persistence and DB lease ownership.
- Deterministic matching: exact external/tvg identity → exact `tvgName` → exact `rawName`; ambiguity не превращается в weak winner.
- Persisted `epg_channel_matches` keyed by immutable EPG/catalog producer revisions **and current matching-policy provenance**.
- Room v7→v8 migration marks pre-versioned rows policy `0`/stale; current policy is `1`.
- Cheap freshness check + `reconcileIfStale`; `304 Not Modified` не вызывает full rematch, если derived state current.
- Guide/NowNext readers потребляют только current-policy rows.
- Bounded Now/Next with `READY | NO_GUIDE | SOURCE_CONFLICT` and open-ended programme handling.
- Reconciliation after accepted catalog/EPG publication и startup best-effort stale repair.

## Уже реализовано в открытых PR

### PR #81 — Channels Now/Next / issue #29

Clean-rebuilt на current post-#80 foundation; exact-head Full `30781623927` — success.

Реализовано:

- destination/back-stack-scoped `ChannelsViewModel`;
- immutable `StateFlow<ChannelsUiState>`;
- bounded Now/Next loading и programme-boundary reload;
- stale guide rejection on membership changes;
- metadata/order-only updates reuse the guide snapshot;
- Media3-backed playback-session projection без EPG reload на playback-only changes;
- dedicated TV channel rows;
- Navigation 3 saveable/ViewModel-store state;
- Player→Back focus continuity в unit/instrumentation contracts.

До merge не хватает exact-head TV/device acceptance: Channels focus → Player → Back и playback-session/MediaSession smoke. Green Full в одиночку этот gate не заменяет.

### PR #86 — Favorites / issue #29

Stacked on #81 and implemented:

- dedicated `ChannelPreferencesRepository`, не mutation через `PlaybackCatalog`;
- transactional Room preference write boundary через существующий `user_channel_overlays`;
- `Applied | Unchanged | NotFound`;
- Player favorite action;
- `Все каналы / Избранное` filter;
- Room-side filtering, empty-state recovery и filter-aware focus restoration.

После merge #81 ветку надо rebuild/retarget на accepted `main`, затем заново пройти Full + TV device journeys.

### PR #85 — EPG matching/Guide allocation Stage 2

PR #84 уже принят, поэтому #85 retargeted to `main`, но branch ancestry всё ещё загрязняет review surface (десятки inherited files/commits). До review/merge нужен clean rebuild на `9325e0b4…` с переносом только allocation-only delta и затем comparable A/B evidence.

### PR #83 — Core allocation Stage 1

Реализованы reusable M3U buffers/decoder, reusable SHA-256 state, playback-header fast paths, direct XMLTV timestamp scanner и Android microbenchmark module.

Предыдущий Full `30757037017` падал на configuration phase: AndroidX Benchmark 1.4.1 использовал legacy AGP `TestedExtension`, отсутствующий на AGP 9.3. В ветке Benchmark pin обновлён до `1.5.0-alpha07`; current exact head `9ac00e9a3f24cadffa24ea1d125a2080c3527972`. PR остаётся draft до нового exact-head Full, clean retarget/rebuild и comparable before/after measurements.

### PR #87 — XMLTV allocation Stage 2

Clean one-commit/one-file branch:

- reuse already-normalized captured XMLTV text вместо повторного `toString().trim()`;
- lazy reusable guarded `skip()` scratch buffer.

Correctness validation + allocation evidence ещё не получены; lazy metadata collections остаются measurement-gated.

## Measurement foundation / #27

Уже доступно:

- deterministic provider-neutral M3U corpora 1k/10k/50k;
- canonical manifests и repository generation entry point;
- bounded HLS/XMLTV fixtures и production XMLTV consumer binding;
- descriptive M3U/Room/Player measurement adapters;
- repository-owned `current-normal`, `old-edge-normal`, `current-low-ram` profiles;
- fresh-AVD sequential series orchestration и audit manifests.

Remaining acceptance:

1. five-run `current-normal`;
2. five-run `old-edge-normal`;
3. five-run `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` decision;
6. durable performance report and truth sync.

No structural optimization or native rewrite выбирается по one/two-run smoke evidence.

## Текущий critical path

### P0 — закрыть текущий stacked graph

1. **#81:** получить exact-head TV/device playback/focus evidence → merge.
2. **#86:** после #81 clean rebuild/retarget → Full + TV matrix → merge.
3. **#85:** clean rebuild на post-#84 `main` → correctness + comparable allocation evidence.
4. **#83:** подтвердить AGP9-compatible benchmark toolchain новым Full, затем clean rebuild/retarget + before/after M3U/XMLTV measurements.
5. **#87:** exact-head correctness + XMLTV allocation evidence; merge только при измеримом выигрыше без semantic drift.

Независимые evidence lanes (#81 device, #83/#87 perf, #27 profiles) можно выполнять параллельно; зависимые feature branches не следует постоянно ребейзить до стабилизации parent.

### P1 — issue #29 daily-use discovery

После принятия #81/#86:

1. bounded/debounced Search через отдельный query boundary по channel name/number/group + active programme metadata;
2. profile-scoped bounded Recent, обновляемый только после successful playback, не при Player open/failed resolve;
3. bounded/lazy TV Guide viewport;
4. D-pad/focus/Player Back continuity across filters/routes/restored state.

FTS не вводится заранее: сначала bounded Room query и measurements. Recent, если потребует новую Room schema, должен строиться уже поверх принятой v8, а не конкурировать за migration number с #84.

### P2 — issue #30 bounded fallback + TV Doctor Lite

- bounded attempt/time fallback ladder;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback failure families;
- no retry storms и no mutation of preferred variant от temporary fallback;
- bind HLS fixtures to real fallback consumer;
- redacted local diagnostics/export.

Media3 остаётся sole player engine, пока measured compatibility evidence не потребует отдельного ADR.

### P3 — issue #31 alpha hardening

- R8/resource shrinking;
- Compose compiler metrics;
- Macrobenchmark + Baseline/Startup Profiles;
- process/native memory evidence и API37 memory-limiter stress;
- physical Android/Google TV + constrained/Fire TV checks;
- upgrade/Keystore/Room recovery;
- signing, changelog, SBOM/licenses и release checklist.

## Branch hygiene

В репозитории остаётся заметное число старых `feat/docs/wip/rebuild` refs. Пока #81/#86/#85 ещё используют recovery/rebuild context, массово удалять их не следует. После стабилизации этого graph — отдельный branch-hygiene pass: оставить `main`, реально активные feature/perf branches и нужные release refs; merged/replaced branches удалить после проверки PR/commit reachability.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine **не являются текущими correctness dependencies**. Kotlin/Room/Media3 остаётся preferred path, пока repeated #27/#31 measurements не покажут residual hotspot/compatibility gap, достаточный для оправдания FFI/ABI/packaging/debugging/maintenance cost отдельным ADR.

## Evidence limits

API26/current emulator checks валидируют Android API, lifecycle, Room, focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough behavior, Fire OS, weak ARM SoCs, real network zap latency или thermal throttling; physical-device validation остаётся mandatory before alpha.
