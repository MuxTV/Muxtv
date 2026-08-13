# MuxTV `0.1.0-alpha.1` — архитектурно согласованный план до закрытой MVP alpha

**Статус документа:** proposed canonical successor / handoff plan
**Дата сверки:** 2026-08-13
**Репозиторий:** `MuxTV/Muxtv` (private)
**Проверенный `main`:** `1249624db5010e8140814a56553ea194c6d25d66` — PR #160
**Architecture version:** 2
**Room schema:** 10
**Application ID:** `app.muxtv.tv`
**Release identity:** `versionCode=1001`, `versionName=0.1.0-alpha.1`
**Android:** `minSdk=26`, `compileSdk=37`, `targetSdk=37`, Java 17
**Назначение:** единый план дальнейшего пути от принятого Search S5 к приватному GitHub pre-release `0.1.0-alpha.1`.

---

## 1. Назначение и границы документа

Этот документ сводит в один непротиворечивый план четыре класса источников:

1. фактическое состояние кода в текущем `main`;
2. принятые PR, GitHub issues и CI/evidence runs;
3. действующий canonical ExecPlan и repository-truth поверхности;
4. актуальные официальные Android/AndroidX/GitHub рекомендации на июль–август 2026.

Документ является **декларативным планом**, а не инструкцией исполнителю. Он фиксирует последовательность пакетов, архитектурные инварианты, границы scope, acceptance/evidence и источники решений.

### Приоритет источников при конфликте

`current main` → merged PR evidence → актуальные issue contracts → canonical ExecPlan → `.work` truth surfaces → исторические документы → внешние reference projects.

Внешняя практика не отменяет уже принятые MuxTV-инварианты без отдельного доказанного дефекта или ADR.

---

## 2. Проверенное состояние репозитория

### 2.1 Текущий source-of-truth

На момент сверки:

- `main = 1249624db5010e8140814a56553ea194c6d25d66`;
- HEAD — merge PR #160 `perf(search): scope refresh to published results`;
- открытых PR нет;
- `main` в GitHub API отмечен как `protected=false`;
- Room schema остаётся v10;
- release identity `0.1.0-alpha.1` и release optimization уже присутствуют;
- Baseline Profile producer подключён, `automaticGenerationDuringBuild=false`;
- приложение таргетирует Android 17 / API 37.

### 2.2 Что реально закрыто к текущему HEAD

| Контур | Фактический статус | Основание |
|---|---|---|
| Source / secure URL / credentials | accepted | current code + merged pre-S5 train |
| Streaming M3U/XMLTV + atomic revisions | accepted | current code/issues/evidence |
| Channels S4 Room Paging | accepted | PR #157 |
| Search S5 top-N | **accepted** | PR #159 + PR #160 |
| Guide bounded data windows | implementation already present | `RoomGuideWindowRepository`, `GuideViewModel` |
| Guide S6 closure | **next product-data package** | canonical ExecPlan + current code gap |
| Service-owned Media3 Player | accepted | PR #150 and current Player boundary |
| Bounded same-channel recovery | accepted runtime core | #30 + PR #150/#151 |
| Doctor playback observations/export | accepted | PR #151/#152 |
| Source-refresh Doctor diagnostics | open residual | M4 / #30 |
| Player auto-hiding TV overlay | open residual | M4 / #33 |
| D-pad shared remote contracts | open residual | M6 / #111 |
| Release identity + R8/resource optimization | accepted | PR #153/current build |
| Baseline Profile CUJ closure | open | M3/#31 |
| Signing / artifact provenance / upgrade proof | open | M7/#31 |
| Physical Android/Google TV release gate | open | #31 |
| Reboot/unlock/WorkManager lifecycle contract | open | #118 |

---

## 3. Обнаруженный drift между планом, кодом и repository truth

### 3.1 Search S5 уже завершён, но truth surfaces показывают обратное

`CURRENT-STATE.md` и `.work/meta/status.yaml` всё ещё ссылаются на `main@1ec2298...`, считают PR #158 последним принятым и оставляют S5 measurement/top-N активными.

Это противоречит:

- PR #159, merged as `b8ab064aed58b2b6789514ae858c979f0ccb4713`;
- PR #160, merged as `1249624db5010e8140814a56553ea194c6d25d66`;
- текущему коду Search, где global programme-boundary scan уже удалён, а refresh scoped к опубликованному top-N;
- Search presentation projection, где display labels/result IDs вынесены из Compose.

**Плановая классификация:** S5 — `accepted`; текущий milestone — S6 Guide.

### 3.2 Старый S5 50k measurement contract больше не пригоден как обязательный gate

Canonical ExecPlan всё ещё содержит обязательную идею 50k+EPG baseline/five repetitions.

Фактический workflow run `31537375406` на self-hosted runner:

- выполнялся примерно 2 ч 47 мин;
- основной validation step был отменён после многократного продолжительного исполнения;
- run не является экономически пригодным шаблоном обычного PR/MVP acceptance.

