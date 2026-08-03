---
status: accepted
last_reviewed: 2026-08-03
architecture_version: 3
implementation_source_commit: 9325e0b4b124402a8eb5b1731442bce40a5404a8
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый `main` уже имеет рабочий Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player и EPG путь bounded XMLTV → secure refresh → immutable EPG revision → deterministic versioned matching → bounded Now/Next.

После PR #84 correctness foundation включает producer-revision provenance, explicit matching-policy provenance, stale-aware repair и Room schema **v8**. Следующий product critical path — issue #29 daily-use TV UI. Reproducible performance evidence из #27 идёт параллельно, а не заменяет продуктовую работу.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Room 3, WorkManager, OkHttp, Media3.
- Room schema на принятом `main`: **v8**.
- PR #80 → `12dce1ac95b5a2215c53f485bf70ffd13fad46b3`: deterministic EPG matching + bounded Now/Next.
- PR #84 → `9325e0b4b124402a8eb5b1731442bce40a5404a8`: matching-policy provenance, stale repair, Room v8.
- #71, #28 и #82 закрыты как completed.
- Exact-head #84 evidence: Full `30783348416` — success; API26/API36 database/device matrix `30783348361` — success.
- #84 matrix: 118 instrumentation tests на API26 и API36, 0 failures/errors/skips; `core:database` 88/88 на каждом профиле.
- Self-hosted Android TV runner/matrix — постоянная evidence infrastructure, не отдельный product milestone.

## Закрытый production foundation

### Source/catalog/playback

- URL/access policy и credential isolation через Keystore-backed storage.
- Bounded streaming M3U ingest.
- Immutable source revisions, staging, atomic activation и previous-good preservation.
- Durable source refresh ownership/lease/run-token protection.
- Stable canonical channel identity + profile overlays.
- `PlaybackCatalog` остаётся read/playback boundary.
- Exact-origin HTTP approval и sensitive-header isolation.
- Один process-owned MediaSessionService/ExoPlayer переживает Activity recreation/reconnect.

### EPG

- Bounded SAX/streaming XMLTV parser без DOM.
- Independent parser limits и XXE/DTD hardening.
- Plain/gzip/ZIP bounded payload decode.
- Secure conditional remote acquisition (`ETag`/`Last-Modified`, correct `304`).
- Immutable EPG revisions и previous-good retention.
- Durable EPG policy/state/attempt/validator persistence + DB lease ownership.
- Deterministic exact-ID → exact `tvgName` → exact `rawName` matching; ambiguity не превращается в weak winner.
- Persisted producer-revision + matching-policy provenance.
- v7→v8 migration marks old unversioned match rows stale; current policy is version 1.
- Cheap freshness check + `reconcileIfStale`; current-policy-only Guide/NowNext reads.
- Bounded Now/Next with `READY | NO_GUIDE | SOURCE_CONFLICT` and open-ended programme handling.

## Активный product graph

### PR #90 — Channels Now/Next / issue #29

Clean Room-v8 rebuild прежнего #81. Current head `0675015e7ba8c588c62be5f40927cbd466fc2338`, mergeable, draft.

Реализовано:

- destination/back-stack-scoped `ChannelsViewModel`;
- immutable `StateFlow<ChannelsUiState>`;
- bounded catalog + Now/Next reads;
- programme-boundary reload и stale guide generation rejection;
- metadata/order-only catalog updates reuse current guide snapshot;
- Media3-backed playback-session state source;
- playback-only changes re-project rows without EPG reload;
- dedicated TV channel rows;
- Navigation 3 saveable/ViewModel-store ownership;
- Player→Back stable focus restoration + nearest-previous fallback.

Exact-head Full `30785039850` — **success**. Artifact содержит unit/lint/build/instrumentation-compile evidence, но не фактический API26/API36 runtime journey. Remaining gate: product DeviceMatrix with Channels focus → Player → Back and MediaSession/playback-session evidence, then final review/head check and SHA-guarded merge.

PR #81 закрыт как superseded.

### PR #91 — Favorites / issue #29

Clean Favorites slice stacked on #90. Current head `3826443ec6bbf6ca2bd1bead8f2947378961f0bd`, verified parent→head **4 commits / 16 files**, no Room schema bump.

Реализовано:

- dedicated `ChannelPreferencesRepository`;
- transactional Room write через существующий `user_channel_overlays.isFavorite`;
- `Applied | Unchanged | NotFound`;
- active-visible canonical target verification и orphan-overlay prevention;
- Player favorite action;
- `Все каналы / Избранное` with Room-side filtering;
- empty-state recovery и filter/membership-keyed focus restoration;
- corrected historical #86 `initialFocusRequester` navigation regression.

