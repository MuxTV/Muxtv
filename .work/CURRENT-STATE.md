---
status: accepted
last_reviewed: 2026-08-15
architecture_version: 2
implementation_source_commit: 9eb30068cf88264b70386dab8be86fb80668db1a
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый `main` заканчивается PR #166 (`9eb30068cf88264b70386dab8be86fb80668db1a`) и содержит закрытый MVP-контур S0–S5, принятый Guide S6, external-player EP-01..07, repository-truth contract и доказуемую GitHub/local hygiene.

Текущая работа не считается принятой до exact-head evidence и merge:

- PR #167 — EP-08 TorrServer/progressive resilience evidence;
- PR #168 — M6-R Lounge Light TV redesign + remote/accessibility corrections;
- PR #169 — dependency-based architecture guard hardening;
- PR #170 — Android 17 `ACCESS_LOCAL_NETWORK` boundary correction.

PR #168 остаётся **active/review**, а не частью accepted implementation: его тема, overlay-only rail, Home/Channels/Guide/Search/Settings presentation, focus/reachability fixes и icon/brand polish не должны описываться как main до merge.

## Принятая база

- Repository: MuxTV/Muxtv, default branch `main`, private, BSD 3-Clause.
- Accepted implementation source: `main@9eb30068cf88264b70386dab8be86fb80668db1a` (PR #166).
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=0.1.0-alpha.1, minSdk=26, target/compile API37.
- Architecture: нормативная v2; продвижение implementation не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3.
- Room schema: v10.
- CI: host Fast/Full, Product API26/API36, Database API26/API36, variance/measurement и benchmark-foundation lanes; API37/physical-device claims остаются отдельным release evidence.

## Реализованный product contour на accepted main

### Source, catalog и EPG

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U и XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- Guide S6 presentation/focus closure без Paging3/full-guide materialization/schema bump;
- active/current-revision + selected-profile-visible truth contract.

### TV surfaces и playback

- Home, Channels, Guide, Search, Player, Sources и Doctor routes;
- Channels Room Paging с bounded loaded window, Favorites, Recent, Now/Next и stable focus;
- bounded Unicode Search top-N: 100 результатов по умолчанию, максимум 200, явный `isTruncated`;
- Search debounce/cancellation/retry, Player/Back query и canonical focus restoration;
- service-owned Media3 playback и first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- Doctor Lite presentation/export без secrets;
- external `ACTION_VIEW` playback через тот же service-owned player (EP-01..03);
- shared TV player surface, audio/subtitle selectors и coalesced seek/HUD (EP-04..07).

### Measurement и release foundation

- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH, Macrobenchmark/Baseline Profile producer foundation;
- release identity, R8/resource optimization и repository-owned signing/evidence contracts;
- self-hosted runner preflight, cleanup и exact-source-head provenance.

## Последние принятые этапы

- PR #149 — MVP/CI contract;
- PR #150 — service-owned bounded playback recovery;
- PR #151 — bounded redacted playback observations;
- PR #152 — Doctor Lite;
- PR #153 — alpha identity и release optimization;
- PR #154 — closed-MVP truth sync;
- PR #155 — self-hosted runner hardening;
- PR #156 — measurement foundation;
- PR #157 — Channels Paging;
- PR #158 — repository truth contract и GitHub/local hygiene;
- PR #159 — S5 measurement baseline;
- PR #160 — S5 published-results refresh optimization;
- PR #161 — repository-truth / 50k policy correction;
- PR #162 — Guide S6 bounded presentation closure;
- PR #163 — closed without merge; no accepted implementation delta;
- PR #164 — real-time Paging invalidation test repair;
- PR #165 — external-player EP-01..03 plus accepted shared overlay mechanics;
- PR #166 — shared TV player surface EP-04..07.

## Активная последовательность

1. EP-08/#167 — observation-driven progressive/TorrServer evidence without production buffer/seek-policy tuning before evidence.
2. M6-R/#168 — Lounge Light shell and daily surfaces, remote focus/accessibility, bounded settings details and design-reference polish; exact-head CI/device evidence required before acceptance.
3. #169/#170 — focused architecture-guard and Android17 LAN-permission corrections; keep them small and independent.
4. M4-R residual — terminal recovery presentation and source-refresh Doctor diagnostics only; overlay mechanics are already accepted through #165/#166.
5. L1 — lifecycle/reboot/package-replace contract (#118) без расширения Direct Boot scope.
6. D1 — dependency hardening/freeze after focused dependency evidence.
7. M3-C — Baseline Profile/CUJ closure and performance evidence.
8. M7-R — signing, artifact provenance and release evidence; RC includes API37 smoke and physical Android/Google TV gate.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution **не является** обязательным PR, S6 или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Claim на large catalog подтверждается bounded architecture и reachability/correctness evidence; absolute performance claims относятся к physical release evidence.

## Открытые дорожки

- EP-08/#167 — progressive resilience evidence.
- M4-R/#30/#33 — recovery terminal UX и source-refresh Doctor diagnostics.
- M6-R/#33/#93/#111 — Lounge Light shell, accessibility и remote interaction (PR #168 active).
- #169 — architecture dependency guard hardening.
- #170 — Android17 local-network runtime permission boundary.
- L1/#118 — reboot/unlock/package-replace WorkManager lifecycle.
- M3-C/M7-R/#27/#31/#146 — Baseline Profile, release engineering, signing и physical-device evidence.
- #100/#101/#109/#112/#113/#115/#117/#132/#141/#144 — backlog вне closed-alpha critical path.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts, но не доказывают Android17 runtime-permission UX. Emulator evidence также не доказывает vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Такие claims требуют соответствующего API37/physical-device evidence.