При этом PR #160 сознательно не повторял long device/Full/50k runs и сохранил ограниченный correctness/runtime contract.

**Плановая классификация:**

- 50k corpus сохраняется как synthetic correctness/stress asset;
- timed/repeated 50k execution не является обязательным PR, S6 или release gate;
- claim на поддержку large catalog подтверждается bounded architecture + reachability/correctness, а не многочасовым benchmark ritual;
- абсолютные performance claims относятся к physical release evidence.

### 3.3 Автоматический heavy M3U lane имеет слишком широкий trigger

Текущий `.github/workflows/focused-m3u-evidence.yml` запускается не только вручную, но и на `push` в `main` для широкого набора путей (`tools/ci/**`, Gradle/build-logic, `core/testing/**`, `catalog/ingest/**` и др.). Job выполняет `5x10k + 5x50k` и имеет `timeout-minutes: 180`.

**Плановая классификация:** heavy repeated M3U evidence — manual/stress lane, а не автоматический main-push gate.

### 3.4 Guide уже имеет bounded data architecture

Текущий Guide не является пустой заглушкой:

- channel window использует keyset cursor и `limit + 1`;
- programme window ограничен выбранными channel IDs и временным диапазоном;
- `GuideViewModel` имеет generation/cancellation, bounded page history и адаптивное временное окно 6h → 3h → 90m → 45m при truncation;
- projection различает `READY`, `NO_GUIDE`, `SOURCE_CONFLICT`;
- UI использует stable key `channelId` и identity-based focus anchor.

Следовательно S6 не должен превращаться в новый Paging/schema/storage проект.

### 3.5 Player runtime зрелее Player UX

Player сохраняет корректную identity-only границу и service ownership, но `PlayerContent` в `Ready` постоянно рисует нижний control overlay. Это расходится с #33, где TV contract предусматривает fullscreen video, controls hidden by default, `OK` для показа и `Back` для первоначального скрытия overlay.

### 3.6 Lifecycle issue #118 не закрыт, но Direct Boot crash нельзя объявлять доказанным

`MuxTvApplication.onCreate()` инициирует DB/matching/onboarding/scheduler reconciliation без отдельной проверки `UserManager.isUserUnlocked()`.

Одновременно manifest **не** содержит `directBootAware=true`; по официальной Android документации приложения по умолчанию не запускаются в Direct Boot. Поэтому текущее состояние не доказывает pre-unlock crash.

Реальный незакрытый контракт #118:

- отсутствие functional refresh до unlock;
- idempotent reconcile после доступного normal runtime;
- отсутствие duplicate periodic work после reboot/package replace;
- сохранение stale-generation/lease invariants.

### 3.7 Dependency state требует точечной, а не массовой актуализации

Текущий version catalog:

- Room3 `3.0.0`;
- Navigation3 `1.1.4`;
- Media3 `1.10.1`;
- Paging `3.5.0`;
- Benchmark `1.5.0-alpha07` с repository comment о необходимости для AGP 9.x DSL.

Официальная AndroidX таблица на 2026-07-29:

- Room3 stable `3.0.1`;
- Navigation3 stable `1.1.5`;
- Media3 stable `1.10.1`, `1.11.0-rc01` preview;
- Paging stable `3.5.0`.

Следовательно:

- #146 Room3 patch соответствует актуальному stable patch и остаётся isolated hardening;
- Navigation3 1.1.5 — небольшой optional stable patch;
- Media3 **не требует** перехода на 1.11 preview перед alpha;
- Benchmark alpha pin не подлежит механическому downgrade к «stable», пока репозиторий подтверждает AGP 9.x compatibility constraint.

---

## 4. Непересматриваемые архитектурные инварианты MVP

### 4.1 Playback ownership

- Один `MuxTvPlaybackService` владеет ExoPlayer, MediaSession, recovery generation, timeout и cancellation.
- UI/Navigation передают только identity: `profileId`, `channelId`, optional `preferredVariantId`.
- URI, headers и credentials разрешаются внутри service boundary непосредственно перед attempt.
- Media3 отвечает за retry/backoff внутри одного candidate; MuxTV отвечает только за переход между deterministic same-channel candidates.
- First rendered frame остаётся success boundary.
- Один generation: максимум 3 candidates / максимум 20 секунд.
- Late/stale callbacks не меняют новое поколение.
- UI не создаёт второй retry/fallback owner.

### 4.2 Security/privacy

Не допускается попадание locator/header/credential/token/raw exception в:

- navigation;
- saved state;
- logs;
- Doctor export;
- measurement artifacts;
- screenshots/semantics/test names;
- GitHub Actions artifact metadata.

Keystore-backed secret/access-ref boundary сохраняется.

### 4.3 Data integrity

