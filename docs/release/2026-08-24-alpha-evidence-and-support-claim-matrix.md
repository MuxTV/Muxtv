# MuxTV 0.1 Alpha Evidence and Support-Claim Matrix

Status: prepared release contract for #31. This document defines what evidence is allowed to prove. It does not claim that any pending runtime/device gate has already passed.

## 1. Evidence classes

Use exactly these claim labels in alpha evidence/release notes unless a later reviewed contract supersedes them:

| Claim | Meaning | May be generalized? |
| --- | --- | --- |
| `VERIFIED_VIRTUAL` | Reproduced on one of the two canonical Android TV AVDs. Valid for Android/API/application correctness only. | Only to the tested Android/API contract, never vendor hardware. |
| `VERIFIED_PHYSICAL_DEVICE` | Reproduced on the named physical model/OS/display/audio route. | No; device-scoped unless repeated evidence supports a broader claim. |
| `LIMITED_EVIDENCE` | Observed, but coverage/variance is too small for a support statement. | No. |
| `UNVERIFIED` | Not exercised with appropriate evidence. | No. |
| `KNOWN_UNSUPPORTED` | Reproducibly unsupported or deliberately excluded by product policy. | Only within the explicitly documented scope. |

## 2. Canonical virtual matrix

Repository-owned AVD identities are exactly:

| Identity | Purpose | Can prove | Cannot prove |
| --- | --- | --- | --- |
| `MuxTV_TV_OLD_API26` | Minimum supported Android/API compatibility | install/start, lifecycle, Room migration, app contracts available on API26, basic D-pad/player/session compatibility | weak ARM performance, vendor codec/HDR/passthrough, Fire OS, thermal behavior |
| `MuxTV_TV_CURRENT_API36` | Current Android TV correctness and primary UI/device evidence | current API behavior, D-pad/focus, MediaSession, Room, UI geometry, instrumentation, display-mode checks | vendor decoder quality, real-TV absolute performance, Fire OS, physical HDR/audio route behavior |

Do not create low-RAM, 720p, 4K, benchmark, migration, catalog, player or variance-specific AVD identities. Those are workload/configuration dimensions or physical-device evidence classes.

## 3. Display configuration matrix

Display tests reuse `MuxTV_TV_CURRENT_API36` unless a specific API26 compatibility question requires otherwise.

| Mode | Classification | Intended evidence |
| --- | --- | --- |
| 1920x1080 @ 320dpi | representative 1080p TV mode | primary Lounge layout/focus geometry |
| 1280x720 @ ~213dpi | representative 720p TV density | small-TV geometry, long RU strings, safe focus/action containment |
| 1280x720 @ 320dpi | compact stress | robustness only; must never be presented as representative 720p-TV evidence |
| large-text configuration | accessibility dimension | reachability, clipping, focus order |
| reduced-motion configuration | accessibility dimension | focus/state usability without scale/motion |

Every display override must be restored in `finally` by the test harness.

## 4. Physical qualification matrix

Physical evidence records exact model, OS/API/Fire OS, app SHA, display/audio route, fixture class and observed decoder/runtime information where available.

| Class | Minimum questions |
| --- | --- |
| Current Android/Google TV | install/update, core D-pad journeys, startup, first-frame, zapping, standard HLS/progressive playback |
| Constrained/older Android TV | memory growth, long navigation/playback soak, rebuffer/first-frame trade-offs, operational responsiveness |
| Fire TV/Amazon | Fire OS lifecycle/session/input behavior, actual decoder/audio differences, any Amazon-specific failure |
| Codec/HDR fixture hardware | actual selected decoder, AVC/HEVC/VP9/AV1 as available, 8/10-bit where observable, HDR10/HLG/HDR10+/Dolby Vision only where hardware/display reports and reproduces it |
| Audio-route fixture | stereo/multichannel/passthrough/offload only on the actual connected route and supported fixture |
| Real-network fixture | DNS/TLS/connect/TTFB/body/rebuffer behavior on explicitly described network conditions |

A physical finding remains `VERIFIED_PHYSICAL_DEVICE` for that device until repeated evidence justifies broader documentation.

## 5. Evidence provenance required for every release-significant record

Record at minimum:

