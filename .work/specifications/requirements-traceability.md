---
status: accepted
last_reviewed: 2026-07-19
owners: [product, architecture, quality]
---

# Requirements traceability

## 1. Purpose

This index maps product requirements to normative design/specification, planned phase and required evidence. A feature is not complete because it appears in README/roadmap; completion requires implementation and listed evidence.

## 2. Requirement states

```text
Specified     normative document accepted
Planned       implementation plan/task exists
Implemented   code exists
Verified      fresh required tests/evidence pass
Released      included in signed stable release
```

Current repository is `Specified` for the requirements below and not `Implemented` unless `.work/CURRENT-STATE.md` says otherwise.

## 3. Product and platform

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| P-001 | Android TV/Google TV/Fire TV first line | PROJECT, ARCHITECTURE, platforms/fire-tv | 00+ | physical device matrix |
| P-002 | No mandatory account/cloud/telemetry | PROJECT, security/threat-model | 00+ | dependency/network audit |
| P-003 | GitHub sideload distribution | release/self-update-and-signing | 00/04 | signed APK/update tests |
| P-004 | No bundled paid/unauthorized content | PROJECT | all | release/content review |
| P-005 | Simple mode plus expert disclosure | PRODUCT, design-system | 04 | journey/screenshots |

## 4. Profiles

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| PR-001 | exactly one primary profile on first run | profiles, ADR-0004, meta/profiles | 00 | DB initializer/invariant test |
| PR-002 | no built-in children/parents/guest profile types | profiles, PRODUCT | 00 | schema/model absence test |
| PR-003 | additional profiles user-created/named | profiles | 02 | create/switch UI/domain tests |
| PR-004 | primary cannot delete but can rename | profiles | 00/02 | use-case/DB tests |
| PR-005 | sources installation-scoped, overlays profile-scoped | profiles, domain-model | 00 | schema/cascade tests |
| PR-006 | policies apply to any profile | profiles | 02 | policy/search/playback/local-control tests |
| PR-007 | no profile picker with one profile | profiles, focus-navigation | 00 | UI journey test |

## 5. Source/catalog ingestion

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| SRC-001 | M3U URL/file bounded streaming import | m3u-ingestion | 01 | corpus/performance tests |
| SRC-002 | untrusted network/address/header policy | network-and-source-policy | 01 | SSRF/redirect tests |
| SRC-003 | immutable revision/staging/atomic commit | source-refresh | 01 | process-death/transaction tests |
| SRC-004 | failed refresh preserves current catalog | source-refresh | 01 | fault tests |
| SRC-005 | URL/token changes do not create channel identity | domain-model, source-refresh | 01 | revision fixture test |
| SRC-006 | provider/canonical/overlay separation | domain-model | 00/01 | schema/repository tests |
| SRC-007 | suspicious count/identity churn guarded | source-refresh | 01 | generated diff tests |

## 6. EPG

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| EPG-001 | secure streaming XMLTV/gzip/zip | xmltv-processing | 02 | corpus/XXE/bomb tests |
| EPG-002 | timezone/DST ambiguity explicit | xmltv-processing | 02 | time fixtures |
| EPG-003 | previous guide preserved after failed revision | xmltv-processing, source-refresh | 02 | transactional test |
| EPG-004 | manual binding survives refresh | xmltv-processing, domain-model | 02 | binding test |
| EPG-005 | lazy interval EPG grid | xmltv-processing, design-system | 02 | UI/performance test |
| EPG-006 | return to current channel/time context | focus-navigation, user-journeys | 02 | focus journey test |

## 7. Playback

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| PB-001 | Media3 behind PlaybackEngine | playback-runtime | 00 | dependency/contract test |
| PB-002 | player survives Activity recreation | playback-runtime | 01 | instrumentation test |
| PB-003 | MediaSession/audio focus/remote controls | playback-runtime, focus-navigation | 01 | device tests |
| PB-004 | stable mapped errors, no raw exceptions | playback-errors | 00/01 | mapper/UI tests |
| PB-005 | bounded cancellable recovery | playback-runtime, playback-errors | 01 | fake-engine state tests |
| PB-006 | live edge/behind-window recovery | playback-runtime | 01 | local HLS fixture |
| PB-007 | semantic track identity, no index crash | playback-runtime | 01 | manifest-refresh fixture |
| PB-008 | runtime device capability evidence | playback-runtime | 01+ | physical codec matrix |
| PB-009 | channel zap measured to stable frame/audio | benchmark-methodology | 01 | physical benchmark |