- immutable source/EPG revisions;
- staging → atomic activation;
- previous-good retention;
- cancellation/supersession не публикует stale generation;
- selected-profile-visible + active/current-revision truth;
- schema change только при продуктовой необходимости и с migration evidence.

### 4.4 Surface-specific data strategy

- **Channels:** Room Paging, поскольку это последовательный browser большого каталога.
- **Search:** bounded ranked top-N, default 100 / max 200 / explicit `isTruncated`; Search Paging не вводится.
- **Guide:** bounded keyset channel window + bounded time programme window; full EPG grid не материализуется.

### 4.5 UI state ownership

- Compose остаётся rendering/focus interaction layer, а не data/query engine.
- Не вводится второй глобальный Store/MVI/Redux слой поверх уже принятого ViewModel/Flow ownership.
- Не вводится custom global focus engine.
- Stable identity важнее index-based restoration.

### 4.6 Performance evidence

- Performance work остаётся measurement-led.
- Synthetic large corpus не равен обязательному timed gate.
- Оптимизация требует выявленного bottleneck; произвольный универсальный процент улучшения не является самостоятельной архитектурной целью.
- Emulator evidence подтверждает functional/API behavior, но не absolute ARM-TV performance, vendor codec, HDR/passthrough, thermal или real-network claims.
- Absolute release ceilings валидируются на physical target-class device.

---

# 5. Последовательность до `0.1.0-alpha.1`

## C0 — Repository truth и evidence-policy reconciliation

### Назначение

Закрытие расхождения между `main@1249624...` и устаревшими canonical/truth surfaces до начала S6.

### Состав

- S5 Search классифицируется как `accepted` через PR #159/#160.
- `implementation_source_commit` и active milestone синхронизируются с текущим accepted main.
- `CURRENT-STATE`, `status.yaml`, ROADMAP и canonical ExecPlan перестают описывать уже выполненный global-boundary/Compose-label work как future work.
- Timed/repeated 50k Search/M3U evidence переводится из обязательного MVP critical path в manual stress evidence.
- `focused-m3u-evidence.yml` классифицируется как manual-only heavy lane; автоматический `push: main` trigger не является частью целевой CI модели.
- Issue #27 разделяет measurement infrastructure/corpus readiness и конкретные future performance investigations; отсутствие threshold gate не считается дефектом само по себе.

### Архитектурные ограничения

- Runtime behavior не меняется.
- Room schema не меняется.
- Search public contract не меняется.
- Historical evidence не удаляется.

### Acceptance

- Все mutable truth surfaces согласны относительно current HEAD и следующего milestone.
- S5 не остаётся одновременно `accepted` в коде и `active` в metadata.
- Ни один обычный main push не запускает автоматически `5x10k + 5x50k` workload.
- 50k corpus остаётся доступным вручную для stress/correctness investigation.

### Evidence class

Host `Fast/Full`; device execution не требуется при отсутствии runtime/platform changes.

---

## S6 — Guide bounded-performance и presentation closure

### Назначение

Доведение существующего bounded Guide до принятого MVP contract без новой data architecture.

### Существующая база

- `RoomGuideWindowRepository` — bounded channel/programme windows;
- `GuideViewModel` — generation cancellation, cursor paging, bounded history, adaptive time-span fallback;
- `GuideRoute` — stable row key, focus anchor, current-time marker, TV timeline.

### Scope

#### Presentation projection

UI-facing projection содержит до Compose:

- стабильный row/cell identity;
- channel primary/secondary labels;
- programme time label;
- `NO_GUIDE` / `SOURCE_CONFLICT` presentation copy;
- focus detail label;
- secret-safe semantics/test identity.

Geometry, theme colors, Dp calculations, current-time marker и actual focus state остаются Compose concern.

#### Focus/navigation

Сохраняются:

- channel ID + programme key identity;
- deterministic nearest/surviving fallback;
- Player → Back restoration;
- page transition semantics;
- safe focus при EPG/data invalidation.

#### Bounded performance validation

Основной workload соответствует реальному consumer window, а не 50k full-grid:

- текущий channel window;
- 6h default programme window с существующим adaptive narrowing;
- realistic programme density;
- mix explicit stop/open-ended programmes;
- `NO_GUIDE` и source conflict.

Большой backing catalog может использоваться для проверки index selectivity/reachability, но UI query остаётся bounded.

#### SQL optimization

Изменения `GuideWindowDao`/indexes/schema относятся к S6 только при воспроизводимом bounded evidence конкретного hotspot. Существующий correlated next-programme lookup не переписывается по предположению.

### Не входит

- Guide Paging3;
- full-guide materialization/cache;
- Room schema bump без измеренного требования;
- новая EPG model;
- новый focus engine;
- 50k timed Guide benchmark.

### Acceptance

