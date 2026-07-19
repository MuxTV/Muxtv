---
status: accepted
last_reviewed: 2026-07-19
---

# Quality gates

## Pull request gates

Каждый PR обязан пройти:

- Gradle configuration cache check;
- compile всех затронутых modules;
- unit tests;
- static analysis и Android lint;
- dependency/version catalog validation;
- architecture dependency tests;
- Room migration tests;
- parser corpus tests;
- screenshot tests для изменённых UI components;
- secret scan и dependency review.

## Release gates

Дополнительно:

- instrumented tests на emulator;
- Macrobenchmark на физическом reference device;
- Baseline Profile generation и проверка `baseline.prof` в APK;
- minified release build;
- APK signature verification;
- SBOM, SHA-256 checksums и release notes;
- clean install, upgrade и backup/restore scenarios;
- smoke test на Android TV, Google TV и Fire TV class device.

## Performance budgets

Начальные бюджеты фиксируются до feature growth и уточняются benchmark-ами:

| Метрика | Бюджет |
|---|---:|
| Cold start до интерактивного shell, reference device | p50 ≤ 1.5 s, p95 ≤ 2.5 s |
| Возврат из background | p95 ≤ 700 ms |
| Открытие списка каналов из cache | p95 ≤ 300 ms |
| Channel zap до first frame при доступном потоке | p50 ≤ 1.2 s, p95 ≤ 3.0 s |
| Sustained jank в rail navigation | < 2% frames |
| Импорт 10 000 M3U entries | ≤ 5 s, peak app heap ≤ 180 MB |
| XMLTV processing | streaming; peak heap не растёт линейно размеру файла |
| Утечка после 100 переключений каналов | отсутствует; heap возвращается к steady band |

Регрессия >10% требует объяснения; >20% блокирует merge без принятого ADR.

## Test pyramid

- Domain и parsers: быстрые JVM/common tests.
- Database: instrumented/hosted migration and transaction tests.
- Features: reducer/ViewModel tests с fake ports.
- UI: semantics + screenshot tests в состояниях default/focused/selected/loading/error.
- Playback orchestration: deterministic fake engine tests.
- Media3 adapter: instrumented tests с local test streams.
- End-to-end: небольшой набор critical user journeys.

## Device matrix

Минимальные классы:

1. слабый Android TV/AOSP, 2 GB RAM, API 26–28;
2. массовый Google TV, 2–3 GB RAM, API 30+;
3. современная 4K приставка, API 33+;
4. Fire TV device;
5. emulator для deterministic UI/DB tests.

## Observability

- structured local logs с redaction;
- bounded ring buffer;
- correlation IDs для import, refresh и playback session;
- export diagnostic package по явному действию;
- telemetry отсутствует по умолчанию;
- crash reporting допускается только opt-in и отдельным ADR.