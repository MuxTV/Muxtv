---
status: accepted
last_reviewed: 2026-08-06
architecture_version: 5
implementation_source_commit: 431168a1603dae94dc164a45cd1ac560025ad903
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites/Recent, bounded Search, bounded Guide data layer и service-owned Media3 Player с explicit transport classification и точной границей успешного playback по first rendered frame.

Текущий критический путь:

```text
Guide TV route (#29)
→ bounded fallback / TV Doctor (#30)
→ Lounge UI packages (#33/#93)
→ alpha hardening (#31)
```

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Принятый product head: PR #127 / #128 / #129 → `431168a1603dae94dc164a45cd1ac560025ad903`.
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
- Channels `Недавние`, bounded copy and stable D-pad/Player-Back continuity;
- cross-surface active/current-revision + selected-profile-visible truth contract (#114/#123/#124);
- bounded Guide channel/programme data window with typed `NO_GUIDE` / `SOURCE_CONFLICT` / `READY` states (#29 data layer);
- explicit HLS/MPEG-TS/DASH/progressive playback transport classification with `MODE_SINGLE_PMT` opt-in (#108);
- bare source host normalization to HTTPS to prevent downgrade (#116).

## Последняя acceptance

PR #128 exact head `985fbda` and PR #129 exact head `396424d` accepted into `431168a1603dae94dc164a45cd1ac560025ad903`:

- Android TV Product DeviceMatrix `31027992936` — success;
- Full host acceptance выполнен внутри матрицы;
- exact API26 и API36 profiles;
- database 120/120 на каждом профиле;
- app 26/26 на каждом профиле;
- Media3 12/12, credentials 4/4, importer EPG 1/1, remote EPG 1/1 на каждом профиле;
- Room v10 schema SHA-256 `809c0bfa812e5a86a5a84d97fe4f48f1d9ac71e515c5745ef222f24689e926c4`;
- Room identity `f6625d546ddfbad62e4e33340b17f490`;
- unresolved review threads — 0;
- squash merge — `431168a1603dae94dc164a45cd1ac560025ad903`.

## Активная реализация

### P0 — Guide TV route (#29, ветка `feat/guide-tv-route-29`)

Поверх принятого data-слоя #128:

- destination-scoped Guide state поверх `GuideWindowRepository`;
- sticky time/channel axes и bounded/lazy viewport;
- typed `READY` / `NO_GUIDE` / `SOURCE_CONFLICT` UI без full-guide materialization;
- deterministic keys и D-pad continuity;
- Player → Back focus restoration.

### P1 — playback recovery

1. issue #30: bounded variant attempts/time budget, typed failures (DNS/TLS/HTTP/redirect/timeout/decoder), secret-free диагностика и TV Doctor Lite; fallback потребляет typed transport из #108, а не ре-детектирует форматы;
2. buffer/FFmpeg work только после issue #27/physical-device evidence.

### Параллельные hardening дорожки

- issue #118 — Direct Boot/WorkManager: explicit no-refresh до user unlock, идемпотентная инициализация WorkManager после unlock, reboot/package-replace без дублей periodic work;
- issue #111 — TV remote контракты: long-press, dialog scrollability на 720p, focus/selected/playing контраст;
- issue #113 — portable backup envelope: versioned non-secret envelope + integrity digest до secrets-модели, SAF capability detection, restore на first-run;
- issue #101 — разделение Product/Database suites только с before/after wall-time evidence;
- issue #100 — conditional M3U `ETag`/`304` при свободном Room schema owner;
- issue #27 — repeated measurement series до structural optimization.

## Порядок следующих работ

1. Guide TV route/grid and D-pad continuity (#29, поверх принятого #128);
2. #30 bounded variant fallback + TV Doctor Lite (потребляет typed transport #108);
3. #33/#93 Lounge Light packages L1→L9 over real Search/Recent/Guide;
4. #31 release hardening and physical devices;
5. параллельно: #118 user-unlock startup gate, #111 TV remote contracts, #113 backup envelope, #101 CI suite split с measured evidence, #100 source validators/304 при свободном Room owner, #27 evidence lane.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

Old-edge/current emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
