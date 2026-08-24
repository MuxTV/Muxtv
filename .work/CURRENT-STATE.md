---
status: reviewed_snapshot
last_reviewed: 2026-08-24
architecture_version: 2
# Compatibility alias: reviewed snapshot, not dynamic HEAD.
implementation_source_commit: 5aa9c108cc63187d8066494fb30c73b82f4e0f97
reviewed_main_commit: 5aa9c108cc63187d8066494fb30c73b82f4e0f97
live_state_authority: git
---

# Текущее состояние

## Что означает этот документ

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он проверен против принятого `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` после merge PR #181/D0.

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние открытых PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как динамическая «истина».

`implementation_source_commit` сохранён как compatibility alias и равен `reviewed_main_commit`. Descendant branch является нормальным `ancestor` drift.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Функциональный Source/Catalog/EPG/Search/Guide/Player контур присутствует, service-owned Media3 playback/recovery принят, Doctor Lite и внешний HTTP/HTTPS playback реализованы. Основная текущая проблема не отсутствие базовых функций, а завершение доказуемой стабилизации UI/measurement/release evidence перед performance tuning.

Старое утверждение о dual seek ownership больше не является текущим долгом: PR #175 принят и merged как `2302c11441c85b8b5752d7f03cc5bc13be8c6d92`. Semantic relative/absolute/current-item seek сводится к одному service-owned `PlaybackSeekController`; UI не является вторым final mutation owner.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, private, BSD 3-Clause.
- Reviewed main: `5aa9c108cc63187d8066494fb30c73b82f4e0f97` — merge PR #181 / D0.
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: normative v2.
- Stack baseline на accepted main: Kotlin/Coroutines, Compose for TV, Navigation3, Hilt, Room3, WorkManager, OkHttp, Media3; dependency modernization staged separately and не считается принятой только потому, что существует branch/PR.
- Room schema: v10.
- Gradle graph: 27 modules plus included `build-logic`.
- CI: self-hosted host validation, risk-routed focused/product device lanes, measurement/benchmark/migration lanes and exact-source-head evidence contracts.

## Принятый Android TV device contract

PR #181/D0 установил repository infrastructure truth:

