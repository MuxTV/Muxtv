---
status: accepted
last_reviewed: 2026-08-04
architecture_version: 5
implementation_source_commit: d12bad2c0acc0a0dbeeffbc2c25308d1143329d8
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites, bounded Search и service-owned Media3 Player.

Текущий критический продуктовый путь:

```text
first-rendered-frame success
→ profile-scoped Recent
→ bounded Guide
→ bounded fallback / TV Doctor
→ Lounge UI packages
→ alpha hardening
```

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Принятый product head: PR #104 → `d12bad2c0acc0a0dbeeffbc2c25308d1143329d8`.
- Room schema на принятом `main`: **v9**.
- Один process-owned `MediaSessionService` / `ExoPlayer`; альтернативный Hilt owner удалён PR #102.
- Self-hosted topology PR #103: Full host acceptance выполняется до последовательных API26/API36 `DeviceOnly` profiles.

## Что закрыто

### Source/catalog/security

- Keystore-backed credential isolation и exact-origin HTTP approval;
- bounded streaming M3U ingest;
- immutable source revisions, atomic activation и previous-good preservation;
- durable source refresh lease/run-token ownership;
- secure remote onboarding и durable pending registry;
- typed playback catalog resolution.

### EPG

- bounded secure XMLTV parsing и plain/gzip/ZIP decode;
- conditional EPG refresh с `ETag` / `Last-Modified` и корректным `304`;
- immutable EPG revisions и durable refresh ownership;
- deterministic current-policy channel matching;
- bounded Now/Next и programme-boundary invalidation.

### Daily-use TV

- Channels destination-scoped state and dedicated channel rows;
- deterministic D-pad graph;
- canonical Player → Back focus restoration and nearest-previous fallback;
- profile-scoped Favorites and Channels `Все / Избранное`;
- Room v9 bounded Unicode Search Core using FTS4 `unicode61`;
- safe prefix query encoding, compact current-programme vocabulary and active-truth revalidation;
- bounded Search TV with normalized/debounced cancellation, retry, truncation and one-shot boundary refresh;
- Search → Player → Back query/canonical-focus continuity;
- explicit `DirectionDown` handling from `BasicTextField` for API26 compatibility.

## Последняя acceptance

PR #104 exact head `d3478020b3af05c55fbbe71ee9ec656a7413a405`:

- Self-hosted Full validation `30935565574` — success;
- Android TV Product matrix `30935565897` — API26 and API36 passed;
- API36 app instrumentation — 22 tests, 0 failures/errors/skips;
- Search focus journeys — 4/4;
- unresolved review threads — 0;
- squash merge — `d12bad2c0acc0a0dbeeffbc2c25308d1143329d8`.

## Активная реализация

### P0 — first-rendered-frame success

Нужен точный service-owned сигнал успешного воспроизведения:

- `Player.Listener.onRenderedFirstFrame()` как единственная success boundary;
- setup-generation и current-media identity protection;
- profile + canonical channel identity;
- one-shot/idempotent delivery;
- direct injected recorder boundary, не `SharedFlow` как единственный durable transport;
- privacy-safe timing observation `activation → first frame`.

### P1 — Recent / ожидаемый Room v10

- composite identity `(profileId, canonicalChannelId)`;
- запись только после first rendered frame;
- `lastSuccessfulPlaybackAt` и bounded count/retention;
- hidden/inactive channels filtered on read;
- deterministic newest-first ordering;
- Channels/Home UI без дублирования channel identity;
- canonical D-pad focus restoration.

### P2 — bounded Guide

- bounded channel window × bounded time window;
- sticky channel/time axes;
- no full-guide materialization in Compose;
- typed `NO_GUIDE` / `SOURCE_CONFLICT` states;
- current-time marker and deterministic Player/Back focus continuity.

## Параллельные, но не блокирующие пакеты

### Issue #27 — evidence lane

- repeated current-normal / old-edge-normal / current-low-ram series;
- cross-profile interpretation;
- operation-specific hard/warning/descriptive decisions;
- no structural optimization without before/after evidence.

### Issue #101 — CI Phase 2

Разделить connected suites внутри одного AVD harness:

- `Product`: importer + refresh + credentials + database + Media3 + app;
- `Database`: importer/database-owned instrumentation only.

### Issue #100 — conditional M3U refresh

Добавить source validators и `304 Not Modified` после того, как следующий Room schema owner определён. Не создавать параллельную migration с Recent.

## Дальнейший порядок

1. first-frame recorder/profile identity;
2. Recent Room/data/UI;
3. bounded Guide;
4. issue #101 CI suite split;
5. issue #100 source validators, если свободен следующий schema owner;
6. issue #30 bounded fallback + TV Doctor Lite;
7. issues #33/#93 Lounge UI packages over real Search/Recent/Guide;
8. issue #31 R8, Baseline/Startup Profiles, endurance, signed alpha and physical-device evidence.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
