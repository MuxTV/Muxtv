---
status: accepted
last_reviewed: 2026-08-08
architecture_version: 5
implementation_source_commit: 286ece017445b811a7adddd4ba7e85cacc5dd3ea
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites/Recent, bounded Search, полноценный bounded Guide TV route и service-owned Media3 Player с explicit transport classification и точной границей успешного playback по first rendered frame.

Guide (#29) завершён и влит в `main` через PR #131. Текущий критический путь после повторной сверки зависимостей:

```text
#27 deterministic corpus + repeated measurement evidence
→ #30 bounded variant fallback / TV Doctor Lite
→ #33/#93 Lounge UI packages
→ #31 alpha hardening + physical-device evidence
```

Issue #26 (Media3 OkHttp transport/reconnect hardening), от которой зависит #30, уже закрыта как completed. Поэтому единственный незакрытый evidence-gate перед полноценным #30 — #27. Архитектурный/TDD slice #30 можно готовить параллельно с тяжёлыми measurement runs, но performance/compatibility решения не должны опережать повторяемые #27 evidence.

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Текущий принятый `main`: `286ece017445b811a7adddd4ba7e85cacc5dd3ea` — Guide TV route merge PR #131.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: **v10**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Self-hosted topology: Full host acceptance до последовательных old-edge/current Android TV profiles.
- Альтернативный playback engine, Rust/UniFFI и bundled SQLite не являются текущими dependencies.

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
- bounded Guide channel/programme data window with typed `NO_GUIDE` / `SOURCE_CONFLICT` / `READY` states;
- bounded Guide TV viewport, deterministic D-pad/focus restoration и Guide → Player navigation (#29/#131);
- local half-hour Guide timeline alignment, включая quarter-hour-offset time zones;
- explicit HLS/MPEG-TS/DASH/progressive playback transport classification with `MODE_SINGLE_PMT` opt-in (#108);
- Media3 OkHttp transport/reconnect hardening (#26);
- bare source host normalization to HTTPS to prevent downgrade (#116).

## Последняя acceptance

PR #131 exact head `a5e42d6aaa628b9fe09d6afb37e25ecb7d368773` принят и влит squash/merge-коммитом `286ece017445b811a7adddd4ba7e85cacc5dd3ea`.

На exact head перед merge:

- Self-hosted validation run `31210637363` — success;
- Android TV product device matrix run `31210636241` — success;
- Guide route/UI acceptance была выполнена на том же exact head;
- Room schema/migrations, player transport и CI topology PR #131 не менял.

## Активная реализация

### P0 — deterministic corpus / repeated evidence (#27, PR #134)

Текущий незавершённый evidence lane:

- deterministic 1k/10k/50k M3U corpus уже является repository-owned contract;
- PR #134 добавляет последовательную measurement series с exact source-commit/profile/seed provenance;
- один series evidence directory должен иметь единственного владельца и никогда не перетираться;
- повторения обязаны подтверждать одинаковые corpus SHA-256/byte-count/expected counts;
- initial reports остаются descriptive; regression thresholds вводятся только после repeated variance evidence;
- после GREEN harness нужны реальные 5×10k и 5×50k серии и review analyzer provenance перед performance claims.

### P0-next — bounded playback recovery / TV Doctor Lite (#30)

После наличия измеримого #27 baseline:

- bounded same-channel variant attempts со stable ordering и explicit max-attempt/time budget;
- typed DNS/TLS/HTTP/redirect/timeout/manifest/decoder/playback observations;
- никакого cross-channel fallback и никаких endless retry loops;
- preferred variant не изменяется автоматически после временного fallback;
- secret-free durable diagnostics и redacted export;
- TV Doctor Lite различает auth/provider/network/manifest/codec/decoder/render families;
- Activity recreation / WorkManager / Player не должны умножать retry storms;
- alternate playback engine остаётся explicit non-goal.

### Параллельные hardening дорожки

- PR #133 / issue #112 — provider-readiness snapshot/contract для будущих native/provider-specific источников без подмены active catalog truth;
- issue #118 — Direct Boot/WorkManager: explicit no-refresh до user unlock, идемпотентная инициализация после unlock, reboot/package-replace без дублей periodic work;
- issue #111 — TV remote контракты: long-press, dialog scrollability на 720p, focus/selected/playing contrast;
- issue #113 — portable backup envelope: versioned non-secret envelope + integrity digest до secrets-модели, SAF capability detection, restore на first-run;
- issue #101 — разделение Product/Database suites только с before/after wall-time evidence;
- issue #100 — conditional M3U `ETag`/`304` при свободном Room schema owner;
- issues #39/#40 — user guide/recovery и pre-release/app-store checklist до alpha/release claims.

## Порядок следующих работ

1. Довести PR #134 до GREEN и сохранить fail-closed ownership всей measurement evidence series.
2. Выполнить repeated #27 baseline (минимум 5×10k и 5×50k), проверить variance/provenance; не вводить structural optimization без измеренного bottleneck.
3. Параллельно завершить PR #133, если exact-head host/device checks подтверждают provider-readiness invariants.
4. Начать #30 с отдельного TDD-контракта bounded same-channel candidate policy и typed redacted diagnostics; не смешивать policy, UI Doctor и engine changes в один неразделимый diff.
5. После core #30 добавить TV Doctor Lite presentation/export и fixture/device evidence.
6. Затем выполнять #33/#93 Lounge Light packages поверх реально принятых Channels/Search/Recent/Guide/Player contracts.
7. Перед alpha закрыть #31 hardening, #39/#40 docs/release gates и physical Android/Google TV/Fire TV evidence.
8. Параллельные #118/#111/#113/#101/#100 брать отдельными PR по свободным ownership boundaries, не блокируя основной playback critical path без доказанной зависимости.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

Old-edge/current emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