- persistent AVD identities are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`;
- API26 resolves exactly to `system-images;android-26;android-tv;x86`;
- API36 resolves exactly to `system-images;android-36;android-tv;x86_64`;
- fallback to another API is fail-closed;
- variance, benchmark, catalog measurement and player measurement reuse the same canonical identities;
- 720p/1080p/density/stress modes are configurations of those devices, not extra AVDs;
- repository lanes must not create low-RAM/mainstream/measurement/benchmark AVD identities.

Emulators prove API/lifecycle/Room/focus/MediaSession contracts only. Weak ARM, thermal, vendor MediaCodec, HDR, passthrough, Fire OS and absolute performance claims require physical-device evidence.

## Реализованный продуктовый контур

### Source, catalog, EPG, Search и Guide

- secure source URL policy, exact-origin HTTP approval and Keystore-backed credentials;
- bounded streaming M3U/XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication and previous-good retention;
- durable refresh ownership, cancellation and supersession;
- deterministic EPG matching, bounded Now/Next and Guide windows;
- Channels Room Paging with Favorites/Recent and focus restoration;
- bounded Unicode Search top-N, debounce/cancellation/retry and Player/Back restoration;
- Guide presentation projection outside Compose.

### Playback and external playback

- one process-owned `MediaSessionService` / ExoPlayer authority;
- service-owned first-rendered-frame success boundary;
- bounded same-channel recovery and redacted playback observations;
- external `ACTION_VIEW` HTTP/HTTPS through the same playback service;
- opaque process-local external lease, exact-origin cleartext approval and sanitized metadata;
- API37 `ACCESS_LOCAL_NETWORK` boundary;
- shared TV player surface, audio/subtitle selectors and transient seek HUD;
- PR #175: single service-owned semantic seek authority for private MuxTV input and standard current-item Media3 seek controls.

Residual seek work is measurement/diagnostics only: UI waiter/request churn under rapid repeats, typed seek failure observations and evidence-gated LoadControl/back-buffer/cache decisions. It is **not** dual mutation ownership.

### Background lifecycle

Issue #118 is completed: functional Room/Keystore refresh is not supported before user unlock; post-unlock WorkManager scheduling/reconciliation is idempotent. Do not add Direct-Boot credential/database state without a new approved design.

### Diagnostics, measurement and release foundation

- Doctor Lite playback presentation/export without secrets;
- deterministic 1k/10k/50k M3U corpus and repeated-series tooling;
- JMH and Macrobenchmark/Baseline Profile foundation;
- release identity and R8/resource optimization foundation;
- self-hosted runner preflight/cleanup and exact-source-head provenance;
- dependency-aware architecture guards;
- risk-based validation and exact two-AVD device harness.

Known measurement defect remains isolated in #178: the catalog measurement runner asserts an invalid global Search boundary although product Search publishes boundaries from actual published result rows. D0 evidence explicitly classified that failure outside the two-AVD infrastructure package.

## Последние принятые стабилизационные этапы

- PR #175 — single service-owned seek authority, merged as `2302c114...`;
- PR #183 — accepted lint/product prerequisite discovered by D0 validation;
- PR #185 — accepted player/lint prerequisite discovered by D0 validation;
- PR #181 — D0 exact two-AVD repository contract, merged as `5aa9c108...`.

Earlier accepted Source/EPG/Search/Guide/Playback/Doctor/Lounge work remains part of the functional baseline; this list highlights only the most recent stabilization changes.

## Текущая последовательность перед MVP 0.1 alpha

Canonical stabilization plan: `docs/superpowers/plans/2026-08-22-muxtv-stabilization-master-plan.md`.

1. **U0 — UI characterization.** Gather deterministic A/B/C geometry/focus evidence on the canonical API36 AVD without product UI changes.
2. **U1 — evidence-driven UI correction.** Change only the shared/root layout owner proven by U0 and revalidate focus/navigation/720p-on-same-AVD behavior.
3. **M0 / #178 — measurement correctness.** Restack onto the accepted post-U1 baseline and restore trustworthy measurement boundary/routing evidence.
4. **Performance/release decisions.** Only after M0 may DB/Search/parser/player tuning claims rely on the repaired measurement path.

Parallel evidence preparation is defined by `docs/superpowers/specs/2026-08-24-observability-modernization-design.md` and its implementation plan. Dedicated owners are #191 WorkManager diagnostics, #192 Tracing 2.0 and #193 OkHttp timings. Those workstreams may be prepared/host-tested without changing U0, but they do not override the U0->U1->M0 decision gate.

## Modernization / observability gaps

The latest-stack review found these gaps, none of which is permission for speculative tuning:

- WorkManager failure callbacks are not yet projected into bounded secret-safe diagnostics (#191);
- no repository-owned AndroidX Tracing 2.0 evidence boundary yet (#192);
- no OkHttp DNS/connect/TLS/TTFB/body phase timing yet (#193);
- Media3 analytics evidence must precede adaptive buffer policy (#109/#27);
- R8 Configuration Analyzer and sustained TV Macrobenchmark CUJs must be added to #31 release evidence;
- source conditional validators / HTTP 304 remain #100;
- Room3 pool/FTS5/WITHOUT ROWID are measurement candidates only after #178 (#196);
- Gradle 9.7 parallel configuration cache / Isolated Projects are non-blocking post-alpha experiments (#195).

## Explicit non-adoptions for current alpha train

- no Media3 PlayerPool / second player;
- no SimpleCache before #109 evidence;
- no blanket Room pool count changes;
- no FTS5 or `WITHOUT ROWID` migration merely because the API exists;
- no Compose Styles/experimental runtime flags as alpha baseline;
- no custom WorkManager coroutine context without contention evidence;
- no Direct-Boot secret/database store;
- no Rust/native parser or alternate playback engine without benchmark/ADR evidence;
- no third Android TV AVD.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution is manual synthetic stress/correctness evidence, not an unconditional PR/release gate. Absolute performance claims require controlled hardware/release evidence.

## Live-state protocol

Before execution:

1. resolve exact Git `HEAD`, branch and dirty state from checkout;
2. compare HEAD to `reviewed_main_commit` and classify relation (`exact`, `ancestor`, `diverged`, `missing`);
3. query GitHub for live PR/Issue state when needed;
4. do not select old `tmp/*`, `backup/*`, `rebuild/*` branches merely because they exist;
5. treat evidence as valid only for its exact SHA/environment.

`tools/ci/Get-RepositoryLiveState.ps1` is the offline Git reader for repository relation. GitHub coordination remains a separate live source.

## Evidence limits

API26/API36 emulator gates validate Android API, Room/migration, lifecycle, focus, MediaSession and database contracts. They do not prove vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network or absolute-performance behavior.
