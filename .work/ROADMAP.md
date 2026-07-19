---
status: accepted
last_reviewed: 2026-07-19
---

# Roadmap

## Phase 00 — Foundation

Результат: воспроизводимый Android TV проект с CI и архитектурными контрактами.

- Gradle version catalog и convention plugins;
- `app-tv`, `core-*`, `player-api`, `player-media3`, `provider-api`;
- Compose for TV design system;
- Navigation 3 shell;
- Room 3 schema v1 и migration tests;
- Hilt composition root;
- unit, screenshot, instrumentation, macrobenchmark modules;
- debug/release signing model;
- GitHub Actions и GitHub Releases draft pipeline.

## Phase 01 — Reliable Live TV

Результат: приложение импортирует M3U и стабильно воспроизводит каналы.

- потоковый M3U parser;
- source validation;
- группы, каналы, избранное и история;
- Media3 playback service;
- audio/subtitle track selection;
- channel zapping;
- понятная классификация playback errors;
- первые Baseline Profiles.

## Phase 02 — EPG and Personal Catalog

Результат: полноценное телевидение, а не список URL.

- XMLTV streaming parser;
- now/next и EPG grid;
- alias normalization и match confidence;
- user overlays;
- пользовательские номера, группы и скрытие;
- атомарный refresh источника;
- backup/restore schema v1.

## Phase 03 — Smart Channels

Результат: один логический канал объединяет несколько источников.

- duplicate candidate engine;
- canonical channel lifecycle;
- stream score;
- primary/reserve variants;
- автоматический failover;
- TV Doctor probe pipeline;
- preview и undo исправлений.

## Phase 04 — Mass-user UX

Результат: установка и настройка доступны обычному пользователю.

- QR onboarding;
- локальная web-панель;
- простой/экспертный режим;
- профили и parental control;
- accessibility presets;
- встроенная проверка GitHub Releases и безопасное обновление APK.

## Phase 05 — Providers and DVR

Результат: расширение источников после стабилизации core.

- Xtream provider;
- catch-up/timeshift normalization;
- DVR jobs и storage policy;
- declarative extension manifests;
- optional compatibility APK с libmpv;
- phone/desktop companion research.

## Release policy

- До `0.1.0` публичных обещаний совместимости нет.
- Начиная с `0.1.0`, database migrations и backup schema тестируются на каждом PR.
- `1.0.0` требует завершения Phase 00–04, device matrix и documented recovery flows.
- Новая крупная функция не принимается, если она ухудшает startup, zapping или memory budgets без согласованного ADR.