- Полный Guide никогда не материализуется для UI.
- `READY`, `NO_GUIDE`, `SOURCE_CONFLICT` отображаются детерминированно.
- Truncated programme window вызывает только существующее bounded narrowing или явный `Incomplete` state.
- Stale generation не публикует UI state.
- Focus identity переживает reload и Player → Back.
- Static Guide не создаёт app-owned formatting churn на каждом frame/recomposition.
- D-pad journey остаётся пригодным на 720p и 1080p.
- Long Russian labels не ломают row geometry/focus reachability.

### Evidence class

- feature/database unit tests;
- targeted Guide instrumentation;
- Full validation;
- Product API26/API36 при изменении runtime/UI behavior;
- Android 17/API37 target-behavior smoke — availability-conditioned, поскольку приложение уже `targetSdk=37`.

---

## M4-R — Player/Doctor residual closure

### Назначение

Закрытие оставшегося UI/diagnostic scope поверх уже принятого service-owned runtime.

### Player overlay contract

Presentation states включают как минимум:

- hidden controls;
- visible controls;
- interaction-resets-hide-timer;
- transient recovery/status presentation;
- terminal failure/Doctor path.

TV semantics:

- video fullscreen по умолчанию;
- Center/OK раскрывает controls;
- inactivity скрывает controls, если focus/interaction не удерживают их;
- Back при открытом overlay сначала закрывает overlay;
- Back при закрытом overlay возвращает на originating surface;
- скрытые controls не сохраняют недоступный focus;
- media keys не создают параллельный player state owner.

Favorite/обычные actions входят в единый coherent control surface; отдельный floating control не является целевой финальной композицией.

### Recovery presentation

UI отображает service-owned recovery/terminal result, не реализуя собственный цикл candidates/retry.

### Source-refresh Doctor adapter

Existing source refresh state проецируется в bounded typed, redacted diagnostic model. Экспорт исключает source URLs, credentials, headers, query tokens, access refs и raw exceptions.

### Acceptance

- Один ExoPlayer/MediaSession owner сохраняется при Activity recreation и UI overlay transitions.
- Old overlay timer/state не изменяет новый playback generation/channel.
- Failed A → stop/replace → healthy B не переносит recovery/transport state.
- Doctor показывает actionable typed categories без secret-bearing payload.
- Channels/Search/Guide → Player → Back восстанавливает originating focus identity.

### Evidence class

Player/service JVM + instrumentation + Product API26/API36; physical playback остаётся release gate, а не обязательным для каждого UI PR.

---

## M6-R — Remote interaction, accessibility и минимальный Lounge Light closure

### Назначение

Закрытие TV-specific usability contract без repository-wide redesign.

### Remote semantics

- short Center press — одна activation;
- long press не поглощается generic preview wrapper;
- repeated/held key не умножает destructive action;
- Back/Direction остаются у ожидаемого focus/navigation owner;
- dynamic removal/recomposition оставляет валидную focus target.

### Visual states

Независимо различимы:

- focused;
- selected;
- currently playing;
- disabled;
- favorite, где релевантно.

Состояние не кодируется одним цветом. Dense list/Guide focus не меняет геометрию соседей.

### Reduced motion

- frequent D-pad focus остаётся immediate;
- flourish удаляется без потери outline/tone feedback;
- редкие overlay transitions остаются короткими и функциональными.

### Reachability

- 720p/1080p safe areas;
- D-pad scroll для variable-height dialogs/lists;
- long RU strings;
- deterministic first/last focus transitions.

### Shell/navigation

Компактная Lounge Light shell допускается теперь, когда Search и Guide являются реальными destinations. Она не меняет Navigation3 ownership и не создаёт параллельный presentation store.

### Не входит

- theme engine;
- global focus framework;
- permanent preview/miniplayer;
- speculative Home recommendations;
- QR/local-web setup;
- large visual redesign, не связанный с alpha journeys.

### Acceptance

Shared TV interaction tests #111 + core D-pad journeys проходят на 720p/1080p. Player controls, Guide и dialogs не имеют focus traps или touch-only recovery paths.

---

## L1 — Lifecycle / reboot / package-replace contract (#118)

### Назначение

Доказуемая background-work семантика на reboot/update без расширения Direct Boot scope.

### Contract

- MuxTV не заявляет functional refresh во время Direct Boot.
- Main Room/Keystore/DataStore state остаётся credential-protected.
- `directBootAware=true` не вводится для DB/credential-dependent components без отдельного design decision.
- Normal runtime initialization/reconcile идемпотентен.
- Reboot/package replacement не создаёт duplicate periodic work.
- Disabled/deleted/superseded source не публикуется stale worker'ом.
- Manual refresh остаётся доступным после normal app launch.

### Важное уточнение

Текущий manifest не opt-in'ится в Direct Boot, поэтому сам факт отсутствия `isUserUnlocked()` в `Application.onCreate()` не доказывает pre-unlock crash. Evidence должен проверять реальный lifecycle/reconciliation contract, а не предполагать дефект.

### Acceptance

- reboot/normal unlock/launch path не создаёт duplicate unique work;
- package replace сохраняет или корректно reconciles schedules;
- stale ownership tokens остаются fail-closed;
- no protected-state migration to device-protected storage.