## 8. Smart Channels and TV Doctor

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| SC-001 | conservative candidate matching with conflicts | smart-channels | 03 | labeled corpus |
| SC-002 | merge/split explainable/reversible | smart-channels, domain-model | 03 | mutation round-trip tests |
| SC-003 | manual reject/split survives upgrades | smart-channels | 03 | algorithm-version migration test |
| SC-004 | auto-merge disabled until precision >=99.5% | meta/scoring-model | 03 | calibration report |
| DOC-001 | L0–L4 evidence levels distinct | tv-doctor | 03 | probe contract tests |
| DOC-002 | audit does not degrade foreground playback | tv-doctor, benchmark-methodology | 03 | concurrent benchmark |
| DOC-003 | findings preview/select/apply/undo | tv-doctor | 03 | mutation/UI tests |
| DOC-004 | health scoped by device/network/evidence/confidence | tv-doctor | 03 | aggregation tests |
| DOC-005 | transparent stream scoring/hysteresis/cooldown | tv-doctor, meta/scoring-model | 03 | calibration/state tests |

## 9. UI/search/accessibility

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| UI-001 | all essential flows via five-button D-pad/Back | focus-navigation | 00+ | focus graph tests |
| UI-002 | visible focus and distinct selected/pressed states | design-system | 00+ | screenshots/contrast tests |
| UI-003 | stable-key focus restoration | focus-navigation | 00+ | route/data mutation tests |
| UI-004 | 720p/1080p/4K and large-text/high-contrast/reduced-motion | design-system | 00/04 | screenshot/device matrix |
| UI-005 | premium visuals do not harm startup/zapping/jank | design-system, benchmark-methodology | all | macrobenchmarks |
| SRCH-001 | local indexed channel/programme search | search | 02/04 | ranking/latency tests |
| SRCH-002 | rule-based time/category queries without LLM | search | 04 | locale query fixtures |
| SRCH-003 | search obeys profile restrictions | search, profiles | 02/04 | cross-profile tests |

## 10. Local control/security

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| LC-001 | QR/one-time TV-confirmed pairing | local-control | 04 | replay/consent tests |
| LC-002 | server not permanently exposed by default | local-control | 04 | lifecycle/network test |
| LC-003 | scoped capabilities and secret write-only behavior | local-control | 04 | API/security tests |
| LC-004 | no generic URL proxy/filesystem/package install API | local-control, threat-model | 04 | negative tests |
| SEC-001 | SSRF/private IP/redirect revalidation | network-and-source-policy | 01 | address/rebinding tests |
| SEC-002 | cross-origin credentials stripped | network-and-source-policy | 01 | redirect matrix |
| SEC-003 | XML entities/archive bombs bounded | xmltv-processing, threat-model | 01/02 | hostile corpus |
| SEC-004 | secrets excluded/redacted by default | threat-model, backup-and-restore | 00+ | canary scan |
| SEC-005 | extensions least privilege/out-of-process | extensions | 05 | conformance/security tests |

## 11. Backup/update/release

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| BK-001 | versioned backup without secrets by default | backup-and-restore | 02 | golden/canary test |
| BK-002 | staged previewable atomic restore | backup-and-restore | 02 | corruption/cancel tests |
| BK-003 | restore never creates second primary | backup-and-restore, profiles | 02 | mode matrix test |
| UP-001 | official GitHub channel only | self-update-and-signing | 04 | metadata endpoint tests |
| UP-002 | hash/package/version/certificate verification | self-update-and-signing | 04 | malicious artifact tests |
| UP-003 | PackageInstaller user/system confirmation | self-update-and-signing | 04 | instrumentation/manual test |
| UP-004 | nightly separate applicationId | self-update-and-signing | 00 | APK inspection |
| REL-001 | pinned protected release workflow/SBOM/checksums | self-update-and-signing | 00/04 | workflow review/artifacts |
| REL-002 | migration/recovery before release | self-update-and-signing | all | upgrade matrix |

## 12. Performance/platform

| ID | Requirement | Normative documents | Phase | Evidence |
|---|---|---|---|---|
| PERF-001 | claims include device/firmware/network/method/sample | benchmark-methodology | 00+ | report schema |
| PERF-002 | deterministic fixtures plus physical TV devices | benchmark-methodology | 00+ | lab reports |
| PERF-003 | bounded parser/database memory | benchmark-methodology | 01/02 | large corpus |
| PERF-004 | endurance/network/fault tests | benchmark-methodology | 01+ | traces/reports |
| FIRE-001 | no mandatory GMS | fire-tv | 00 | dependency/device launch |
| FIRE-002 | physical Fire remote/lifecycle/codec/performance gate | fire-tv | 01+ | Fire device report |

## 13. Evidence ownership

- test/code evidence stored in CI, reports and `.work/reviews` references;
- large traces/binaries remain artifacts, not source commits;
- each completed phase updates `CURRENT-STATE.md` and `meta/status.yaml`;
- failing/untested requirement cannot be marked Verified;
- reference repository feature or README does not satisfy MuxTV evidence.

## 14. Change policy

Requirement ID is stable once implementation starts. Semantic change updates normative document and traceability, and may require ADR/migration. New requirements must identify phase, evidence and scope impact before implementation.