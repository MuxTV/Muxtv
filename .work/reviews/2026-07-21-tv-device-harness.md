# Android TV device harness review

## Scope

Branch: `infra/tv-device-harness`

This checkpoint adds repository-owned Android TV emulator orchestration for the Windows self-hosted runner. It does not change application production code.

## Implemented

- `DeviceCurrent` and `DeviceMatrix` workflow-dispatch modes;
- SDK command-line tool discovery through `ANDROID_SDK_ROOT` / `ANDROID_HOME`;
- installation and update of platform-tools, emulator and selected TV system images;
- `sdkmanager --list` based image resolution without guessed package names;
- Android TV API 36 current profile;
- API 26 old-edge profile with explicit API 26–30 fallback reporting;
- deterministic `tv_1080p` AVD recreation;
- sequential matrix execution to keep runner memory bounded;
- hardware-acceleration check with actionable WHPX guidance;
- headless cold boot with snapshots and audio disabled;
- bounded ADB registration with dynamic even console/ADB port selection;
- selected ADB server restart plus TCP registration fallback;
- boot and package-manager readiness checks;
- `ANDROID_SERIAL` isolation for connected Gradle tests;
- reuse of `tools/verify-local.ps1 -Mode Device` rather than duplicating Gradle task logic;
- final logcat, activity, meminfo, device properties and screenshot collection;
- emulator shutdown in `finally` with forced-process fallback;
- branch/head-bound device manifest, exception type/trace and expanded Actions evidence upload.

## Architecture review

- The harness is infrastructure-only and remains under `tools/android`.
- Android runtime behavior continues to be verified through existing Gradle instrumentation tasks.
- Device matrix execution is sequential, avoiding multiple concurrent AVDs on the current runner.
- Emulator results are smoke/runtime evidence only; codec and performance claims still require physical devices.
- No credentials, source URLs or playlist data are intentionally collected.

## Verified DeviceCurrent run

Self-hosted run `29863649053` passed on head `28896176b5ba77d2da1ed02483a4883b7942b493`.

Resolved environment:

- Windows Hypervisor Platform operational on Windows 10.0.19045;
- Android Emulator `36.6.11`;
- ADB/platform-tools `37.0.0`;
- system image `system-images;android-36;android-tv;x86_64`;
- AVD `MuxTV_TV_CURRENT_API36`;
- 1920x1080 `tv_1080p`, 2 GB RAM, two CPU cores;
- Android API 36, x86_64;
- cold boot and package-manager readiness succeeded;
- emulator shutdown completed after validation.

The nested Device evidence manifest passed all steps:

1. Gradle version;
2. build-logic tests;
3. configuration-cache create/reuse;
4. pure Kotlin tests;
5. Android unit tests;
6. credentials instrumentation APK compilation;
7. debug APK;
8. Android lint;
9. release APK;
10. real Android Keystore instrumentation tests;
11. Room/database instrumentation tests;
12. application instrumentation tests.

The artifact `self-hosted-validation-29863649053-1` is bound to the same head. Connected-test reports contain one actual target, `MuxTV_TV_CURRENT_API36(AVD)`.

## Verified final Full run

Self-hosted run `29943748575` passed on final head `5c976d2b925678ec924751306c1b0af532feb8de`. The permanent workflow retains normal PR `Full` behavior and exposes `DeviceCurrent`/`DeviceMatrix` only as explicit manual modes.

## Remaining release checkpoint

`DeviceMatrix` has not yet been claimed as passed. It remains a manual/release checkpoint for resolving the old Android TV image and running old/current profiles sequentially. It becomes mandatory before merging Keystore schema changes, Room migrations that affect persisted user data, MediaSessionService, player-surface lifecycle and TV focus/navigation packages.

## Known environment requirement

The self-hosted Windows host must have CPU virtualization enabled and Windows Hypervisor Platform available. The harness detects and reports this condition but cannot enable firmware virtualization itself.
