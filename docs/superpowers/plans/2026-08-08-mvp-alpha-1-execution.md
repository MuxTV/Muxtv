# Muxtv closed MVP `0.1.0-alpha.1` execution plan

**Date:** 2026-08-08

**Baseline:** `main@e9dd0336716e27e9b51f4eb10da82169112e71d1`

**Working branch:** `upd/mvp-alpha-1`

**Status:** active canonical ExecPlan

## Goal and authority

Deliver a private GitHub pre-release `0.1.0-alpha.1` (`versionCode=1001`) with a secure URL source flow, streaming and atomic M3U/XMLTV ingestion, useful TV destinations, one service-owned Media3 player, bounded same-channel recovery, redacted Doctor diagnostics, deterministic D-pad behavior, and measured performance on the supported device class.

This file is the only mutable execution journal for the MVP scope. The user-approved plan of 2026-08-08 and tracked source remain authoritative facts. Older plans remain historical evidence where they conflict with this file.

## Product contract

Included:

- protected IPTV source by URL;
- cancellable streaming M3U/XMLTV import with progress and atomic revision publication;
- Home, Channels, Guide, Favorites, Search, Recent, Sources, Doctor and Settings;
- a single `MediaSessionService`/ExoPlayer owner;
- bounded automatic same-channel recovery;
- secret-safe source/playback diagnosis and export;
- complete D-pad, focus restoration and Back behavior;
- API 26 and API 36 functional CI gates;
- a performance contract for API 29+ devices with 3–4 GB RAM;
- one physical Android TV or Google TV manual release/performance gate.

Excluded from this release:

- QR/local-web onboarding and local-file import;
- multiple profiles, backup/restore, Xtream, catch-up and DVR;
- Fire TV certification or compatibility claims;
- stores, a telemetry backend and an alternative playback engine;
- full localization. Alpha uses one internally consistent UI language.

## Non-negotiable architecture contracts

- Navigation, saved state and UI carry playback identity only: `profileId`, `channelId`, and optional `preferredVariantId`.
- Locators, headers and credentials are resolved inside the playback service boundary immediately before an attempt.
- `MuxTvPlaybackService` owns ExoPlayer, MediaSession, recovery generation, timeout and cancellation.
- Media3 handles retry/backoff inside one candidate; Muxtv alone advances between deterministic same-channel candidates.
- Each playback start has a generation token. Duplicate and stale callbacks are idempotently ignored.
- First rendered frame is the success boundary. A generation may attempt at most three candidates and last at most 20 seconds.
- Stop, replacement request, service destruction and timeout cancel resolution, preparation and pending callbacks.
- URI, headers, credentials and raw exception messages must never enter logs, diagnostics, navigation, saved state or export.
- Performance work is measurement-led. No mass rewrite, custom interning, second engine or native bridge without a measured residual problem and a separate decision record.

## Design source and TV adaptation

Normative external input is pinned to `emilkowalski/skills@de33dbed000212b54400a33767d1e4d03654db2a`, specifically `skills/emil-design-eng/SKILL.md` (MIT). Updates require a separate PR.

Android TV rules:

- dense D-pad focus changes immediately; frequent navigation is never animated;
- press feedback is immediate and restrained;
- rare overlays/dialogs/notifications enter in 140–220 ms ease-out and exit in 100–160 ms;
- use only alpha, scale or translation where motion explains state or spatial relation;
- no blur, animated layout sizing, heavy TV shaders, bounce or decorative delay;
- reduced-motion settings remove flourish without weakening outline/tone feedback;
- focus order, Back, scroll and focused-item restoration are deterministic;
- status is not encoded by color alone, and TV contrast, safe margins, type and targets are reviewed at viewing distance;
- every UI review records `Before | After | Why`.

## Execution checklist

### M0 — repository truth and safety

- [x] Authenticate Git HTTPS and obtain all branch, tag and PR refs.
- [x] Create and verify complete pre-cleanup bundle at `.git/bundles/pre-mvp-20260808.bundle` (470 refs).
- [x] Inventory 111 worktrees and preserve dirty, unmerged, open-PR and unknown histories.
- [x] Remove only three clean worktrees proven reachable from `origin/main`; retain all branches.
- [x] Fast-forward root checkout to `main@e9dd0336716e27e9b51f4eb10da82169112e71d1` and exclude local `worktrees/` through `.git/info/exclude`.
- [x] Create `upd/mvp-alpha-1` and this canonical ExecPlan.

### M1 — reliable self-hosted CI

