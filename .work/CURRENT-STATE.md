---
status: accepted
last_reviewed: 2026-08-05
architecture_version: 5
implementation_source_commit: 7af053ca14281d9e63a51470fbeb3cb8d708c318
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites/Recent, bounded Search и service-owned Media3 Player с точной границей успешного playback по first rendered frame.

Текущий критический путь:

```text
cross-surface active/profile-visible truth contract (#114)
→ bounded Guide database window
→ Guide TV route
→ explicit transport classification (#108)
→ bounded fallback / TV Doctor (#30)
→ Lounge UI packages
→ alpha hardening
```

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Принятый product head: PR #107 → `7af053ca14281d9e63a51470fbeb3cb8d708c318`.
- Room schema: **v10**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Self-hosted topology: Full host acceptance до последовательных old-edge/current Android TV profiles.

## Что закрыто

### Source/catalog/security

- Keystore-backed credential isolation и exact-origin HTTP approval;
- bounded streaming M3U ingest;
- immutable source revisions, atomic activation и previous-good preservation;
- durable source refresh lease/run-token ownership;
- secure remote onboarding и durable pending registry;
- typed playback catalog resolution.

### EPG

- bounded secure XMLTV parsing;
- streaming plain/gzip/ZIP decoder;
- separate compressed/decoded byte ceilings;
- `Content-Encoding` validation before payload sniffing;
- conditional EPG refresh с `ETag` / `Last-Modified` и корректным `304`;
- immutable EPG revisions и durable refresh ownership;
- previous-good EPG preservation after malformed/oversized refresh;
- deterministic current-policy channel matching;
- bounded Now/Next и programme-boundary invalidation.

### Daily-use TV

- Channels destination-scoped state and dedicated channel rows;
- deterministic D-pad graph;
- canonical Player → Back focus restoration and nearest-previous fallback;
- profile-scoped Favorites and Channels `Все / Избранное`;
- Room v9 bounded Unicode Search Core using FTS4 `unicode61`;
- active-truth Search revalidation and bounded Search TV;
- Search → Player → Back query/canonical-focus continuity;
- explicit API26 search-field D-pad Down handling;
- service-owned `onRenderedFirstFrame()` success boundary;
- setup-generation + current-media identity protection;
- exact profile/canonical-channel first-frame identity;
- direct multi-observer recorder with observer-failure isolation;
- profile-scoped bounded Recent in Room v10;
- first-frame-only Recent writes, newer-wins/idempotent delivery and cap 50/profile;
- active/current-revision + non-hidden Recent projection;
- Channels `Недавние`, bounded copy and stable D-pad/Player-Back continuity.

## Последняя acceptance

PR #107 exact head `d095fb0e99485f93f9dbed8675c13b0f5ac52537`:

- Android TV Product DeviceMatrix `31027992936` — success;
- Full host acceptance выполнен внутри матрицы;
- exact API26 и API36 profiles;
- database 120/120 на каждом профиле;
- app 26/26 на каждом профиле;
- Media3 12/12, credentials 4/4, importer EPG 1/1, remote EPG 1/1 на каждом профиле;
- Room v10 schema SHA-256 `809c0bfa812e5a86a5a84d97fe4f48f1d9ac71e515c5745ef222f24689e926c4`;
- Room identity `f6625d546ddfbad62e4e33340b17f490`;
- unresolved review threads — 0;
- squash merge — `7af053ca14281d9e63a51470fbeb3cb8d708c318`.

## Активная реализация

### P0 — issue #114 cross-surface truth contract

Добавить один database-owned integration contract на fresh `main`, не новый SQL framework:

- Playback, Search, Recent и Guide должны иметь одинаковую active/current-revision + selected-profile-visible membership;
- staged/previous-revision provider rows не должны появляться на user-facing surfaces;
- hidden overlay должен одинаково применяться к rows/direct playback/Search/Recent/Guide;
- выполнить active revision swap в той же in-memory базе и повторить assertions;
- profile-agnostic low-level lookup допустим только за profile-aware public boundary;
- bounded `rows.size` нельзя выдавать за exact total;
- production query меняется только если RED докажет drift.

### P1 — bounded Guide

После #114:

- bounded canonical-channel slice;
- bounded time interval + small prefetch margin;
- единый programme-overlap predicate;
- active/profile-visible membership до time projection;
- deterministic stable keys/tie-breaks;
- explicit completeness/truncation state;
- sticky channel/time axes;
- typed `NO_GUIDE` / `SOURCE_CONFLICT`;
- no full-guide materialization in Compose;
- deterministic Player/Back focus continuity.

### P2 — playback recovery

1. issue #108: explicit HLS/raw MPEG-TS/DASH/progressive classification at one Media3 choke point;
2. issue #30: bounded variant attempts/time budget, typed failures and TV Doctor Lite;
3. buffer/FFmpeg work только после issue #27/physical-device evidence.

## Параллельные hardening packages

### Issue #121 — Room migration/schema guard

- production-owned ordered current migration chain;
- current-schema tests reuse that chain;
- targeted migration tests remain independent;
- require exact generated schema for current DB version;
- deterministic failure on missing/stale schema;
- no destructive fallback.

### Issue #101 — CI Phase 2

Разделить Product/Database connected suites внутри текущего AVD harness только после before/after wall-time evidence. Не создавать второй emulator lifecycle.

### Issue #100 — conditional M3U refresh

Добавить source validators и `304 Not Modified`, когда свободен следующий Room schema owner. Не создавать конкурирующую migration.

### Issue #27 — evidence lane

Repeated current-normal / old-edge-normal / current-low-ram series до structural optimization, hard thresholds, buffer policy или native decoder adoption.

## Порядок следующих работ

1. implement/accept #114 cross-surface membership contract;
2. bounded Guide DB window contract;
3. Guide TV route/grid and D-pad continuity;
4. #108 transport classification;
5. #30 fallback + TV Doctor;
6. #121 database migration/schema guard в свободном schema window;
7. #101 CI suite split с measured evidence;
8. #100 source validators/304 при свободном Room owner;
9. #33/#93 Lounge packages over real Search/Recent/Guide;
10. #31 release hardening and physical devices.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

Old-edge/current emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
