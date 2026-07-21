# Android TV device harness review

## Scope

Branch: `infra/tv-device-harness`

This checkpoint adds repository-owned Android TV emulator orchestration for the Windows self-hosted runner. It does not change application production code.

## Implemented

- `DeviceCurrent` and `DeviceMatrix` workflow-dispatch modes;
- SDK command-line tool discovery through `ANDROID_SDK_ROOT` / `ANDROID_HOME`;
- installation of missing platform-tools, emulator and selected TV system images;
- `sdkmanager --list` based image resolution without guessed package names;
- Android TV API 36 current profile;
- API 26 old-edge profile with explicit API 26–30 fallback reporting;
- deterministic `tv_1080p` AVD recreation;
- sequential matrix execution to keep runner memory bounded;
- hardware-acceleration check with actionable WHPX guidance;
- headless cold boot with snapshots and audio disabled;
- boot and package-manager readiness checks;
- `ANDROID_SERIAL` isolation for connected Gradle tests;
- reuse of `tools/verify-local.ps1 -Mode Device` rather than duplicating Gradle task logic;
- final logcat, activity, meminfo, device properties and screenshot collection;
- emulator shutdown in `finally` with forced-process fallback;
- branch/head-bound device manifest and expanded Actions evidence upload.

## Architecture review

- The harness is infrastructure-only and remains under `tools/android`.
- Android runtime behavior continues to be verified through existing Gradle instrumentation tasks.
- Device matrix execution is sequential, avoiding multiple concurrent AVDs on the current runner.
- Emulator results are smoke/runtime evidence only; codec and performance claims still require physical devices.
- No credentials, source URLs or playlist data are intentionally collected.

## Required gates

1. Self-hosted `Full` on the exact PR head.
2. Manual `DeviceCurrent` to prove SDK discovery, image installation, boot, connected tests and cleanup.
3. Manual `DeviceMatrix` before merging changes that depend on oldest/current Android TV runtime behavior.

## Known environment requirement

The self-hosted Windows host must have CPU virtualization enabled and Windows Hypervisor Platform available. The harness detects and reports this condition but cannot enable firmware virtualization itself.
