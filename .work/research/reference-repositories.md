---
status: accepted
last_reviewed: 2026-07-19
owners: [architecture, research]
review_policy: references-not-blueprints
---

# Критический обзор reference repositories

## 1. Правило использования

Популярность, количество функций и звёзд не доказывают правильность архитектуры. Для каждого проекта фиксируются:

- какую конкретную проблему он помогает понять;
- какие решения подтверждены длительной эксплуатацией;
- какие ограничения/issue history видны;
- что MuxTV принимает;
- что MuxTV сознательно не копирует.

Код и документация проверяются вместе с лицензией, состоянием проекта, issues и официальной platform documentation.

## 2. Android official TV samples

### Репозитории

- `android/tv-samples`;
- official Compose for TV codelabs/catalog;
- `android/nowinandroid` для общей современной Android architecture/performance practices.

### Полезно

- актуальные TV components;
- D-pad/focus/accessibility patterns;
- Compose semantics/testing;
- Baseline Profiles, convention plugins и modularization examples;
- platform-correct lifecycle and UI APIs.

### Ограничения

- samples упрощены и не покрывают IPTV live semantics, огромный EPG, provider credentials, failover и слабые vendor devices;
- sample module graph не является обязательным шаблоном;
- визуальная демонстрация не доказывает performance на production data.

### MuxTV принимает

Official APIs и focus/accessibility behavior являются первичным источником. Product architecture, catalog and player orchestration проектируются отдельно.

## 3. AndroidX Media

### Репозиторий

- `androidx/media`.

### Полезно

- authoritative source Media3/ExoPlayer;
- live/HLS/DASH behavior;
- error codes, tracks, MediaSession, UI components;
- demos/tests for platform integration.

### Ограничения

- Media3 does not define MuxTV channel/domain/provider model;
- player retry mechanisms can conflict with outer retries if ownership is unclear;
- official demo cannot cover every broken IPTV stream/device codec.

### MuxTV принимает

Media3 stable baseline behind `PlaybackEngine`, explicit error mapping, one retry owner per failure class and physical-device tests.

## 4. Jellyfin Android TV

### Репозиторий

- `jellyfin/jellyfin-androidtv`.

### Сильные стороны

- mature Android TV/Fire TV deployment;
- broad device/codec/audio/subtitle exposure;
- release branching and translation process;
- real MediaSession/player/client lifecycle history;
- large issue corpus with actual TVs, boxes and AVR chains.

### Issue lessons

Observed reports include:

- playback crash from stale/invalid subtitle index;
- Activity/configuration recreation stopping playback;
- runtime decoder failure despite advertised compatibility;
- HDR/Dolby Vision/passthrough differences;
- long first-play black screen and device-specific regression;
- focus trapped in search;
- guide restoring wrong position;
- IPv6-only local server regression;
- resource-lifecycle complexity in player services.

### What MuxTV adopts

- player lifetime independent from screen;
- semantic track identity, not index;
- observed device capability registry;
- Fire TV as explicit test tier;
- release branches/build IDs and migration discipline;
- focus restoration and service lifecycle tests.

### What MuxTV does not copy

- Jellyfin's server-driven playback/transcoding model;
- legacy architecture accumulated over years;
- VOD/library requirements unrelated to Live TV MVP;
- individual workarounds without general evidence/expiry.

## 5. StreamVault IPTV

### Репозиторий

- `Davidona/StreamVault-IPTV`.

### Сильные стороны

- direct TV-first IPTV comparison;
- Kotlin, Compose, Room, Hilt, Media3;
- explicit modules `app/data/domain/player`;
- M3U/Xtream/Stalker/Jellyfin;
- QR provider pairing;
- EPG overrides, guide, timeshift, DVR, multiview;
- companion APK plugin concept;
- GitHub self-update.

### Critical risks visible in scope/issues

The project simultaneously covers Live TV, VOD, Series, downloads, DVR, multiview, several provider protocols, plugins, Cast and TV integrations. This is valuable for feature discovery but creates a high integration surface.

Issue examples reveal:

- 1080p buffering/green-screen/device playback problems;
- EPG `.xml.gz` parsing failures;
- foreign-key failure during EPG refresh;
- EPG settings crashes/invisible source state;
- partial provider sync with weak diagnostics;
- focused button contrast defect;
- backup restore screen without visible proceed action;
- multiple-provider limitations and long setup.

### What MuxTV adopts

- TV-first remote UX;
- QR/LAN configuration;
- provider/player contract separation;
- manual EPG override;
- diagnostics and compatibility controls;
- companion APK only as isolated future extension form;
- in-app GitHub release detection with stronger verification.

### What MuxTV rejects/defer

- VOD/Series as early scope;
- DVR/multiview/plugins before reliable catalog/playback;
- one monolithic feature expansion wave;
- non-commercial source-available license assumptions;
- source refresh that mutates active EPG without staging.

