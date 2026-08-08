---
status: accepted
last_reviewed: 2026-08-08
architecture_version: 5
implementation_source_commit: 5bb6ee1f754785b2b236d6dcb52fd4458780e758
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites/Recent, bounded Search, полноценный bounded Guide TV route и service-owned Media3 Player с explicit transport classification и точной границей успешного playback по first rendered frame.

Guide (#29) завершён и влит через PR #131. CI provenance gap #136 также закрыт: PR #137 влит в `main` как `5bb6ee1f754785b2b236d6dcb52fd4458780e758` после GREEN Full, Product old-edge/current, Database old-edge/current и Measurement variance. Evidence-producing PR workflows теперь явно checkout'ят source commit, который записывают как `SourceCommit`, и fail-closed проверяют `git rev-parse HEAD == ExpectedCommit`.

Текущий критический путь:

```text
#135 repository truth sync
→ restack/reconcile #133 + #134 on exact-evidence baseline
→ #27 deterministic 5×10k + 5×50k measurement evidence
→ #30A bounded same-channel recovery policy
→ #30B Media3 recovery runtime + typed observations
→ #30C durable redacted diagnostics if required
→ #30D TV Doctor Lite
→ #33/#93 Lounge Light packages
→ #31 alpha hardening + physical-device evidence
```

Параллельно идёт #111/PR #138: D1 immediate dense D-pad focus уже имеет наблюдённый RED и minimal GREEN на exact-evidence baseline. Эта design/accessibility дорожка не должна владеть Room/catalog/playback-recovery state и не блокирует #27, кроме временной конкуренции за self-hosted runner.

Issue #26 (Media3 OkHttp transport/reconnect hardening), от которой зависит #30, уже закрыта. После принятого #137 единственный продуктовый evidence-gate перед полноценным #30 — повторяемый #27 baseline. Pure policy/TDD подготовку #30 можно выполнять параллельно, но performance/compatibility defaults не должны опережать repeated evidence.

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Текущий принятый `main`: `5bb6ee1f754785b2b236d6dcb52fd4458780e758` — exact evidence provenance fix PR #137 поверх Guide baseline `286ece017445b811a7adddd4ba7e85cacc5dd3ea`.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: **v10**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Self-hosted topology: Full host acceptance до последовательных old-edge/current Android TV profiles.
- Evidence-producing PR workflows имеют executable source-head identity assertion.
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

### CI/evidence integrity

- host-before-device validation topology;
- old-edge API26 + current API36 TV matrices;
- repository-owned deterministic measurement harness;
- explicit source-head checkout for evidence PR workflows (#136/#137);
- repository-owned `Assert-EvidenceCommit.ps1` fail-closed guard;
- static workflow provenance drift tests;
- PR #137 accepted only after Full, Product matrix, Database matrix and Measurement variance GREEN.

## Последняя принятая acceptance

### Product baseline

PR #131 source head `a5e42d6aaa628b9fe09d6afb37e25ecb7d368773` влит squash/merge-коммитом `286ece017445b811a7adddd4ba7e85cacc5dd3ea`.

Исторические связанные PR validation runs:

- Self-hosted validation run `31210637363` — success;
- Android TV product device matrix run `31210636241` — success.

**Historical provenance caveat:** эти pre-#136 PR runs подтверждают успешную integration acceptance, но не являются строгим exact-source-head evidence, потому что тогда workflows исполняли default GitHub merge ref, записывая `pull_request.head.sha`.

### Latest CI/evidence baseline

PR #137 source head `02d6ee4b2641e12d88ace83bcd6af510f18bac08` принят в `main` как `5bb6ee1f754785b2b236d6dcb52fd4458780e758` после:

- Self-hosted Full — success;
- Database old-edge/current matrix — success;
- Android TV Product old-edge/current matrix — success;
- Measurement variance smoke — success;
- unresolved review threads — 0.

Эта точка является первым принятым baseline, где source-head evidence identity исполняется как контракт.

## Активная реализация

### P0 — truth + active PR reconciliation (#135/#133/#134)

- PR #135 restack'нут на accepted `5bb6ee1...`; point-in-time truth и execution plan должны пройти fresh exact-source Full перед merge;
- PR #133 / issue #112 сохраняет provider-readiness invariants и требует повторного Full + Product API26/API36 на новом baseline;
- PR #134 / issue #27 сохраняет fail-closed series evidence ownership и требует повторного Full + variance acceptance на новом baseline.

### P0-next — deterministic corpus / repeated evidence (#27, PR #134)

После принятия harness:

- выполнить sequential 5× `medium-10k` и 5× `large-50k`;
- использовать фиксированный seed и один контролируемый runner class;
- повторения обязаны подтверждать одинаковые corpus SHA-256/byte-count/expected counts;
- analyzer inputs обязаны перечислять все ожидаемые reports без silent omission;
- initial reports остаются descriptive; regression thresholds вводятся только после variance/provenance review.

### P0-after-evidence — bounded playback recovery / TV Doctor Lite (#30)

- bounded same-channel variant attempts со stable ordering;
- explicit max-attempt и total wall-clock budget;
- Media3 loader retry budget должен учитываться внутри общего recovery deadline и не умножаться бесконтрольно на число variants;
- typed DNS/TLS/HTTP/redirect/timeout/network/manifest/decoder/render/access observations;
- никакого cross-channel fallback;
- preferred variant не изменяется автоматически после временного fallback;
- Activity recreation / WorkManager / UI не становятся дополнительными retry owners;
- secret-free durable diagnostics и redacted export;
- TV Doctor Lite различает actionable failure families;
- alternate playback engine остаётся explicit non-goal.

### Parallel — TV design/accessibility (#111 / PR #138)

- D1: immediate dense focus, stable geometry, full visibility, 0 ms repeated-focus geometry motion;
- D2: native OK/Enter, long-press only where required, auto-repeat ownership, no global key interception;
- D3: independent focused/selected/playing/disabled states + reduced-motion contract;
- D4: 720p/1080p dialog scrollability and D-pad reachability;
- D5-D7 broad Lounge Light visual work waits until core playback/Doctor contracts are stable.

### Параллельные hardening дорожки

- issue #118 — Direct Boot/WorkManager: no-refresh до user unlock, идемпотентная инициализация после unlock, reboot/package-replace без дублей periodic work;
- issue #113 — portable backup envelope: versioned non-secret envelope + integrity digest до secrets-модели, SAF capability detection, restore на first-run;
- issue #101 — разделение Product/Database suites только с before/after wall-time evidence;
- issue #100 — conditional M3U `ETag`/`304` при свободном Room schema owner;
- dependency hardening — Room3 `3.0.0 -> 3.0.1` отдельным PR без изменения MuxTV Room schema version;
- issues #39/#40 — user guide/recovery и pre-release/app-store checklist до alpha/release claims.

## Порядок следующих работ

1. Finish #138 D1 RED→GREEN exact-source Full + Product matrix; после GREEN убрать dead scale-animation machinery отдельным behavior-preserving refactor.
2. Довести #135 до fresh GREEN и merge как актуальную repository truth.
3. Restack/reconcile #133 и #134 на `5bb6ee1...`; повторить required exact-source gates.
4. Завершить #133 независимо, если нет ownership conflict.
5. Завершить #134, затем выполнить #27 repeated 5×10k + 5×50k evidence и review variance/provenance.
6. Начать #30A pure recovery policy TDD без Media3/Room/UI coupling.
7. Реализовать #30B в process-owned `MediaSessionService`, явно разделяя Media3 loader retries и MuxTV variant switching внутри одного total deadline.
8. Добавить #30C durable diagnostics только при доказанной необходимости и свободном Room schema owner; затем #30D TV Doctor Lite.
9. Выполнить Room3 `3.0.1` dependency-only hardening в отдельном PR и database matrix.
10. После стабильных interaction/recovery contracts выполнить #33/#93 D5-D7 Lounge Light packages по одной реальной поверхности за PR.
11. Перед alpha закрыть #31 hardening, R8/Baseline/Startup Profiles с измерениями, #39/#40 docs/release gates и physical Android/Google TV/Fire TV evidence.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine не являются текущими dependencies. Kotlin/Room/Media3 остаются preferred path, пока repeated #27/#31 evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

Old-edge/current emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
