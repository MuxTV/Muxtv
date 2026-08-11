---
status: accepted
last_reviewed: 2026-08-11
architecture_version: 2
---

# Целевая архитектура MuxTV

## 1. Архитектурный стиль

MuxTV строится как **модульный монолит Android TV-first** с однонаправленным потоком данных, единым источником истины, атомарными revision pipelines и изолированными контрактами для provider, playback, EPG, matching, diagnostics и extensions.

Начальная реализация остаётся в одном репозитории и одном APK. Модульность должна ускорять тестирование, замену adapters и контроль тяжёлых зависимостей, а не имитировать микросервисы.

## 2. Платформенная граница

### Android application

Отвечает за:

- Compose for TV UI, D-pad/focus и accessibility;
- Media3, MediaSession, audio focus, surfaces и device capabilities;
- Room/SQLite, WorkManager, PackageInstaller;
- локальный Ktor server и LAN pairing;
- lifecycle, Android permissions и platform integrations;
- Fire TV compatibility adapters.

### Platform-neutral core

Чистые Kotlin contracts/logic используются для:

- domain models и typed IDs;
- M3U/XMLTV parsing/normalization;
- duplicate/EPG matching и scoring;
- backup schema;
- search query interpretation;
- extension DTO/contracts без Android типов.

Kotlin Multiplatform допускается только там, где существует подтверждённый второй platform target или отдельный ADR. На ранних этапах pure Kotlin/JVM modules могут сохранять KMP-compatible границы без обязательного multiplatform build.

UI остаётся нативным Android TV. Общий TV UI на Compose Multiplatform в первой линии запрещён.

### Database boundary

Room/SQLite остаётся Android-first до появления второго клиента. Domain/features не импортируют Room, а storage реализует platform-neutral repository ports. Решение зафиксировано ADR-0003.

## 3. Слои и зависимости

```text
TV UI / Feature reducers
          ↓ intents, immutable state
Application use cases / coordinators
          ↓ domain ports
Domain models, policies and algorithms
          ↓ repository/engine/provider contracts
Adapters
          ↓
Room · OkHttp · Media3 · WorkManager · Ktor · PackageInstaller
```

Правила:

- `domain` не зависит от Android, Room, Media3, OkHttp, Ktor, Compose и DI;
- feature-модули не обращаются к DAO/network/player implementation напрямую;
- provider adapters не управляют UI и playback lifecycle;
- playback engine не знает M3U/Xtream и получает `ResolvedPlaybackRequest`;
- user overlays не перезаписываются при refresh;
- network/image/update clients не разделяют credentials автоматически;
- один компонент владеет каждым retry class, чтобы исключить multiplicative retries;
- automatic catalog mutations объяснимы, journaled и обратимы.

## 4. Состояние и конкурентность

- UI использует immutable `UiState`, intents и bounded side effects;
- Coroutines/Flow являются основным async/observability механизмом;
- source/EPG import работает через immutable revisions, staging и atomic commit;
- один source refresh имеет unique work key/lease;
- long work checkpointed and cancellable; incomplete work never becomes active;
- playback state живёт в process-scoped service/controller, not screen;
- player commands serialized;
- heavy parsing/matching uses bounded batches and temporary app-private storage;
- active catalog remains readable during refresh;
- post-commit indexing/probes are separate retryable jobs.

## 5. Данные и identity

Ключевые aggregates:

- `Source`, `SourceRevision`;
- `ProviderChannel`, `StreamVariant`;
- `CanonicalChannel`, memberships, aliases/tombstones;
- `UserChannelOverlay`, `UserGroup`;
- `EpgSource`, `EpgChannel`, `EpgProgram`, `EpgBinding`;
- `StreamHealthSnapshot`, `PlaybackAttempt`;
- `UserProfile`, `ProfilePolicy`;
- `ExtensionDescriptor`, grants;
- mutation/audit journals.

Provider data, canonical data, installation data и profile overlays хранятся раздельно. URL не является channel identity. Displayed metadata имеет provenance and priority. Merge/split/source refresh сохраняют stable IDs и user data.

Нормативные details: `architecture/domain-model.md`, `architecture/source-refresh.md`.

## 6. Профили

- чистая установка создаёт один Основной профиль;
- primary нельзя удалить, но можно переименовать;
- additional profiles создаёт/называет только пользователь;
- предустановленные роли `Дети`, `Родители`, `Гости` и `profileType` запрещены;
- favorites/history/order/UI/playback preferences — profile-scoped;
- sources/credentials/catalog/base EPG/health/extensions — installation-scoped;
- PIN/restrictions/schedules — independent `ProfilePolicy` для любого профиля;
- picker не показывается при единственном профиле.

Нормативные details: `specifications/profiles.md`, ADR-0004, `meta/profiles.yaml`.

## 7. Ingestion and EPG

M3U/XMLTV considered untrusted and processed:

```text
bounded fetch → secure decode → streaming parse → staging
 → validation/diff/matching → atomic commit → post-commit jobs
```

- raw metadata preserved separately from normalized values;
- external DTD/entities/XInclude disabled;
- archive/decompression/line/record limits;
- previous revision remains active after failure;
- timezone ambiguity never silently becomes UTC;
- EPG viewport queries are interval-bounded/lazy;
- manual EPG binding survives algorithm/source refresh.

