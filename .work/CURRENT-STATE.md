---
status: accepted
last_reviewed: 2026-08-05
architecture_version: 5
implementation_source_commit: 8fced4dc282eaf07e8160f463c8276d7e48ba01b
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites, bounded Search и service-owned Media3 Player с точной границей успешного playback по first rendered frame.

Текущий критический продуктовый путь:

```text
profile-scoped Recent / Room v10
→ cross-surface active/profile-visible truth contract
→ bounded Guide
→ bounded fallback / TV Doctor
→ Lounge UI packages
→ alpha hardening
```

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Принятый product head: PR #106 → `8fced4dc282eaf07e8160f463c8276d7e48ba01b`.
- Room schema на принятом `main`: **v9**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Self-hosted topology: Full host acceptance выполняется до последовательных old-edge/current Android TV device profiles.

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
- explicit `DirectionDown` handling from `BasicTextField` for API26 compatibility;
- service-owned `Player.Listener.onRenderedFirstFrame()` success boundary with setup-generation and current-media identity protection;
- exact profile/canonical-channel identity in the first-frame event;
- direct injected multi-observer recorder boundary with observer-failure isolation;
- privacy-safe activation-to-first-frame measurement.

## Последняя acceptance

PR #106 exact head `6bc33d8b61d0f687d52cdf6f65ca216035ef369d`:

- Self-hosted Full validation `30946905694` — success;
- Android TV Product matrix `30946905920` — old-edge/current success;
- exact setup generation + current media identity prevent delayed first-frame callbacks from completing a newer playback setup;
- direct recorder remains the durable-consumer boundary, while process-local events remain observational only;
- unresolved review threads — 0 at merge review;
- squash merge — `8fced4dc282eaf07e8160f463c8276d7e48ba01b`.

## Активная реализация

### P0 — Recent / Room v10

Draft PR #107 owns the next schema bump and product slice:

- composite identity `(profileId, canonicalChannelId)`;
- write only after accepted service-owned first rendered frame;
- bounded per-profile history;
- active/current-revision + profile-visible read projection;
- deterministic newest-first ordering;
- Channels `Все / Избранное / Недавние` surface;
- canonical D-pad focus restoration;
- migration 9→10 and exact generated Room schema artifact before merge.

PR #107 remains unaccepted until its final exact head passes Full plus old-edge/current product/database device matrices, the generated v10 schema is committed, and review/privacy gates are clean.

### P1 — cross-surface active truth contract (#114)

After #107 merges, start from fresh `main` and prove one shared semantic membership invariant across Playback, Search, Recent and Guide: current source revision only, selected-profile hidden overlay excluded, and surface-specific predicates applied only after base membership. Bounded `rows.size` must never be presented as an exact total.

This is the database/contract gate before full Guide UI.

### P2 — bounded Guide

- bounded channel window × bounded time window;
- stable deterministic row/window keys and tie-breaks;
- profile visibility/current source revision before time projection;
- explicit completeness/truncation semantics where required;
- sticky channel/time axes;
- no full-guide materialization in Compose;
- typed `NO_GUIDE` / `SOURCE_CONFLICT` states;
- current-time marker and deterministic Player/Back focus continuity.

## Параллельные, но не блокирующие пакеты

### PR #119 / issue #110 — EPG compressed transport hardening

Keep the existing bounded streaming decoder/parser architecture. Validate `Content-Encoding` before payload sniffing, preserve separate compressed/decoded byte ceilings, cancellation/close semantics and previous-good EPG state. This work must receive old-edge/current device coverage before acceptance.

### Issue #27 — evidence lane

Repeated current-normal / old-edge-normal / current-low-ram series are required before structural performance optimization or hard thresholds.

### Issue #101 — CI Phase 2

Split connected suites inside the existing AVD harness only when measured runtime/evidence shows the separation is useful; preserve non-zero selected module counts and equivalent correctness evidence.

### Issue #100 — conditional M3U refresh

Add source validators and correct `304 Not Modified` semantics only after the next Room schema owner is free. Do not create a competing migration while Recent owns v10.

## Дальнейший порядок

1. finish and accept #107 Recent/Room v10;
2. implement #114 cross-surface active/profile-visible truth contract from fresh accepted `main`;
3. implement bounded Guide DB window contract, then Guide TV UI;
4. rebase/accept independent #119 transport hardening through final exact-head device evidence;
5. issue #101 CI suite split where measurement justifies it;
6. issue #100 source validators when schema ownership is free;
7. issue #30 bounded fallback + TV Doctor Lite;
8. issues #33/#93 Lounge UI packages over real Search/Recent/Guide;
9. issue #31 R8, Baseline/Startup Profiles, endurance, signed alpha and physical-device evidence.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

Old-edge/current Android TV emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
