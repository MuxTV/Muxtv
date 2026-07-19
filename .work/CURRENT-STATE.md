---
status: accepted
last_reviewed: 2026-07-19
source_commit: ad42cfeaf2e330be1eb2f766dafd85f7be39ae55
---

# Текущее состояние

## Факты

- Репозиторий: `MuxTV/Muxtv`.
- Видимость: private.
- Default branch: `main`.
- Лицензия: BSD 3-Clause.
- Исходное состояние: только `.gitignore`, `LICENSE` и однострочный `README.md`.
- Код приложения, Gradle wrapper, CI, tests и releases отсутствуют.
- Текущая рабочая ветка документации: `docs/architecture-foundation`.

## Принятые решения

- Android TV/Google TV/Fire TV — первая платформа.
- Kotlin + Compose for TV — основной UI stack.
- Media3 — основной playback engine.
- Room 3/SQLite — каталог и EPG.
- Kotlin Multiplatform — выборочное shared core, без общего TV UI.
- Rust/UniFFI и libmpv — optional paths после benchmarks.
- Вся внутренняя документация и metadata располагаются в `.work`.

## Следующий проверяемый результат

Phase 00 должна закончиться сборкой минимального APK, который:

1. запускается на Android TV emulator;
2. отображает TV-first shell с корректным focus;
3. открывает пустой Live TV screen;
4. проходит unit, lint и screenshot checks;
5. публикует debug artifact из GitHub Actions.

До появления этого результата любые описания функциональности являются целевой спецификацией, а не реализованной возможностью.