---

## D1 — Dependency hardening и freeze

### Current → eligible stable changes

| Dependency | Current | Official stable (verified) | Alpha policy |
|---|---:|---:|---|
| Room3 | 3.0.0 | 3.0.1 | isolated #146 candidate |
| Navigation3 | 1.1.4 | 1.1.5 | optional isolated patch |
| Media3 | 1.10.1 | 1.10.1 | stay stable; 1.11.0-rc01 not default alpha upgrade |
| Paging | 3.5.0 | 3.5.0 | no change |
| Benchmark | 1.5.0-alpha07 | repository-specific | retain while AGP9 constraint applies |

### Room3 3.0.1

#146 остаётся отдельным package без MuxTV schema change. Patch релевантен из-за upstream bug fixes, но не смешивается с S6, Player UX или release signing.

### Navigation3 1.1.5

Допустим как маленький maintenance patch до dependency freeze; отсутствие patch не является alpha blocker при отсутствии затронутого bug.

### Media3

`1.10.1` остаётся official stable в проверенной AndroidX таблице от 2026-07-29. Переход на `1.11.0-rc01` перед private alpha не входит в базовый план. Preview upgrade возможен только как отдельное evidence-driven решение при конкретном blocker.

### Freeze point

После lifecycle/UI closure dependency graph замораживается до final release candidate, кроме security/critical regression fix с отдельным validation scope.

---

## M3-C — Performance, Macrobenchmark и Baseline Profile closure

### Принцип

Финальное performance evidence создаётся после стабилизации Guide, Player UX, remote semantics и lifecycle, чтобы profile/benchmark соответствовал реальному release execution path.

### Critical User Journeys

Release-relevant CUJs:

1. cold startup → interactive Home;
2. warm startup;
3. Home → Channels;
4. Channels bounded scroll/navigation;
5. Search query → result focus;
6. Guide open/navigation;
7. Player open → local HLS first frame;
8. bounded recovery/fallback;
9. Player → Back focus restoration.

### Baseline Profile

Текущая конфигурация `automaticGenerationDuringBuild=false` совместима с cost-aware CI. Release closure включает:

- CUJ-based profile generation;
- generation на non-minified/profile variant;
- consumption/rewrite в minified release;
- presence/profile packaging verification;
- before/after benchmark на physical device.

Автоматическая генерация profile на каждом обычном build не является необходимой для MVP.

### Measurement tiers

#### Tier A — cheap deterministic PR evidence

Unit/JVM/host tests, contracts, bounded small fixtures.

#### Tier B — functional Android evidence

API26/API36 TV device matrices только для change classes, где platform/UI/Room behavior реально меняется.

#### Tier C — bounded performance smoke

1k/10k или consumer-shaped data, когда change заявляет performance effect. Размер определяется реальным consumer window и стоимостью feedback loop.

#### Tier D — manual stress

50k corpus и repeated large-series runs. Не required branch/merge/release gate.

#### Tier E — physical release evidence

Absolute startup/frame/first-frame/memory/recovery claims.

### 50k policy

Сохраняется функциональный инвариант: large catalog не приводит к full UI materialization. Но многочасовой timed 50k Search/M3U execution не является обязательным доказательством каждого release candidate.

### Acceptance

- CUJs executable на release-shaped build;
- Baseline Profile packaged/consumed;
- physical before/after evidence доступно;
- no material regression в user-visible journeys;
- performance fix имеет hotspot-specific evidence, а не формальное универсальное требование «≥30% для каждого perf PR».

---

## M7-R — Release engineering, signing и artifact provenance

### Release artifact set

Приватный `0.1.0-alpha.1` release set содержит:

- signed APK;
- SHA-256 checksum manifest;
- R8 mapping artifact (restricted при необходимости);
- dependency inventory / SBOM;
- license report;
- toolchain/source/device manifest;
- benchmark/profile summary;
- known limitations / tested-device scope;
- Doctor export/rollback notes.

### Signing secret boundary

GitHub Environment остаётся preferred boundary для release secrets, но required-reviewer policy является **capability-conditioned**:

- environment secrets доступны только jobs, которые ссылаются на environment;
- required reviewer используется только если GitHub plan/private-repository policy это поддерживает;
- при отсутствии required reviewers manual `workflow_dispatch`, restricted release refs/branches, exact SHA provenance и минимальный secret exposure образуют fallback control plane;
- signing material не сохраняется в caches/artifacts/logs;
- temporary keystore удаляется после release job;
- persistent self-hosted runner требует explicit clean/reset и post-job secret hygiene.

### Self-hosted security

GitHub официально указывает, что self-hosted runner не получает автоматически clean instance на каждый job. Существующий MuxTV reset/clean/preflight contract поэтому сохраняется.