## 6. M3UAndroid

### Репозиторий

- `oxyroid/M3UAndroid`.

### Сильные стороны

- simple, practical, ad-free positioning;
- separate phone/TV application modules;
- dedicated parser included build;
- baseline profile and device benchmark modules;
- extension application and test infrastructure;
- GPL open-source distribution via GitHub/nightly.

### Risks/trade-offs

Its current settings show a broad module graph: multiple apps, core, data, many business modules, parser build, baseline profiles, benchmarks, mock server and lint tooling. This may be justified by its history/targets but would be premature for empty MuxTV.

### What MuxTV adopts

- simplicity as user-facing goal;
- TV-specific app boundary;
- parser corpus/benchmark boundary;
- baseline profiles and release artifacts;
- open-source, no advertising.

### What MuxTV avoids initially

- smartphone app;
- large feature/module graph before code pressure;
- extension infrastructure in Phase 00;
- copying internal architecture without reviewing dependencies/issues/licensing.

## 7. Kodi PVR IPTV Simple Client

### Repositories

- `kodi-pvr/pvr.iptvsimple`;
- Kodi core where platform behavior is relevant.

### Sufficiently mature reference areas

- de-facto extended M3U attributes;
- XMLTV gzip/xz and multiple playlist/guide configurations;
- catch-up template dialects;
- timeshift/catch-up distinctions;
- headers/properties and provider variability;
- long-term IPTV user expectations.

### Constraints

- plugin is built for Kodi PVR/inputstream architecture;
- Kodi profile/security/history choices are not modern Android architecture;
- desktop/embedded cross-platform compromises differ from TV APK;
- permissive parser behavior may conflict with MuxTV security boundaries.

### What MuxTV adopts

- compatibility corpus and typed normalization of common tags/templates;
- catch-up capability separation;
- multiple source/EPG expectations;
- conservative handling of provider extensions.

### What MuxTV rejects

- executing arbitrary Kodi/plugin schemes;
- treating profile PIN as strong security;
- direct translation of Kodi classes/settings;
- unbounded permissive handling of headers/local paths.

## 8. iptv-org ecosystem

### Repositories

- `iptv-org/iptv`;
- `iptv-org/database`;
- `iptv-org/epg`;
- `iptv-org/api`;
- `iptv-org/awesome-iptv`.

### Useful evidence

- real-world channel metadata diversity;
- public catalog structures;
- EPG generation across hundreds of source types;
- language/country/category/ID normalization needs;
- gzip, scheduling, multiple guides and concurrency;
- useful corpus candidates.

### Limitations

- public availability does not prove every stream's legal status, quality or persistence;
- EPG grabber is server/tooling code, not a secure Android client parser;
- database IDs/metadata may change and cannot become MuxTV's sole identity;
- huge star count does not make stream data trusted.

### MuxTV use

- optional test/reference datasets subject to license/legal review;
- aliases/metadata hints with provenance;
- not bundled as guaranteed content provider;
- never bypass user source/network/security policy.

## 9. XMLTV project

### Repository

- `XMLTV/xmltv`.

### Use

- canonical broad format/DTD vocabulary;
- timestamp/element semantics;
- fixtures and tooling reference.

### Critical distinction

Runtime Android parser disables DTD/entities and accepts bounded real-world deviations. Format compliance and secure input handling are separate concerns.

## 10. mpv-android/libmpv

### Repository

- `mpv-android/mpv-android`.

### Useful

- codec/container/subtitle compatibility reference;
- benchmark against Media3 problem streams;
- potential optional compatibility engine.

### Costs

- native build/NDK/packaging;
- larger APK and security update surface;
- harder lifecycle/crash diagnostics;
- different track/event behavior;
- upstream notes do not provide a turnkey stable AAR contract.

### Decision

Not baseline. Add only after corpus shows meaningful Media3 coverage gaps and benchmark/maintenance ADR passes.

## 11. Architecture conclusion

Reference projects suggest two dangerous extremes:

1. minimal player that leaves playlist/EPG/recovery complexity to user;
2. all-in-one media center that expands providers/VOD/DVR/plugins before core reliability.

MuxTV intentionally chooses:

```text
Reliable Live TV core
+ durable personal catalog
+ reversible Smart Channels
+ transparent TV Doctor
+ premium TV-first UX
```

Expansion occurs only after measured quality gates.

## 12. Review discipline

Before adopting an external pattern:

- verify current official API docs;
- inspect repository's current branch/release/status;
- inspect relevant code and tests, not README only;
- review issue history and known regressions;
- check license and transitive constraints;
- state MuxTV-specific problem;
- create ADR for architectural impact;
- benchmark/prototype when performance/native/KMP involved;
- document rejected alternatives and residual risk.

Repository references are refreshed periodically; findings are timestamped and never treated as timeless truth.