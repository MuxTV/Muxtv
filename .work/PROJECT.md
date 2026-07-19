---
status: accepted
last_reviewed: 2026-07-19
owners: [MuxTV]
---

# Проект MuxTV

## Миссия

Сделать бесплатное локальное TV-приложение, которое не просто воспроизводит сырой M3U, а автоматически собирает из нескольких источников устойчивый персональный каталог каналов, объясняет проблемы и остаётся удобным с обычного пульта.

## Продуктовая формула

**Красивый TV-first интерфейс + Smart Channels + TV Doctor + local-first управление + открытый исходный код.**

## Базовые принципы

1. **Пользователь управляет каналом, не URL.** Один логический канал может иметь несколько variants и EPG bindings.
2. **Надёжность важнее количества функций.** Live playback, zapping, focus, refresh integrity и recovery имеют высший приоритет.
3. **Простота снаружи, мощность внутри.** Simple mode скрывает технические параметры; expert mode раскрывает evidence and diagnostics.
4. **Local-first и privacy-first.** Нет обязательного аккаунта, облачной базы и скрытой телеметрии.
5. **Профили без навязанных ролей.** Чистая установка имеет один Основной профиль; остальные создаёт и называет пользователь. Restrictions/PIN are policies, not profile types.
6. **Открытость без хаоса.** Extensions use versioned least-privilege contracts and never direct internal database access.
7. **Измерения вместо преждевременной оптимизации.** Rust, libmpv, full KMP database и сложные caches принимаются only after prototype/benchmark ADR.
8. **Малые, проверяемые изменения.** Modularity exists for responsibility/test/dependency boundaries, not module count.
9. **Любая автоматизация объяснима и обратима.** Merge, EPG assignment, Doctor fix and source refresh expose evidence/provenance and safe rollback/undo.
10. **External projects are references, not blueprints.** Official docs are primary; popular repositories are reviewed critically with code/tests/issues/license.
11. **Remote data is untrusted.** Playlists, XMLTV, images, streams, extensions and updates pass scoped security/resource policies.
12. **No false claims.** `.work/CURRENT-STATE.md` distinguishes implemented facts from target specification.

## Поддерживаемые платформы

### Первая линия

- Android TV;
- Google TV;
- Fire TV and compatible AOSP TV devices.

One Android TV APK/core without mandatory Google Play Services is preferred where practical. Fire TV has separate physical-device compatibility gates.

### Отложено

- Android phone/tablet companion;
- Desktop companion/server;
- Samsung Tizen;
- LG webOS;
- Apple TV.

Tizen/webOS are outside initial scope because their native SDK, distribution, certification and sideload model differ substantially. Phone/desktop code is not a reason to force shared UI/database before product approval.

## Контентная модель

MuxTV is a media player/catalog manager. Project does not provide paid channels/subscriptions and does not guarantee legality/availability of third-party playlists. User connects own authorized sources or clearly permitted public catalogs.

A source being public/open in browser is not sufficient evidence of redistribution rights, stability or safety.

## Identity and profiles

- Installation owns sources, credentials, provider catalog, canonical channels, base EPG, health, extensions and update state.
- Profile owns favorites, history, ordering, numbering, custom groups, display/playback/accessibility preferences and optional restrictions.
- Exactly one primary profile always exists.
- There are no built-in profile types such as children/parents/guests.
- Profile names are labels chosen by user, not authorization roles.

## Термины

- **Installation** — one local MuxTV app data/security boundary on device.
- **Profile** — local viewer preferences and history inside installation.
- **Profile Policy** — optional PIN/content/schedule restrictions applicable to any profile.
- **Source** — playlist, provider account or external catalog configuration.
- **Source Revision** — immutable fetched/parsed candidate revision before/after commit.
- **Provider Channel** — source-specific channel record.
- **Canonical Channel / Smart Channel** — durable user-facing logical channel.
- **Stream Variant** — concrete provider resolver/locator and playback/request policy.
- **Health Snapshot** — evidence-scoped result of probe/observed playback.
- **User Overlay** — profile-specific display/order/favorite/hiding/EPG preferences over canonical data.
- **TV Doctor** — diagnostics plus previewable/undoable repair proposals.
- **Provenance** — explanation of source, algorithm version and evidence for value/decision.
- **Mutation Journal** — durable reversible catalog/Doctor operation history until compaction.

## Не-цели первого года

- собственный нелегальный каталог каналов;
- social network;
- mandatory cloud/account;
- torrent client;
- early full VOD movies/series platform;
- custom video decoder;
- arbitrary executable plugin marketplace/main-process code;
- full native support for every Smart TV OS;
- AI/LLM dependency for core matching/search/playback;
- feature parity race with commercial/all-in-one media centers.