Ephemeral/JIT release runner является более сильной будущей изоляцией, но не вводится как новый обязательный инфраструктурный проект для private alpha, если текущий dedicated private runner остаётся надёжно изолированным и очищаемым.

### Upgrade lineage

Поскольку предыдущей публичной alpha lineage нет, controlled seed APK с той же signing identity используется как предшественник для проверки `install -r`/upgrade, Room data и Keystore continuity.

### Reproducibility

Два clean release builds сравниваются по содержимому, toolchain/source identity и signing provenance. Timestamp/signature-container differences документируются; code/resource divergence считается failure.

### `main` protection

Текущий GitHub API сообщает `protected=false`. До release полезен lightweight ruleset/branch protection для source-of-release либо, если это конфликтует с выбранным workflow, как минимум exact-SHA release eligibility + restricted release workflow/ref policy. Release provenance не должен зависеть от mutable branch name без commit pinning.

---

## RC — Android 17 + physical Android/Google TV release candidate

### API compatibility

Поскольку приложение `targetSdk=37`, Android 17 target behavior должен иметь явное release evidence.

Матрица:

- API26 Android TV — old supported edge;
- API36 Android TV — representative current TV image в существующем runner setup;
- API37 — target-behavior smoke при наличии пригодного образа; допускается отдельный compatibility image, если полноценного Android TV API37 image нет;
- physical Android/Google TV — обязательный scope-limited release/performance gate.

API37 smoke не заменяет API36 TV D-pad/device journey, а API36 не заменяет API37 target-specific behavior coverage.

### Physical journey

Release-shaped signed APK подтверждает:

- fresh install;
- protected source onboarding;
- refresh/atomic catalog availability;
- Channels/Favorites/Recent;
- Search;
- Guide;
- playback + bounded recovery/error;
- Doctor/export;
- Player → Back focus restoration;
- process/activity restart;
- controlled same-key upgrade lineage.

### Physical claims

Только physical evidence используется для absolute claims по:

- startup;
- D-pad latency/jank;
- first rendered frame;
- memory/PSS;
- recovery timing;
- vendor MediaCodec/stream behavior.

Fire TV certification/compatibility не является обязательным closed-alpha gate и не заявляется без отдельного device evidence.

---

# 6. CI routing model до alpha

| Change class | Host | Product TV | DB matrix | Perf | Heavy 50k | Physical |
|---|---|---|---|---|---|---|
| docs/truth only | Full/contract | — | — | — | — | — |
| JVM/pure policy | Fast + Full | — | — | targeted JMH only if relevant | — | — |
| Guide UI/state | Full | API26/API36 | only if DB changed | bounded smoke if perf claim | manual only | RC only |
| Room/DAO/schema | Full | targeted if product behavior | API26/API36 | bounded DB measurement | manual only | RC if release-impacting |
| Player/Media3 UX | Full | API26/API36 | — | local HLS/bounded if relevant | — | RC |
| WorkManager/lifecycle | Full | API26/API36 | targeted if DB lifecycle touched | — | — | RC |
| dependency patch | Full | affected subsystem | affected subsystem | regression only | — | before freeze if needed |
| release/signing | Full | release journey | migration/upgrade where relevant | final CUJs | optional/manual | mandatory |

### CI invariants

- pinned Actions SHAs;
- least privilege;
- no `pull_request_target` for untrusted execution;
- fork code excluded from persistent privileged runner;
- exact source SHA in evidence;
- self-hosted workspace reset/clean before and after relevant jobs;
- emulator/device serialization via dedicated label;
- artifact upload remains authoritative when evidence is required;
- CI routing minimises unnecessary use of the single Android device runner.

---

# 7. Issue alignment

## Alpha-critical / directly consumed

| Issue | Role in this plan |
|---|---|
| #27 | measurement foundation; heavy 50k reclassified to manual stress, not permanent gate |
| #30 | Player bounded recovery/Doctor residual UI + source diagnostics |
| #31 | Baseline Profile, release artifacts, signing, physical gate |
| #33 | Player overlay + minimal TV shell/UX contract |
| #111 | remote short/long/repeat/focus/reachability contracts |
| #118 | reboot/unlock/package-replace WorkManager lifecycle |
| #146 | isolated Room3 3.0.1 hardening candidate |

## Useful but not alpha critical path

| Issue | Classification |
|---|---|
| #93 | design reference; does not independently expand MVP scope |
| #101 | CI efficiency improvement; only if runner contention blocks critical work |
| #141 | artifact reliability; reactive hardening if recurrence continues |
| #144 | CI routing efficiency; compatible with C0/CI cleanup |

## Deferred / evidence-conditioned

| Issue | Reason |
|---|---|
| #100 | HTTP validator optimization, not current correctness blocker |
| #109 | adaptive buffer policy only after measured stall/buffer evidence |
| #112 | provider expansion beyond closed alpha |
| #113 | backup/restore excluded from closed alpha |
| #115 | preview/miniplayer additional playback consumer |
| #117 | FFmpeg fallback only for proven codec gap; no preview/native expansion by default |
| #132 | seek/cache/timeshift-adjacent scope outside core live alpha |