- [x] Add a repository-owned, test-covered runner preflight for toolchain, disk/memory, ADB/device isolation and GitHub artifact DNS/HTTPS endpoints.
- [x] Fail closed before expensive jobs when the runtime results route or representative Azure Blob DNS/HTTPS is unavailable; successful `upload-artifact` remains the authoritative check for its service-returned signed Blob URL.
- [x] Keep Actions pinned to full SHAs, least-privilege permissions and exact source-head evidence; reject fork code on the persistent runner and never use `pull_request_target`.
- [x] Use unique artifact names, `if-no-files-found: error`, binary-friendly `compression-level: 0`, and bounded PR/release retention.
- [ ] Guarantee emulator, ADB and temporary-output cleanup; retain only dependency caches.
- [x] Cancel superseded PR runs while preserving manual/accepted-main runs.
- [ ] Enforce global emulator/device serialization through the dedicated runner label and verify that release runs are never cancelled.
- [ ] Validate configuration cache with fail-on-problems before making it a permanent gate.
- [ ] Apply and verify dedicated runner labels `muxtv-android` and `muxtv-device` in GitHub runner administration before workflows depend on them.
- [ ] Re-run PR #145 exact head and require substantive API 26/API 36 plus artifact publication to be green.

### M2 — bounded integration queue and truth sync

- [ ] Merge #145 only after exact-head required checks are green; keep it pure #30A.
- [ ] Integrate identity/path routing from #144 only if #30B requires it.
- [ ] Accept #146 Room update independently after current-stack validation.
- [ ] Rebase #148 after code integration and expand truth sync: architecture version, current execution, archived plans, actual issue scope and release state.
- [ ] Keep each PR to one logical slice: CI, playback contract, runtime recovery, Doctor, UI, performance or release.

### M3 — measurement foundation

- [ ] Add Macrobenchmark/Baseline Profile infrastructure and release verification for `baseline.prof`/ProfileInstaller.
- [ ] Cover cold/warm startup, Home→Channels, 500-item scroll, Search, Guide, Player, local-HLS first frame, recovery/fallback and focus restoration.
- [ ] Add focused microbenchmarks for M3U/XMLTV parsing, ordering/policy, Room mapping, now/next and search normalization.
- [ ] Generate profiles on a non-minified profile variant and consume them in minified release.
- [ ] Capture Compose stability/recomposition reports as diagnostic evidence; add stability annotations only for proven contracts.

### M4 — playback runtime and Doctor

- [ ] Add failing JVM/service tests for candidate order, duplicate/stale callbacks, cancellation, supersession, timeout and first-frame success.
- [ ] Introduce identity-only `PlaybackStartRequest`, one-at-a-time `PlaybackCandidateResolver`, pure `PlaybackRecoveryPolicy`, safe `PlaybackObservation` and bounded `PlaybackFailureCategory`.
- [ ] Implement service-owned generation state machine with at most 3 candidates / 20 seconds and one final result.
- [ ] Map DNS, TLS, HTTP, timeout, network, manifest, codec/render and credential failures without retaining raw exceptions or secrets.
- [ ] Add bounded durable diagnostics only if Doctor acceptance requires it and only after reserving the next Room schema owner.
- [ ] Implement Doctor presentation/export as a consumer of typed redacted observations, reachable from source and playback failures.

### M5 — data and allocation hot paths

- [ ] Add screen-specific Room projections and Room-backed paging with stable keys for large Channels/Search/Guide data.
- [ ] Keep parsers streaming with bounded batch transactions, cancellation points and atomic revision publication.
- [ ] Move sorting/filtering/formatting outside composable bodies; use stable keys/content types and narrow state-read scopes.
- [ ] Update now/next in the data layer for only affected time windows.
- [ ] Require `0 B/op` after warm-up for pure recovery/ordering functions and no app-owned per-frame allocation stack in static UI.
- [ ] For each performance PR, prove no allocations/op regression and at least 30% improvement in its selected hotspot.

### M6 — Lounge Light vertical UI slices