Fresh PR workflow evidence на current #91 head пока отсутствует. После merge #90 надо clean-rebuild Favorites delta на accepted `main`, получить независимый review surface, затем exact-head Full + product DeviceMatrix и SHA-guarded merge.

PR #86 закрыт как superseded.

## Performance graph / issue #27

### PR #89 — EPG matching/Guide allocation Stage 2

Clean Room-v8 replacement прежнего #85: **2 commits / 2 files**.

Exact-head evidence:

- Full `30784628497` — success;
- API26/API36 database/device matrix `30784628471` — success.

Correctness gate green, но PR остаётся draft до comparable same-corpus/same-profile/same-environment before/after allocation evidence. Compilation/device green не является performance proof.

### PR #83 — Core allocation Stage 1

Reusable M3U buffers/decoder, reusable SHA-256 state, playback-header fast paths, direct XMLTV timestamp scanner и Android microbenchmark module. AndroidX Benchmark обновлён до `1.5.0-alpha07` после AGP9 `TestedExtension` incompatibility. Current head `9ac00e9a3f24cadffa24ea1d125a2080c3527972`; fresh PR workflow runs на этом SHA не обнаружены. Нужны fresh exact-head Full, clean rebuild/retarget и comparable A/B evidence.

### PR #87 — XMLTV allocation Stage 2

Clean one-commit/one-file parser slice. Current head `e617bb9c4198758aa7873a802c7b98bc089a627b`; fresh PR workflow runs на этом SHA не обнаружены. Correctness + allocation evidence обязательны до merge/performance claim.

### Measurement foundation

Уже доступны deterministic M3U corpora 1k/10k/50k, bounded HLS/XMLTV fixtures, M3U/Room/Player adapters, repository-owned `current-normal`, `old-edge-normal`, `current-low-ram` profiles и fresh-AVD sequential orchestration.

Remaining #27 acceptance:

1. 5× `current-normal`;
2. 5× `old-edge-normal`;
3. 5× `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate | warning-only | descriptive-only` decisions;
6. durable performance report.

## Critical path

### P0 — converge current graph

1. Add product Android-TV DeviceMatrix trigger/lane for UI/player changes.
2. Run exact-head product device acceptance for #90; if green and review-clean, merge #90 with exact SHA guard.
3. Clean-rebuild #91 Favorites delta on post-#90 `main`; validate independently and merge.
4. Keep #89/#83/#87 in parallel measurement lanes; do not block user-visible progress on unproven micro-optimizations.
5. Merge repository truth only after its own exact-head validation.

### P1 — issue #29 daily-use discovery

After #90/Favorites acceptance:

1. bounded/debounced Search through a dedicated query boundary covering effective channel name/number/group + active programme metadata;
2. profile-scoped bounded Recent written only after confirmed successful playback;
3. bounded/lazy TV Guide viewport keyed by channel IDs + time window + explicit limits;
4. D-pad/focus/Player Back continuity across filters/routes/restored state.

FTS is not introduced until bounded Room queries are measured inadequate. Recent history should be its own durable profile-scoped model rather than overloading `user_channel_overlays`; any migration starts from accepted Room v8.

### P2 — issue #30

- bounded variant fallback attempt/time ladder;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback failure families;
- no retry storms;
- temporary fallback never mutates preferred variant implicitly;
- production-bound HLS fixtures;
- redacted TV Doctor Lite diagnostics/export.

### P3 — issue #31

- R8/resource shrinking;
- Compose compiler metrics;
- Macrobenchmark + Baseline/Startup Profiles;
- process/native-memory evidence и API37 limiter stress;
- physical Android/Google TV + constrained/Fire TV evidence;
- install/upgrade, Keystore and Room recovery;
- signing, changelog, SBOM/licenses and release checklist.

## Branch hygiene

Не удалять массово recovery refs, пока #90/#91 и performance rebuilds не стабилизированы. После принятия активного graph выполнить отдельный branch-hygiene pass и удалить только merged/replaced branches после проверки reachability.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine **deferred**. Kotlin/Room/Media3 остаётся preferred path, пока repeated #27/#31 evidence не покажет residual hotspot/compatibility gap, оправдывающий FFI/ABI/packaging/debugging/maintenance cost отдельным ADR.

## Execution plan

Текущий подробный checkpoint: `docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md`.

## Evidence limits

API26/API36 emulator checks валидируют Android API, lifecycle, Room, focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM SoCs, реальную zapping/network latency или thermal throttling; physical-device validation остаётся mandatory before alpha compatibility claims.
