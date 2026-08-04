---
status: accepted
last_reviewed: 2026-08-04
architecture_version: 4
implementation_source_commit: 64b64c933da665d00ac403fd410a39309e773d64
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур проходит source onboarding → immutable catalog → Channels + Now/Next/Favorites → process-owned Media3 Player, а EPG-контур проходит bounded XMLTV → secure remote refresh → immutable EPG revision → deterministic current-policy channel matching → bounded Now/Next.

Текущий приоритет — daily-use product surfaces: Search → Recent → Guide. Новые storage/runtime frameworks не являются целью сами по себе.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Room 3, WorkManager, OkHttp, Media3.
- Room schema на принятом `main`: **v8**.
- Принятый product head: PR #92 → `64b64c933da665d00ac403fd410a39309e773d64`.
- PR #90 accepted merge: `7e1f18f31ab8628a104f2668d87e6478d7559242`.
- PR #91 закрыт как superseded старый Favorites stack.
- PR #88 закрыт как superseded stale truth-sync branch.
- Performance PR #83/#87/#89 закрыты без merge; идеи сохраняются только как measurement-gated candidates в issue #27.
- Self-hosted Android TV runner/matrix — acceptance/evidence-инфраструктура, а не отдельный product milestone.

## Что закрыто в production foundation

### Source/catalog/playback

- secure URL/access policy и Keystore-backed credential isolation;
- bounded streaming M3U ingest;
- immutable source revisions, staging, atomic activation и previous-good preservation;
- durable source refresh ownership/lease/run-token protection;
- stable canonical channel identity + profile overlays;
- typed `PlaybackCatalog` boundary;
- exact-origin HTTP approval и sensitive-header isolation;
- один process-owned MediaSessionService/ExoPlayer;
- Player → Back stable canonical-channel focus + nearest-previous fallback.

### EPG

- bounded SAX/streaming XMLTV parser без DOM;
- independent parser/decode limits + XXE/DTD hardening;
- plain/gzip/ZIP bounded payload decode;
- secure conditional remote acquisition (`ETag`/`Last-Modified`, correct `304`);
- immutable EPG revisions и current + previous-good retention;
- durable EPG refresh policy/state/attempt/validator persistence и DB lease ownership;
- deterministic EPG matching;
- producer revision + explicit matching-policy provenance в Room v8;
- stale-policy repair/current-policy reads;
- bounded Now/Next с accepted open-ended programme semantics;
- reconciliation after accepted catalog/EPG publication.

### Daily-use TV surface

- Channels destination-scoped ViewModel/state;
- bounded Now/Next projection and programme-boundary invalidation;
- Media3 playback-session projection;
- dedicated TV channel rows;
- stable-key Player → Back focus continuity;
- durable profile-scoped Favorites через existing `user_channel_overlays.isFavorite`;
- typed `ChannelPreferencesRepository.setFavorite`;
- Player favorite action;
- Channels `Все / Избранное` filtering;
- Room-side Favorites filtering;
- empty Favorites recovery;
- D-pad focus graph and surviving-canonical-key focus restoration.

### PR #92 acceptance

Exact head `cdd43173d00f3817555b2c640c411d82a9d75244`:

- Self-hosted validation `30873814952` — success;
- Android TV product DeviceMatrix `30873814955` — API26/API36, app instrumentation 18/18 each, 0 failures/errors/skips;
- database/device matrix `30873814953` — API26/API36, core database 93/93 each;
- `ChannelPreferencesRepositoryTest` — 5/5 in captured current-device report;
- unresolved review threads — 0;
- final review surface — 16 files.

## Активная реализация — Search Core / issue #29

Current working PR: #94. Его первоначальная ветка была создана до принятия Favorites и после merge #92 стала non-mergeable по двум общим DB registration files. `MuxTvDatabase` и `MuxTvDatabaseFactory` уже вручную reconciled так, чтобы сохранять одновременно `ChannelPreferences` и Search Room v9 paths. Финальный Search review должен быть clean post-Favorites surface; старую исследовательскую историю не следует merge-ить как отдельные commits.

