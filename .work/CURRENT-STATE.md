---
status: accepted
last_reviewed: 2026-08-03
architecture_version: 3
implementation_source_commit: 12dce1ac95b5a2215c53f485bf70ffd13fad46b3
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Рабочий Android TV контур уже проходит source onboarding → immutable catalog → Channels → process-owned Media3 Player, а EPG-контур проходит bounded XMLTV → secure remote refresh → immutable EPG revision → deterministic channel matching → bounded Now/Next.

После merge PR #80 correctness foundation для XMLTV/matching/NowNext считается закрытым. Следующий приоритет — не новый storage/runtime framework, а доведение daily-use UI и распрямление уже реализованного stacked-графа.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp, Media3.
- Room schema на принятом `main`: **v7**.
- Feature merge: PR #80 → `12dce1ac95b5a2215c53f485bf70ffd13fad46b3`.
- #71 и #28 закрыты как `completed` merge-ом #80.
- Exact-head #80 evidence: Full `30766566746` — success; API26/current database/device matrix `30766566756` — success.
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
- Immutable EPG revisions, Room v5→v6→v7 migrations, current + previous-good retention.
- Durable EPG policy/state/attempt/validator persistence and DB lease ownership.
- Deterministic matching in explicit provider relation: exact external/tvg identity → exact `tvgName` → exact `rawName`; ambiguity never silently becomes a weak winner.
- Persisted `epg_channel_matches` keyed by immutable EPG/catalog producer revisions.
- Bounded Now/Next with `READY | NO_GUIDE | SOURCE_CONFLICT` and open-ended programme handling.
- Reconciliation after accepted catalog/EPG publication.

## Уже реализовано в открытых PR

### PR #81 — Channels Now/Next

Implemented, but needs clean rebuild on current `main` after #80 squash:

- destination/back-stack-scoped `ChannelsViewModel`;
- immutable `StateFlow<ChannelsUiState>`;
- bounded Now/Next loading and programme-boundary reload;
- stale guide rejection on membership changes;
- Media3-backed playback-session projection without EPG reload on playback-only changes;
- dedicated TV rows and Player→Back focus continuity;
- direct `feature:player → player:api` dependency;
- OkHttp BOM for app instrumentation tests.

### PR #86 — Favorites

Stacked on #81 and implemented:

- dedicated `ChannelPreferencesRepository` rather than mutating `PlaybackCatalog`;
- transactional Room preference write boundary using existing `user_channel_overlays`;
- `Applied | Unchanged | NotFound` mutation result;
- Player favorite action;
- `Все каналы / Избранное` filter;
- Room-side filtering, empty-state recovery and filter-aware focus restoration.

### PR #84 — matching policy provenance / Room v8

Implemented but still draft/stacked:

- explicit `matchPolicyVersion` separate from reason code;
- v7→v8 migration with legacy rows as policy `0` (stale), current policy `1`;
- current-policy freshness and stale-aware repair;
- Guide readers consume only current-policy rows;
- `MigrationTestHelper.runMigrationsAndValidate(8)` contract added;
- trusted Full artifact already produced the Room/KSP-generated `8.json` (`identityHash 52995b2ea0cba6fecc6a6c8670152032`), which must be committed exactly rather than hand-authored.

### Performance PRs

- #83 Core allocation Stage 1: reusable M3U buffers/decoder, reusable SHA-256 state, playback header fast paths, direct XMLTV timestamp scanner, Android microbench module.
- #85 EPG allocation Stage 2: collision-on-demand ambiguity sets, single-pass matching summary, direct Now/Next loops.
- #87 XMLTV allocation Stage 2: reuse normalized text and reusable guarded `skip()` scratch buffer; lazy metadata lists remain measurement-gated.

## Measurement foundation / #27

Already available:

- deterministic provider-neutral M3U corpora 1k/10k/50k;
- canonical manifests and repository generation entry point;
- bounded HLS/XMLTV fixtures and production XMLTV consumer binding;
- descriptive M3U/Room/Player measurement adapters;
- repository-owned `current-normal`, `old-edge-normal`, `current-low-ram` profiles;
- fresh-AVD sequential series orchestration and audit manifests.

Remaining acceptance:

1. five-run `current-normal`;
2. five-run `old-edge-normal`;
3. five-run `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` decision;
6. durable performance report and truth sync.

No structural optimization or native rewrite should be selected from one/two-run smoke evidence.

## Текущий critical path

### P0 — clean stacked graph

1. Rebuild/merge #81 on current `main`.
2. Rebuild/merge #86 after #81.
3. Rebuild #84 on current `main`, commit generated Room v8 schema, validate and merge → close #82.
4. Retarget/merge #85 after #84.
5. Retarget/validate #83 and #87 with comparable allocation evidence.

### P1 — issue #29 daily-use discovery

После принятия #81/#86:

1. bounded/debounced Search through a dedicated query boundary, including channel name/number/group and active programme metadata;
2. profile-scoped bounded Recent updated only after successful playback, not on Player open/failed resolve;
3. bounded/lazy Guide viewport;
4. D-pad/focus/Player Back continuity across filters/routes.

Do not introduce FTS until bounded Room query measurement shows a real need.

### P2 — issue #30 bounded fallback + TV Doctor Lite

- bounded attempt/time fallback ladder;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback failure families;
- no retry storms and no mutation of preferred variant from temporary fallback;
- bind HLS fixtures to real fallback consumer;
- redacted local diagnostics/export.

Media3 remains the sole player engine unless measured compatibility evidence requires an ADR.

### P3 — issue #31 alpha hardening

- R8/resource shrinking;
- Compose compiler metrics;
- Macrobenchmark + Baseline/Startup Profiles;
- process/native memory evidence and API37 memory-limiter stress;
- physical Android/Google TV + constrained/Fire TV checks;
- upgrade/Keystore/Room recovery;
- signing, changelog, SBOM/licenses and release checklist.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv and a second playback engine are **not current correctness dependencies**. The existing Kotlin/Room/Media3 path remains preferred until repeated #27/#31 measurements identify a residual hotspot or compatibility gap large enough to justify FFI/ABI/debugging/maintenance cost.

## Evidence limits

API26/current emulator checks validate Android API, lifecycle, Room, focus, MediaSession and database contracts. They do not validate vendor MediaCodec/HDR/passthrough behavior, Fire OS, weak ARM SoCs, real network zap latency or thermal throttling; physical-device validation remains mandatory before alpha.