- [x] D1: immediate dense focus and stable geometry (accepted in PR #138 / `d109ad6a`).
- [ ] D2: OK/long-press/repeat semantics without global key interception.
- [ ] D3: independent focused/selected/playing/disabled states and reduced motion.
- [ ] D4: 720p/1080p dialog and viewport reachability.
- [ ] D5: Lounge Light design-system foundation and compact shell/navigation.
- [ ] D6: Home, Channels, Search, Recent and Guide using real state only.
- [ ] D7: Player/recovery/Doctor, Sources and Settings with auto-hiding overlay and no player recreation.
- [ ] Record `Before | After | Why` and semantic/device evidence for every UI PR.

### M7 — release hardening

- [ ] Set `versionCode=1001`, `versionName=0.1.0-alpha.1`, R8 minification, resource shrinking and full optimization for release.
- [ ] Read signing material only from a manually approved GitHub Environment and never persist it in logs/artifacts/caches.
- [ ] Produce signed APK, mapping, dependency SBOM, SHA-256 checksums, license report, benchmark JSON/traces and toolchain/device/source manifest.
- [ ] Perform two clean release builds and compare artifact contents; document unavoidable timestamp differences.
- [ ] Because no predecessor alpha artifact/signing identity exists, first create a controlled signed seed APK in the protected environment with the same key lineage; then prove upgrade, installability, service playback and profile inclusion.
- [ ] Publish a private GitHub pre-release with verified-device scope, known limitations, Doctor transfer instructions and rollback procedure.

## Performance and quality gates

Absolute release measurements are manual on one pinned physical API 29+ Android/Google TV device with 3–4 GB RAM. Evidence records model, firmware, resolution, refresh rate, temperature and free memory. Emulators provide functional compatibility, not absolute performance numbers.

| Scenario | MVP ceiling |
| --- | ---: |
| Cold startup to interactive Home | p95 ≤ 1.8 s |
| Warm startup | p95 ≤ 800 ms |
| Visible D-pad response | p95 ≤ 33 ms |
| 30 s Channels/Guide scroll | p99 frame ≤ 33.3 ms; 0 frozen frames |
| Local HLS OK to first frame | p95 ≤ 1.5 s |
| Recovery | ≤ 3 attempts and ≤ 20 s |
| Playback with overlay closed | PSS ≤ 250 MB |
| 100 navigation/play cycles | retained growth ≤ 10 MB |
| M3U 50,000 parse + persist | p95 ≤ 10 s; cancellation ≤ 500 ms |
| Regression against pinned baseline | median ≤ +5%; p95 ≤ +10% |

Startup/navigation Macrobenchmarks run at least ten iterations; other CUJs run at least five. PR dry-runs prove executability; release runs retain JSON and Perfetto traces. A slice whose baseline exceeds its ceiling is incomplete until corrected; ceilings are not relaxed to fit implementation.

## Required acceptance scenarios

- valid, large, malformed and cancelled M3U/XMLTV; success/cancellation/process-death/DB-failure revision atomicity;
- 50,000 channels without full UI list materialization;
- preferred/deterministic recovery ordering, exhaustion, timeout, cancellation and replacement request;
- duplicate/late Media3 callbacks and errors before/after first frame;
- HTTP 401/403/404/429/5xx, DNS, TLS, timeout, unreachable network and unsupported media;
- forbidden secret material absent from logs, events, saved state and Doctor export;
- D-pad traversal, Back, focus restoration and recreation on API 26/API 36;
- minified signed release installation/upgrade, Baseline Profile and playback-service smoke;
- physical-device smoke, endurance and performance gate.

## Definition of done

- All required checks are green on one exact source SHA.
- #30B, #30D and the MVP subset of #111 are closed; no open P0/P1 defects remain.
- Physical-device ceilings pass with retained evidence.
- Documentation, roadmap, architecture version, issues and release notes match code.
- The private pre-release contains the signed APK, checksums, SBOM, mapping and reproducible evidence.
- No temporary shim, parallel playback path, hidden CI bypass or unresolved secret-bearing diagnostic path remains.

## Decisions and evidence log

- 2026-08-08: user fixed closed-alpha scope, `versionCode=1001`, recovery budget `3 / 20 s`, physical Android/Google TV gate and Fire TV exclusion.
- 2026-08-08: repository synchronized to `origin/main@e9dd0336716e27e9b51f4eb10da82169112e71d1`; full 470-ref bundle verified before cleanup.
- 2026-08-08: external design input pinned to `emilkowalski/skills@de33dbed000212b54400a33767d1e4d03654db2a`.
- 2026-08-08: accepted main already contains D1 through PR #138 / `d109ad6a`; historical `work/tv-design-craft-111` remains provenance only.
- 2026-08-08: no prior alpha artifact or signing lineage exists; release hardening must create a controlled same-key seed before the required signed-upgrade test.

## Stop conditions

Stop and update this plan rather than weakening evidence if source SHA differs from the claimed commit, artifact upload is unavailable, a second retry/player owner appears, secrets must cross the service boundary, two changes claim one Room schema version, a benchmark is not reproducible, or physical-device evidence is missing for a release/performance claim.
