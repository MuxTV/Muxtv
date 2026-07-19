---
status: accepted
last_reviewed: 2026-07-19
owners: [android-platform, player, quality, release]
---

# Fire TV platform specification

## 1. Position

Fire TV входит в первую линию поддерживаемых устройств вместе с Android TV/Google TV, но не считается полностью идентичной платформой. Fire OS основана на AOSP, имеет собственные версии, магазин, launcher, accessibility, remote variants и не гарантирует Google Play Services.

## 2. Build strategy

Baseline — один Android TV APK без обязательных Google Services.

Rules:

- core app starts and plays without GMS;
- Google-specific integrations behind capability/optional modules;
- Fire-specific behavior isolated in platform adapter, not scattered conditions;
- same application/domain/player contracts;
- separate distribution channel metadata only where required;
- Amazon Appstore publication deferred, sideload APK remains supported;
- feature flags derive from capabilities/build channel, not manufacturer string alone.

## 3. Google dependencies

Forbidden as mandatory runtime dependency:

- Google Sign-In;
- Firebase-only essential services;
- Play Billing;
- Play Integrity as core gate;
- Cast as required playback path;
- Play Store in-app update;
- Google-specific voice/search APIs without fallback.

Optional Google TV integrations are absent/disabled cleanly on Fire TV.

## 4. Remote input

Support standard Android key events plus Fire remote keys where present:

```text
DPAD directions/center
Back
Home (system-owned)
Menu
Play/Pause
Rewind/Fast Forward
Voice (system-owned)
Channel Up/Down on compatible remotes
numeric keys on external remotes
```

- essential actions have five-button D-pad fallback;
- Menu may open context actions, but long-press OK/«Ещё» remains available;
- key repeat and long press tested on physical Fire remotes;
- Home cannot be intercepted as app navigation;
- Back behavior follows shared contract;
- media keys routed through MediaSession.

## 5. Lifecycle and background

- app handles Activity recreation/configuration changes without ending playback session;
- foreground service/background behavior tested under Fire OS restrictions;
- screen saver/ambient mode interactions tested;
- memory pressure/process death common on low-RAM sticks and must recover safely;
- Home→return restores state without requiring full source refresh;
- auto-start/boot behavior not added without explicit user need and platform compliance review.

## 6. Playback/device capability

Fire devices differ significantly by generation. Capability detection uses runtime APIs and observed evidence.

Test matrix includes:

- AVC/HEVC/AV1 where advertised;
- MPEG-2 where applicable;
- 1080p/4K/fps modes;
- HDR10/HDR10+/Dolby Vision by actual model/display chain;
- AAC/AC-3/E-AC-3/Opus and passthrough scenarios;
- HLS/DASH/MPEG-TS live;
- subtitles;
- refresh-rate/display-mode changes;
- first playback cold delay;
- long-session stutter/frame drops.

No claim such as «Fire TV supports codec X» without model/generation profile.

## 7. Network

- IPv4 and IPv6 resolution paths tested;
- local/LAN source access requires same security policy as other platforms;
- Wi-Fi reconnect/sleep behavior tested;
- captive/unvalidated network status may differ; app provides provider-level diagnostics;
- no assumption that device has Ethernet;
- VPN/proxy follows system/app policy;
- local-control pairing works on same LAN without Google services.

## 8. Storage

- app-private DB/cache/temp baseline;
- Storage Access Framework behavior tested on Fire OS;
- external/custom DVR storage deferred and model-specific;
- low free-space warnings and cleanup critical on sticks;
- updater checks package installation permission/source and disk space;
- backup export/import must present reachable D-pad actions and support available document providers.

## 9. UI and performance

Low-end profile can disable/reduce:

- background video preview;
- real-time blur/parallax;
- aggressive artwork prefetch;
- multi-decoder probe/multiview;
- high-resolution artwork beyond display need;
- expensive animation.

UI remains visually complete; reduced profile is not a broken theme.

Release budgets include Tier F p50/p95 startup, zapping, EPG scroll and hour playback. Regression seen only on Fire TV still blocks Fire-supported release.

## 10. Accessibility

- VoiceView tested for labels, focus order and player controls;
- high contrast/reduced motion/large text remain app features;
- no critical instruction only in image/color;
- timeout/auto-hide pauses during accessibility interaction where detectable;
- remote focus remains visible under system magnification/accessibility settings.

## 11. Launcher and platform integrations

Core APK declares TV launcher intent/category compatible with Fire TV. Platform-specific recommendation/Watch Next/input APIs are capability adapters:

- Android TV/Google TV integrations must not crash Fire TV;
- Fire launcher integrations require official API/policy review before implementation;
- absence of integration never blocks app launch or playback;
- update flow uses GitHub/package installer, not Play Store APIs.

## 12. Distribution

GitHub universal APK is primary sideload artifact. Documentation includes:

- Downloader/browser/ADB methods where current platform permits;
- enable installation from unknown source steps as platform UI changes;
- certificate/checksum verification guidance;
- stable/nightly package separation;
- update confirmation through system installer.

Amazon Appstore, if pursued, gets separate release/compliance pipeline without weakening open GitHub distribution.

## 13. Physical reference devices

Minimum before `1.0`:

- one low/mid Fire TV Stick representing constrained CPU/RAM;
- one current 4K/HDR Fire device;
- optional Cube/high-end for passthrough and Ethernet.

Record exact model, Fire OS, Android API, build, resolution and audio/display chain.

## 14. Critical reference findings

Jellyfin Android TV supports Fire TV over many years and its issue history shows:

- first playback may be much slower on Fire/Chromecast classes;
- performance regressions can be device-specific after large releases;
- codec/passthrough/display behavior must be tested on actual chain;
- local networking assumptions such as IPv4-only resolution can break valid setups;
- service/lifecycle resource management requires explicit architecture.

These findings justify shared contracts plus a Fire-specific test gate, not a separate fork.

## 15. Acceptance criteria

- APK launches and core flows work without Google Play Services;
- D-pad/Back/Menu/media keys behave predictably;
- Activity recreation/Home-return does not corrupt session/catalog;
- low-end Fire device meets reduced-visual performance budgets;
- local IPv4/IPv6 sources can work under explicit policy;
- unsupported platform integrations are absent safely;
- update/backup flows are reachable with remote;
- release includes physical Fire TV smoke and endurance evidence.