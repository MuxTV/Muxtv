---
status: accepted
last_reviewed: 2026-07-19
architecture_version: 2
documentation_baseline_source_commit: 58648c3043a2dd46aaadfcd7ead33d13e8f916bf
---

# Текущее состояние

## Факты

- Репозиторий: `MuxTV/Muxtv`.
- Видимость: private.
- Default branch: `main`.
- Лицензия: BSD 3-Clause.
- Код приложения, Gradle wrapper, CI, tests, APK и releases пока отсутствуют.
- В репозитории существует принятая `.work`-система архитектуры, спецификаций, ADR, quality/security/release documents и машинно-читаемых метаданных.
- Документация описывает целевое поведение; она не является доказательством реализованной функции.
- Следующий этап — Phase 00 implementation, а не дальнейшее бесконтрольное расширение feature scope.

## Принятые решения

- Android TV, Google TV и Fire TV — первая линия платформ.
- Kotlin + native Compose for TV — UI baseline.
- Media3 — primary playback engine behind `PlaybackEngine`.
- Room/SQLite — Android-first storage behind repository ports; full KMP database deferred by ADR-0003.
- Pure Kotlin modules remain KMP-compatible, actual KMP conversion requires a real second target.
- Rust/UniFFI and libmpv are optional paths after benchmark/security ADR.
- Source/EPG updates use immutable revisions, staging and atomic commit.
- Provider data, canonical channels and profile overlays are separated.
- Clean installation creates one undeletable but renamable `Основной` profile.
- Additional profiles are created/named only by user; no built-in `Дети/Родители/Гости` types.
- PIN/restrictions are policies applicable to any profile.
- Smart Channel auto-merge remains disabled until labeled corpus proves precision gate.
- Automatic catalog/Doctor mutations require explanation, preview and undo.
- Remote playlists/XML/images/provider endpoints are untrusted and governed by scoped network/resource policies.
- GitHub Releases update trust is rooted in Android package signing identity and PackageInstaller confirmation.
- All internal project documentation/metadata lives in `.work`.

## Specification coverage

Accepted baseline covers:

1. profile and installation data scope;
2. catalog identity, merge/split and tombstones;
3. atomic source refresh;
4. M3U/XMLTV ingestion;
5. playback runtime, errors and recovery;
6. Smart Channels and TV Doctor;
7. D-pad/focus and premium TV design system;
8. threat/network/local-control security;
9. Fire TV compatibility;
10. GitHub self-update/signing;
11. benchmark/device/fault methodology;
12. critical review of popular reference repositories.

## Следующий проверяемый результат

Phase 00 должна закончиться minimal but real APK, который:

1. reproducibly builds with pinned toolchain;
2. launches on Android TV emulator and physical reference device;
3. shows TV-first shell with deterministic D-pad focus;
4. contains schema v1 with installation/profile scope and exactly one primary profile;
5. exposes tested Media3-independent playback contracts;
6. passes unit, lint, architecture, migration and screenshot checks;
7. produces first baseline benchmark report;
8. publishes a debug APK artifact from GitHub Actions.

До выполнения exit criteria проект остаётся на стадии specification/foundation.