---

# 8. Explicit non-goals before `0.1.0-alpha.1`

Следующие направления не входят в critical path без отдельного доказанного blocker:

- Rust / UniFFI;
- libmpv / second playback engine;
- FFmpeg extension как default dependency;
- Xtream / Stalker / Ministra;
- catch-up / timeshift / DVR;
- VOD;
- backup/restore;
- QR/local-web onboarding;
- seek/cache subsystem;
- adaptive buffering tuning;
- preview/miniplayer;
- multi-profile;
- global Redux/MVI state store;
- custom global focus engine;
- full KMP database;
- cloud sync;
- Fire TV certification;
- mandatory repeated 50k performance series.

---

# 9. Final Definition of Done для private alpha

## Product

- Source onboarding и secure credential boundary работают.
- M3U/XMLTV import сохраняет atomic revision semantics.
- Channels/Favorites/Recent доступны на больших каталогах без full UI materialization.
- Search S5 сохраняет bounded top-N semantics.
- Guide S6 использует bounded channel/time windows и complete TV navigation contract.
- Player запускает channel через service-owned identity-only boundary.
- Bounded recovery завершается deterministic terminal result.
- Doctor показывает redacted playback/source diagnostics.

## TV interaction

- D-pad traversal не имеет traps.
- Short/long/repeat semantics определены и проверены.
- Focus/selected/playing/disabled визуально различимы.
- Player controls hidden by default и имеют корректный Back contract.
- 720p/1080p layouts/reachability пригодны.
- reduced motion не ухудшает state feedback.

## Reliability

- Activity recreation не создаёт второго player/retry owner.
- Late callbacks/stale workers не меняют новый generation/revision.
- reboot/package replace не создают duplicate scheduled refresh work.
- upgrade lineage сохраняет допустимые Room/Keystore данные.

## Security/privacy

- secrets/locators/raw exceptions отсутствуют в logs/navigation/saved state/Doctor export/evidence.
- signing material не сохраняется на persistent runner после release job.
- evidence связано с exact release commit.

## Performance/profile

- release CUJs executable;
- Baseline Profile создан из representative CUJs, упакован/потреблён minified release;
- physical device подтверждает user-visible performance без emulator-derived absolute claims;
- 50k stress evidence не блокирует release при отсутствии конкретного unresolved performance defect.

## Release

- signed APK;
- checksums;
- dependency/SBOM + licenses;
- mapping/provenance;
- two-clean-build comparison;
- same-key seed upgrade proof;
- API26/API36 functional evidence;
- API37 target-behavior evidence при доступном environment, либо явный documented availability gap;
- physical Android/Google TV journey;
- private GitHub pre-release с tested-device scope и known limitations.

---

# 10. Мета-ссылки: MuxTV repository sources

## Current source / plans / truth

- Current main commit: https://github.com/MuxTV/Muxtv/commit/1249624db5010e8140814a56553ea194c6d25d66
- Canonical ExecPlan: https://github.com/MuxTV/Muxtv/blob/main/docs/superpowers/plans/2026-08-08-mvp-alpha-1-execution.md
- Current-state surface: https://github.com/MuxTV/Muxtv/blob/main/.work/CURRENT-STATE.md
- Machine status: https://github.com/MuxTV/Muxtv/blob/main/.work/meta/status.yaml
- Roadmap: https://github.com/MuxTV/Muxtv/blob/main/.work/ROADMAP.md
- Version catalog: https://github.com/MuxTV/Muxtv/blob/main/gradle/libs.versions.toml
- App build: https://github.com/MuxTV/Muxtv/blob/main/app/tv/build.gradle.kts
- Manifest: https://github.com/MuxTV/Muxtv/blob/main/app/tv/src/main/AndroidManifest.xml

## Accepted Search evidence

- PR #159 — S5 measurement foundation: https://github.com/MuxTV/Muxtv/pull/159
- PR #160 — published-results refresh optimization: https://github.com/MuxTV/Muxtv/pull/160
- Long self-hosted run `31537375406`: https://github.com/MuxTV/Muxtv/actions/runs/31537375406

## Current Guide implementation

- `RoomGuideWindowRepository.kt`: https://github.com/MuxTV/Muxtv/blob/main/core/database/src/main/kotlin/app/muxtv/database/RoomGuideWindowRepository.kt
- `GuideViewModel.kt`: https://github.com/MuxTV/Muxtv/blob/main/feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideViewModel.kt
- `GuideViewportPolicy.kt`: https://github.com/MuxTV/Muxtv/blob/main/feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideViewportPolicy.kt
- `GuideRoute.kt`: https://github.com/MuxTV/Muxtv/blob/main/feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideRoute.kt

## Current Player/lifecycle implementation