Реализованный Search Core design/runtime scope:

- bounded `ChannelSearchRepository` API;
- max public results 200, max processed tokens 6;
- Room v9 derived FTS4 + `unicode61` для Unicode/Cyrillic correctness;
- safe quoted prefix encoding без user-controlled FTS syntax;
- compact unique EPG programme-title vocabulary вместо одного FTS row на programme occurrence;
- active catalog/EPG/current-policy truth revalidation;
- current-programme-only EPG enrichment;
- selective-seed bounded multi-token intersection;
- explicit truncation instead of false completeness;
- deterministic structured TV ranking;
- global earliest programme-boundary invalidation without polling;
- rowid-preserving search content lifecycle.

Оставшиеся Search Core gates:

1. clean post-Favorites branch/review surface;
2. generated Room v9 `9.json` committed exactly from Room output;
3. exact-head compile/KSP after Favorites reconciliation;
4. v8→v9 migration on API26/API36 and `unicode61` runtime proof;
5. non-zero Search DAO/index/repository contracts;
6. existing source/EPG ownership regressions green;
7. descriptive DB-size/backfill/query measurements before any extra prefix/title index;
8. guarded squash merge.

## Measurement foundation / issue #27

Уже доступны deterministic M3U 1k/10k/50k, canonical manifests, HLS/XMLTV fixtures, M3U/Room/Player measurement adapters, repository-owned `current-normal` / `old-edge-normal` / `current-low-ram` profiles и fresh-AVD series orchestration.

Остаётся:

1. 5× `current-normal`;
2. 5× `old-edge-normal`;
3. 5× `current-low-ram`;
4. cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` decisions;
6. durable performance report.

Старые #83/#87/#89 не переоткрывать. Если repeated evidence докажет выигрыш, полезный delta clean-rebuild от актуального accepted `main`.

## Текущий critical path

### P0 — Search Core / Room v9

Закончить clean post-Favorites Search Core, migration/runtime compatibility и bounded active-truth query.

### P1 — Search TV

- destination-scoped Search state;
- ~300 ms initial typing debounce, immediate explicit submit;
- cancellation/dedupe stale generations;
- input ↔ results D-pad graph;
- IME submit focus escape;
- Player → Back query + canonical focus restoration;
- no full-catalog/full-guide Compose filtering.

### P2 — Recent / ожидаемый Room v10

Отдельная profile-scoped durable history:

- `profileId`;
- `canonicalChannelId`;
- `lastSuccessfulPlaybackAt`;
- `successfulPlaybackCount`.

Запись только после confirmed successful playback, не после click/open/resolve/buffering. Retention bounded; hidden/inactive channels filtered on read.

### P3 — bounded Guide

Bounded channel × time viewport, lazy data projection, deterministic TV focus и Player/Back continuity. Full-guide materialization запрещён.

### P4 — issue #30 playback recovery + TV Doctor Lite

- bounded preferred → fallback1 → fallback2 → stop;
- attempt/total time budgets;
- typed DNS/TLS/TIMEOUT/HTTP/AUTH/REDIRECT/MANIFEST/DECODER/PLAYBACK;
- no retry storms;
- temporary fallback не меняет preferred variant;
- redacted diagnostics.

### P5 — issue #33 TV UX polish

Финализировать Lounge/navigation/row geometry/Player overlay/Sources flow после появления реальных Search/Guide routes, без новой state architecture.

### P6 — issue #31 alpha hardening

- R8/resource shrink;
- Compose compiler metrics;
- Macrobenchmark + Baseline/Startup Profiles;
- Java/native/process memory;
- upgrade/Keystore/Room recovery;
- physical Android/Google TV + constrained/Fire TV;
- signing, changelog, SBOM/licenses, release checklist.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine **не являются текущими correctness dependencies**. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет residual hotspot/compatibility gap, достаточный для ADR с FFI/ABI/packaging/debugging cost.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal/network behavior; physical-device validation остаётся обязательным перед alpha.