Normative details: `specifications/m3u-ingestion.md`, `specifications/xmltv-processing.md`.

## 8. Playback runtime

Baseline engine — Media3 stable behind `PlaybackEngine`. `PlaybackService/Controller` owns:

- player/session/surface lifecycle;
- MediaSession/audio focus;
- variant resolution and bounded recovery;
- live edge/behind-window semantics;
- device capability evidence;
- stable error mapping and diagnostics.

Activity/Compose do not own/release player. Track selection uses semantic identity, not array index. State machine includes resolving, preparing, surface wait, playing/buffering/paused/seeking, recovering and terminal states.

Optional libmpv compatibility implementation requires separate ADR/benchmark and does not mix with Media3 orchestration.

Normative details: `architecture/playback-runtime.md`, `specifications/playback-errors.md`, `meta/playback-error-catalog.yaml`.

## 9. Smart Channels and TV Doctor

- candidate generation separated from merge;
- false-positive merge considered more harmful than missed duplicate;
- hard conflicts and manual reject/split block auto-merge;
- auto-merge disabled until labeled corpus proves required precision;
- all decisions carry evidence, confidence and algorithm version;
- merge/split/fixes previewed and undoable;
- stream health carries source, sample count, confidence, device/network scope;
- background probes have concurrency/thermal/network budgets and never degrade playback;
- scoring is transparent and calibrated, with hysteresis/cooldowns.

Normative details: `specifications/smart-channels.md`, `specifications/tv-doctor.md`, `meta/scoring-model.yaml`.

## 10. UI, design and focus

Compose for TV plus MuxTV Design System. Focus is primary cursor/state.

- all flows work with five-button D-pad and Back;
- stable-key focus restoration, including EPG channel/time context;
- selected/focused/pressed states distinct;
- premium visuals cannot delay input/start/zapping;
- high contrast, reduced motion and larger UI are profile settings;
- 720p/1080p/4K screenshot matrix;
- EPG uses custom synchronized/lazy timeline layout;
- no essential action requires vendor key or long press only.

Normative details: `design/focus-navigation.md`, `design/design-system.md`.

## 11. Local control

Embedded Ktor server starts only for active pairing/session. Phone setup uses one-time TV-visible token, explicit confirmation, short-lived scoped capabilities and no cloud account.

- existing credentials never returned;
- no arbitrary proxy/filesystem/package install endpoints;
- CSRF/origin/Host/rate/payload limits;
- long jobs run through durable application pipelines;
- local client cannot bypass active profile policy;
- service not permanently exposed by default.

Normative details: `specifications/local-control.md`.

## 12. Extensions

Order:

1. built-in adapters in repository;
2. declarative manifests/rules without executable code;
3. isolated companion APK through versioned Binder/AIDL capability contract.

Random DEX/JAR/JS/native code, shared database and direct credential/player access are forbidden. Extension outputs pass normal validation/staging pipelines.

## 13. Rust and native code

Rust is not required for MVP. `CatalogComputeEngine` has Kotlin implementation. Rust/UniFFI can be accepted only after benchmark shows material CPU/memory gain and packaging/startup/crash/security costs pass ADR gates.

Native libraries (libmpv/FFmpeg/Rust) expand ABI, supply-chain and crash surface and require separate release/security/device testing.

## 14. Security and network

- all remote data untrusted;
- per-source address scope and cleartext policy;
- localhost/link-local/private network blocked by default; LAN explicitly approved;
- DNS/redirect target revalidated;
- sensitive headers stripped cross-origin by default;
- no global TLS bypass;
- DTD/entities/archive bombs blocked;
- credentials opaque/redacted/excluded from backup by default;
- updater fixed to official repo/package/certificate;
- pairing/extensions use least privilege;
- telemetry/crash upload absent by default.

Normative details: `security/threat-model.md`, `security/network-and-source-policy.md`.

## 15. Release and update

GitHub Releases distribution uses fixed applicationId/signing identity, versionCode monotonicity, checksums/metadata/SBOM and Android package signature verification. User/system approval through PackageInstaller is mandatory. Nightly has separate applicationId. Update failure never blocks playback.

Normative details: `release/self-update-and-signing.md`.

## 16. Performance and quality

Performance gates cover:

- cold/interactive startup;
- cached catalog/channel/EPG navigation;
- import 1k/10k/100k/500k M3U;
- generated large XMLTV with bounded heap;
- zapping/first stable frame/failover;
- hour/day playback and 100/500 switches;
- weak Android TV, mass Google TV, Fire TV and high-end codec chain;
- focus correctness, screenshot/accessibility and fault injection.

Every number must name device/firmware/network/method/sample size. External streams supplement deterministic fixtures only.

Normative details: `quality/quality-gates.md`, `quality/benchmark-methodology.md`.

## 17. Evidence policy

Official platform/library documentation is primary. Popular repositories are reference evidence, not blueprints. Before adoption inspect current code/tests/issues/license and write MuxTV-specific rationale/ADR. Critical comparison is maintained in `research/reference-repositories.md`.