- exact source commit SHA;
- package/application version;
- build variant and signing identity category (never private key material);
- device/AVD identity;
- Android API / OS / Fire OS;
- emulator/system-image/tool versions for virtual evidence;
- physical model and relevant display/audio route for hardware evidence;
- display size/density when UI evidence is involved;
- fixture/corpus ID and schema/version;
- timestamp/timezone;
- test/journey identifier;
- result classification;
- artifact/report digest when archived;
- known environmental deviations.

Never include raw playlist locators, query tokens, Authorization/Cookie values, provider credentials or private user data.

## 6. Alpha functional evidence matrix

| Area | Host/static prerequisite | Virtual evidence | Physical evidence before broad claim |
| --- | --- | --- | --- |
| Install/start | build/release config | API26 + API36 | current TV |
| Upgrade/Room | migration/schema tests | API26 + API36 | recommended current TV for release candidate |
| Keystore/re-auth | security tests | API26 + API36 where supported | current TV for release recovery flow |
| Reboot/unlock/WorkManager | #118 contracts | API26/API36 where boot simulation is reliable | at least current TV for release confidence |
| Home/Channels/Guide/Search/Settings D-pad | Compose/unit/static focus contracts | API36 primary; API26 compatibility | current TV |
| 720p/1080p UI | U0/U1 geometry/focus contracts | same API36 with display override | physical check recommended where available |
| Player first frame/zapping | player tests | API36 + API26 compatibility | current TV; constrained TV before performance claims |
| Seek | #175 authority tests | API36/API26 | current + constrained TV before latency claims |
| Rebuffer/buffer policy | #27/#109 measurement semantics | correctness/relative evidence only | constrained/representative physical device required |
| Codec/HDR/passthrough | typed capability/fixture contracts | only API-level fallback/no-crash evidence | required on exact hardware capability |
| Fire TV support | host contracts only | generic Android AVD does not count | Fire TV/Amazon environment required |
| Backup/restore | #113 schema/threat model/tests | Android correctness where applicable | TV-operable path required before claiming usability |

## 7. Baseline Profile / Macrobenchmark CUJs

Release measurement should cover actual work, not merely opening screens:

1. cold/warm startup -> first usable Home focus;
2. Channels: sustained D-pad moves through a paging/focus-restoration boundary;
3. Search: type realistic synthetic query -> results -> move focus;
4. Guide: horizontal + vertical navigation over a populated bounded window;
5. Player: launch -> first rendered frame;
6. repeated channel zaps;
7. bounded repeated seek sequence through the service-owned authority.

Report the metric distribution appropriate to each journey (startup/frame p50/p95/p99, jank, first-frame, zapping, seek/rebuffer). No performance claim from one run.

## 8. R8/release integrity gate

Before alpha acceptance:

- release shrinking/resource optimization enabled as intended;
- AGP R8 Configuration Analyzer report generated and reviewed;
- broad keep rules have an explicit reason;
- any keep-rule reduction is independently testable/revertible when non-trivial;
- release build installs and core reflection/serialization/navigation/worker/player paths execute;
- SBOM/dependency report generated;
- native artifact provenance/digests included if native libraries are ever added;
- signing/package/certificate identity recorded without secret material.

## 9. Claim examples

Allowed:

> `VERIFIED_VIRTUAL`: Channels/Guide/Search D-pad journeys pass on canonical Android TV API36 for commit `<sha>`.

Allowed:

> `VERIFIED_PHYSICAL_DEVICE`: HEVC Main10 playback reproduced on `<model>` / `<OS>` / `<display route>` for fixture `<id>` at commit `<sha>`.

Not allowed:

> “HEVC/HDR works on Android TV” because the emulator launched the stream.

Not allowed:

> “Optimized for weak TVs” from a low-memory emulator configuration alone.

Not allowed:

> “Fire TV supported” without Fire TV/Amazon evidence.

## 10. Release decision rule

An alpha candidate may ship with `UNVERIFIED` or `LIMITED_EVIDENCE` capabilities if they are explicitly documented as limitations and are not required core acceptance criteria. It may not silently convert missing evidence into a compatibility claim.

The release gate is therefore evidence-completeness plus honest scope, not an attempt to certify every TV model before `0.1.0-alpha`.