- `PlayerRoute.kt`: https://github.com/MuxTV/Muxtv/blob/main/feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt
- `MuxTvApplication.kt`: https://github.com/MuxTV/Muxtv/blob/main/app/tv/src/main/kotlin/app/muxtv/MuxTvApplication.kt

## CI

- Self-hosted validation: https://github.com/MuxTV/Muxtv/blob/main/.github/workflows/self-hosted-validation.yml
- Android TV Product Matrix: https://github.com/MuxTV/Muxtv/blob/main/.github/workflows/android-tv-product-device-matrix.yml
- Database Matrix: https://github.com/MuxTV/Muxtv/blob/main/.github/workflows/database-migration-device-matrix.yml
- Heavy M3U evidence: https://github.com/MuxTV/Muxtv/blob/main/.github/workflows/focused-m3u-evidence.yml

## Issues directly referenced

- #27 Performance/corpus: https://github.com/MuxTV/Muxtv/issues/27
- #30 Recovery/Doctor: https://github.com/MuxTV/Muxtv/issues/30
- #31 Release hardening: https://github.com/MuxTV/Muxtv/issues/31
- #33 TV UX: https://github.com/MuxTV/Muxtv/issues/33
- #93 Lounge Light: https://github.com/MuxTV/Muxtv/issues/93
- #100 HTTP validators: https://github.com/MuxTV/Muxtv/issues/100
- #101 DB suites/CI: https://github.com/MuxTV/Muxtv/issues/101
- #109 Buffer policy: https://github.com/MuxTV/Muxtv/issues/109
- #111 Remote interaction: https://github.com/MuxTV/Muxtv/issues/111
- #112 Provider adapters: https://github.com/MuxTV/Muxtv/issues/112
- #113 Backup/restore: https://github.com/MuxTV/Muxtv/issues/113
- #115 Preview/miniplayer: https://github.com/MuxTV/Muxtv/issues/115
- #117 FFmpeg fallback: https://github.com/MuxTV/Muxtv/issues/117
- #118 Reboot/unlock lifecycle: https://github.com/MuxTV/Muxtv/issues/118
- #132 Seek/cache: https://github.com/MuxTV/Muxtv/issues/132
- #141 Artifact upload: https://github.com/MuxTV/Muxtv/issues/141
- #144 CI routing: https://github.com/MuxTV/Muxtv/issues/144
- #146 Room3 3.0.1: https://github.com/MuxTV/Muxtv/issues/146

---

# 11. Официальные внешние источники, проверенные 2026-08-13

## Android / AndroidX

- AndroidX versions: https://developer.android.com/jetpack/androidx/versions
- AndroidX stable channel: https://developer.android.com/jetpack/androidx/versions/stable-channel
- Room3 release notes: https://developer.android.com/jetpack/androidx/releases/room3
- Media3 release notes: https://developer.android.com/jetpack/androidx/releases/media3
- Create Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
- Configure Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
- Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
- Android TV focus system: https://developer.android.com/design/ui/tv/guides/styles/focus-system
- Direct Boot: https://developer.android.com/privacy-and-security/direct-boot
- `UserManager.isUserUnlocked`: https://developer.android.com/reference/android/os/UserManager
- Android 17 SDK/API37 setup: https://developer.android.com/about/versions/17/setup-sdk
- Android 17 target behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android 17 all-app behavior changes: https://developer.android.com/about/versions/17/behavior-changes-all

## GitHub Actions

- Deployments and environments: https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments
- Deployment environments: https://docs.github.com/en/actions/concepts/workflows-and-actions/deployment-environments
- Self-hosted runners: https://docs.github.com/actions/concepts/runners/about-self-hosted-runners
- Self-hosted runners reference / ephemeral guidance: https://docs.github.com/en/enterprise-cloud@latest/actions/reference/runners/self-hosted-runners
- Workflow syntax / concurrency: https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax

---

# 12. Decision summary

1. **Search S5 считается завершённым на `main@1249624...`; повторный 50k timed Search не является prerequisite S6.**
2. **Первый следующий product package — Guide S6**, но только как closure существующей bounded architecture.
3. **После S6 основными alpha blockers становятся Player overlay/Doctor residual, remote TV semantics, lifecycle #118, Baseline Profile/release evidence, signing и physical RC.**
4. **Heavy 5x10k+5x50k workflow должен быть stress/manual lane**, а не автоматическим main-push critical path.
5. **Room3 3.0.1 — допустимый isolated stable patch; Media3 1.10.1 остаётся stable и не требует перехода на 1.11 RC.**
6. **API37 compatibility evidence добавляется из-за фактического `targetSdk=37`**, availability-conditioned и без замены API36 Android TV gate.
7. **Release signing policy учитывает реальные возможности GitHub plan для private repository** и не предполагает required reviewers там, где GitHub их не предоставляет.
8. **Новые engines/native bridges/provider families остаются вне closed-alpha critical path.**
