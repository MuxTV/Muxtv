---
status: accepted
last_reviewed: 2026-08-11
architecture_version: 2
implementation_source_commit: 6e852d364db6904e80f87deb9deaba58ec58025a
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый main заканчивается PR #157 и содержит закрытый MVP-контур S0–S4: CI/release contracts, bounded playback recovery, redacted Doctor Lite, measurement foundation и Room-backed Channels Paging.

Открытых PR на момент ревью нет. Следующий последовательный пакет — repository truth/hygiene, затем Search S5 measurement baseline и Search S5 top-N optimization.

## Принятая база

- Repository: MuxTV/Muxtv, default branch main, private, BSD 3-Clause.
- Android application: app.muxtv.tv, versionCode=1001, versionName=0.1.0-alpha.1, minSdk=26.
- Architecture: нормативная v2; продвижение implementation не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3.
- Room schema: v10.
- Фактический Gradle graph: 27 модулей плюс included build build-logic.
- CI: host Fast/Full, Product API26/API36, Database API26/API36, variance/measurement и benchmark gates.

## Реализованный продуктовый контур

### Source, catalog и EPG

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U и XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- active/current-revision + selected-profile-visible truth contract.

### TV surfaces

- Home, Channels, Guide, Search, Player, Sources и Doctor routes;
- Channels Room Paging с bounded loaded window, Favorites, Recent, Now/Next и stable focus;
- bounded Unicode Search top-N: 100 результатов по умолчанию, максимум 200, явный isTruncated;
- Search debounce/cancellation/retry, Player/Back query и canonical focus restoration;
- service-owned Media3 playback и first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- Doctor Lite presentation/export без secrets.

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
- PR #154 — предыдущая closed-MVP truth sync;
- PR #155 — self-hosted runner hardening;
- PR #156 — measurement foundation;
- PR #157 — Channels Paging.

## Активная последовательность

1. Repository truth и доказуемая local/remote hygiene; каждый исторический PR классифицируется, но комментарии добавляются только при реальной metadata-несогласованности.
2. Search S5 baseline на 50k active channels с current/next EPG, stage timings и query plans.
3. Search S5 top-N optimization без Paging/API/schema/dependency changes:
   - удалить глобальный programme-boundary scan;
   - вычислять boundary только по опубликованным top-N rows;
   - не выполнять EPG/boundary работу для пустого результата;
   - вынести готовые labels/result IDs из Compose;
   - сохранить debounce, cancellation, retry, stale-generation и truncation.
4. Guide S6 и оставшиеся M3/M4/M6/M7 gates продолжаются только после принятого S5 evidence.

## Открытые дорожки

- M3/#27 — performance baselines и measurement closure.
- M4/#30/#109 — remaining Player recovery UX, source diagnostics и measured buffering.
- M6/#33/#93/#111/#115 — Lounge Light, accessibility и remote interaction.
- M7/#31/#101/#141/#144/#146 — CI reliability, Room patch, signing/release и physical-device evidence.
- #100/#113/#117/#118/#132 — отдельные backlog-пакеты вне S5.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Такие claims требуют physical-device evidence.
