---
status: accepted
date: 2026-07-19
deciders: [MuxTV]
---

# ADR-0001: Android TV-first и нативный Kotlin stack

## Контекст

Проект должен давать премиальный TV UX, стабильный playback, корректный D-pad focus и распространяться бесплатно через GitHub Releases. Рассматривались Kotlin/Compose for TV, Flutter, Rust UI, web/Electron, .NET MAUI и Avalonia.

## Решение

- основной клиент: native Android application;
- язык: Kotlin 2.4.10;
- UI: Compose for TV + собственный design system;
- navigation: Navigation 3;
- playback: Media3 за внутренним contract;
- storage: Room 3/SQLite;
- DI: Dagger Hilt;
- background jobs: WorkManager;
- local phone control: embedded Ktor server;
- first distribution: signed APK через GitHub Releases.

## Причины

- официальные TV components и focus behavior;
- прямой доступ к Media3, MediaSession, PackageInstaller и device codecs;
- меньше platform bridge failures, чем у Flutter/web/.NET stacks;
- возможность оптимизировать weak TV hardware;
- официальный production-ready Android/Kotlin ecosystem.

## Последствия

Положительные:

- лучший контроль UX и playback;
- предсказуемое профилирование;
- простой доступ к Android TV APIs.

Отрицательные:

- Tizen/webOS требуют отдельных клиентов;
- desktop UI нельзя получить автоматически;
- Android-specific code должен оставаться за platform boundary.

## Отвергнутые варианты

- Flutter как основной TV client: больше ручной работы с focus/platform integration.
- Rust UI: недостаточно зрелая TV ecosystem; высокий JNI/NDK cost.
- PWA/Electron: ограничения codecs, background playback и TV integration.
- MAUI/Avalonia: приемлемы для будущего companion, но не для основного Android TV клиента.