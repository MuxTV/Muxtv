---
status: reviewed_snapshot
last_reviewed: 2026-08-28
architecture_version: 2
# Compatibility alias: reviewed snapshot, not dynamic HEAD.
implementation_source_commit: 4a6634f51cb03f90708b7d1f02ff97632515d150
reviewed_main_commit: 4a6634f51cb03f90708b7d1f02ff97632515d150
live_state_authority: git
---

# Текущее состояние

## Что означает этот документ

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он проверен против принятого `main@4a6634f51cb03f90708b7d1f02ff97632515d150` после GitHub-hosted CI migration (#211) и принятого U1 TV shell/scale correction (#213).

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как «текущее»: оно может измениться без единого commit и поэтому не является versioned repository truth.

`implementation_source_commit` сохранён как compatibility alias и равен `reviewed_main_commit`. Он **не означает**, что live HEAD обязан совпадать с этим SHA; descendant branch является нормальным `ancestor` drift.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Reviewed snapshot включает закрытый Source/Catalog/EPG/Search/Guide контур, service-owned Media3 playback/recovery и semantic seek authority, Doctor Lite, внешний HTTP/HTTPS playback, EP-08 progressive resilience evidence, U0-characterized и U1-corrected Lounge Light TV shell/focus behavior, dependency-aware architecture guards, GitHub-hosted exact-head CI и **exact two-AVD repository/runtime contract**.

U0/#188/#189 и U1/#212/#213 больше не являются текущим critical path. Следующий обязательный stabilization owner — **M0 measurement correctness (#178 under #27)**. Новые DB/performance/buffer/cache conclusions и tuning не должны опережать M0.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, **public**, BSD 3-Clause.
- Reviewed main: `4a6634f51cb03f90708b7d1f02ff97632515d150` — PR #213, принятый U1 correction поверх GitHub-hosted CI baseline #211.
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: нормативная v2; implementation progress не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: v10.
- Gradle graph snapshot: **28** included projects плюс included build `build-logic`.
- CI: standard GitHub-hosted Windows/Linux runners; host validation/lint on hosted runners; Android TV instrumentation on hosted Linux/KVM; risk-routed focused/device/database/measurement lanes; no supported workflow depends on repository self-hosted runners.
- Android TV AVD policy: repository owns **ровно две** canonical identities — `MuxTV_TV_OLD_API26` и `MuxTV_TV_CURRENT_API36`. Дополнительные low-RAM/720p/mainstream/benchmark AVD identities не создаются; display/density/runtime profiles проверяются на этих же двух AVD.

## Реализованный продуктовый контур

### Source, catalog, EPG, Search и Guide

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U/XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- Channels Room Paging с Favorites/Recent и deterministic focus restoration;
- bounded Unicode Search top-N, debounce/cancellation/retry и canonical Player/Back restoration;
- Guide presentation projection вне Compose и deterministic `READY` / `NO_GUIDE` / `SOURCE_CONFLICT` states.

### Playback и external playback

- один process-owned `MediaSessionService`/ExoPlayer;
- service-owned first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- external `ACTION_VIEW` HTTP/HTTPS через тот же playback service;
- opaque process-local external lease, exact-origin cleartext approval и sanitized external metadata;
- runtime `ACCESS_LOCAL_NETWORK` boundary для API37+;
- shared catalog/external TV player surface;
- audio/subtitle selectors и transient seek HUD;
- EP-08: stock Media3 progressive/retry/range/no-range evidence и Android D-pad non-terminal external seek after first frame;
- MediaSession callback result contract и external D-pad lint boundary приняты;
- final seek mutation/coalescing authority принадлежит playback service; UI сохраняет только presentation/provisional behavior и не является вторым player-mutation owner.

### Lounge Light TV — U0/U1 accepted state

U0 characterization использовала immutable A/B/C refs и один `MuxTV_TV_CURRENT_API36` с representative `1920x1080@320`, representative `1280x720@213` и compact-stress `1280x720@320` profiles. Она доказала, что B/C permanently reserved expanded rail width and shifted shared destination content relative to A, а также отдельно зафиксировала global Lounge Light scale shrink.

PR #213 принят как минимальный U1 correction:

- destination content reserves `railCollapsed = 88dp` whenever rail is visible;
- rail visual width is transient `88dp` unfocused -> `138dp` while rail owns focus;
- selected destination state is independent from focus state;
- rail expansion does not reflow destination content;
- Navigation3/focusRestorer/Back-to-origin behavior preserved;
- reduced-motion disables animated transition via snap behavior;
- restored only U0-proven scale values: focus outline `3dp`, section gap `40dp`, Home card `300x140dp`, hero `48sp`, section title `26sp`, card title `20sp`, metadata `15sp`;
- no Home-local compensation and no PR #180 CTA policy was adopted without separate representative evidence;
- exact-head Hosted CI, App TV lint, API36 focused-device U1 profiles and Hosted validation passed before merge.

### Diagnostics, measurement и release foundation

- Doctor Lite presentation/export без secrets;
- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH и Macrobenchmark/Baseline Profile foundation;
- release identity, R8/resource optimization и signing/evidence contracts;
- GitHub-hosted exact-source-head provenance and fail-closed Android test-result validation;
- dependency-aware architecture guards;
- reviewed-snapshot/live-state separation;
- canonical API26/API36 reuse instead of AVD proliferation.

## Последние принятые этапы reviewed snapshot

- PR #175 / `2302c114...` — single service-owned seek authority;
- PR #176 / `d0133e9f...` — evidence publication retry boundary;
- PR #177 / `c9a84034...` — unlock-gated CE startup implementation;
- PR #183 / `3fba522c...` — Media3 UnstableApi lint boundary;
- PR #185 / `e208076d...` — external D-pad lint boundary;
- PR #181 / `5aa9c108...` — exact two-AVD repository/runtime contract;
- PR #206 / `a8c579cf...` — accepted stabilization truth sync;
- PR #211 / `c038ef5d...` — public-repository migration to GitHub-hosted CI;
- U0 / #188 / PR #189 head `7a332bbd...` — completed characterization provenance, intentionally closed unmerged after evidence consumption;
- PR #213 / `4a6634f5...` — accepted U1 transient rail geometry + Lounge Light scale correction.

Порядок списка отражает причинную стабилизационную цепочку, а не числовую сортировку PR.

## Следующая последовательность перед MVP 0.1 alpha

1. **M0 — measurement correctness (#178 under #27).** Restack/rebuild measurement-only delta onto accepted main; prove selective Search result boundaries are asserted from the actually published result set; close measurement workflow trigger ownership; obtain exact-head hosted validation + measurement correctness evidence.
2. **Architecture boundary gate before provider expansion (#202 then #201).** First move Sources behind stable catalog/source-management ports; then remove Media3 implementation leakage from `feature:player`, while preserving current WorkManager/Room run-token and service-owned player/seek ownership.
3. **Product breadth promoted by #205/#184.** After the architecture gate, implement the minimum provider-capability seam, exactly one Xtream Live vertical slice, then first-class provider catch-up intent/resolution. This is promoted ahead of the old late provider-only sequencing because the user-visible IPTV loop is now a larger maturity gap than additional control-plane sophistication.
4. **Keep expensive provider/storage scope late.** Stalker/Ministra, local timeshift, DVR, multiview, executable extensions and alternate player engines remain separate later decisions and are not pulled into the first provider train.
5. **Observability preparation (#191/#192/#193) may proceed in parallel host-first** only while it does not change product/performance semantics or block M0/product breadth.
6. **Dependency modernization after stabilization.** PR #190 remains combined compatibility evidence only; final dependency changes must be isolated owner PRs.
7. **Alpha release evidence (#31).** API37 private-LAN smoke, canonical API26/API36 release matrix, Baseline Profile/CUJ, signing/SBOM and physical current/weak TV evidence for hardware-specific claims.

Detailed execution order: `docs/superpowers/plans/2026-08-28-post-u1-stabilization-execution.md`.

## #118 lifecycle disposition

PR #177 accepted the user-unlocked CE startup implementation. Remaining real locked boot -> unlock, reboot/package-replace and physical-device lifecycle evidence is **release qualification**, already explicitly owned by open #31. The implementation issue may therefore remain closed; this snapshot does not treat those operational cases as already verified.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Absolute performance claims требуют отдельного hardware/release evidence.

## Известные gaps reviewed snapshot

- M0 measurement correctness (#178) ещё не принят;
- confirmed architecture boundary leaks in Sources (#202) and Player (#201) remain before provider expansion;
- source-refresh Doctor diagnostics residual (#30);
- WorkManager/Tracing/OkHttp observability owners #191/#192/#193 не являются полностью accepted implementation;
- provider capability / Xtream Live / provider catch-up product path from #184/#205 not yet implemented;
- Baseline Profile/CUJ closure;
- signing/SBOM/physical-device release evidence (#31), including residual #118 lifecycle qualification;
- API37 private-LAN permission smoke.

## Live-state protocol

Перед любой новой execution-сессией:

1. получить exact Git `HEAD`, branch и dirty state из checkout;
2. сравнить exact HEAD с `reviewed_main_commit` и явно классифицировать relation (`exact`, `ancestor`, `diverged`, `missing`);
3. получить PR/Issue state из GitHub, если он нужен для задачи;
4. не использовать старые remote `tmp/*`, `backup/*`, `rebuild/*` как execution base только потому, что они существуют;
5. evidence считать действительным только для точного SHA, на котором оно было получено;
6. не создавать третий MuxTV AVD: API/profile/display variants должны использовать canonical API26/API36 identities.

`tools/ci/Get-RepositoryLiveState.ps1` является offline Git-reader для пунктов 1–2. GitHub coordination state остаётся отдельным live source.

## Evidence limits

Canonical API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts. Display/density variants на API36 могут валидировать TV geometry/focus contracts без отдельного AVD. Эти gates **не доказывают** vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Эти claims требуют physical-device evidence.
