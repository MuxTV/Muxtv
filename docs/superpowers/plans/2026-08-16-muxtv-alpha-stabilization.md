# MuxTV Alpha Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Привести MuxTV из функционального pre-alpha к контролируемой MVP 0.1 alpha-базе, где Git/repository truth, CI evidence, playback ownership и TV interaction contracts согласованы и проверяются на точных интеграционных кандидатах.

**Architecture:** Работа разделена на независимые, последовательно принимаемые потоки. Сначала стабилизируется repository control plane, затем текущие блокирующие PR #167 и #168, затем устраняется dual-owner seek deviation (#132), после чего выполняются backlog/CI hygiene и release-hardening. Каждый поток выпускается отдельным PR и не смешивает product fixes с governance/dependency work.

**Tech Stack:** Git/GitHub Actions, PowerShell 7, Kotlin, Jetpack Compose for TV, Media3/ExoPlayer, Room 3, Android instrumentation, self-hosted Windows runners.

## Global Constraints

- Default branch: `main`.
- Current reviewed main at plan creation: `faa179a1301ab9b0977cc8991aee803b647ba7ba` (PR #171).
- Open product PRs at plan creation: #167 and #168.
- No merge is accepted from historical evidence; evidence must match the exact candidate under validation.
- Device failures are root-caused before behavior changes; timeouts are not increased as a substitute for diagnosis.
- Playback remains service-owned; no Activity/ViewModel becomes player/recovery authority.
- Rust remains evidence-gated and is out of this stabilization sequence.
- Product feature expansion is frozen until #167/#168 and repository control-plane blockers are resolved.

---

## Dependency Graph

```text
A. Repository truth/live-state contract
        |
        v
B. PR #167 progressive/external playback diagnosis
        |
        v
C. Merge/integration acceptance of #167
        |
        v
D. Rebase/update PR #168 on accepted main
        |
        v
E. PR #168 focus/lifecycle stabilization
        |
        v
F. Issue #132 single service-owned seek controller
        |
        +--------------------+
        |                    |
        v                    v
G. CI/backlog/branch      H. Release hardening
   reconciliation            API37/physical TV
```

## Phase A — Repository control plane

**Objective:** Versioned `.work` files must stop pretending that dynamic Git/GitHub state is current when it is only a reviewed snapshot. Git HEAD and GitHub coordination state become live authorities; `.work` remains durable architecture/product snapshot metadata.

- [ ] Add a regression contract proving that a reviewed snapshot may be an ancestor but must be surfaced as drifted when live HEAD differs.
- [ ] Add `tools/ci/Get-RepositoryLiveState.ps1` returning exact HEAD/branch, reviewed snapshot SHA, ahead count, dirty state and a boolean drift signal without network dependency.
- [ ] Update `tools/ci/Test-RepositoryTruthContract.ps1` to validate snapshot semantics and reject wording/metadata that labels an old snapshot as dynamic current main.
- [ ] Update `.work/meta/status.yaml` to explicit reviewed-snapshot/live-authority semantics.
- [ ] Rewrite `.work/CURRENT-STATE.md` so the product snapshot is reviewed through current `main@faa179a` and dynamic PR state is explicitly non-durable.
- [ ] Run Fast and Full repository validation; validate shallow checkout behavior.
- [ ] Merge only after exact-head host evidence is green.

**Acceptance:** A stale ancestor can no longer be silently interpreted by an agent as live repository state. A clean checkout can deterministically report both exact HEAD and snapshot drift.

## Phase B — PR #167 systematic diagnosis

**Objective:** Resolve exact-head failures in EP-08 without weakening first-frame, lifecycle, privacy or playback-service ownership contracts.

- [ ] Freeze #167 scope: no new resilience features.
- [ ] Re-run/inspect exact HEAD `948acbf3345aae94971ddee493943c7ddce9d6fa` host and device failures.
- [ ] Split `ExternalPlaybackRangeJourneyTest` waits into named stages: approval, surface attach, first frame, Started, seek/HUD, Back, Activity destruction, session finalization/background-stop.
- [ ] On timeout emit secret-free snapshots: Activity lifecycle, active setup/session IDs, player state, isPlaying, position/buffer, lease state, observation kinds and last HTTP-range metadata without locator/auth values.
- [ ] Form one root-cause hypothesis from the first failing boundary.
- [ ] Write a minimal regression test reproducing that boundary.
- [ ] Implement one production fix only if the failure is production behavior; otherwise fix test infrastructure only.
- [ ] Repeat the targeted journey enough times to detect race/flakiness before full device validation.
- [ ] Run PR Fast, DeviceCurrent, then exact integration candidate DeviceMatrix.

**Acceptance:** Both host and Android TV focused workflows are green on the exact accepted #167 candidate; `ProgressiveResilienceEvidenceTest` and `ExternalPlaybackRangeJourneyTest` execute rather than skip; no timeout inflation is used as the fix.

## Phase C — #167 integration admission

- [ ] Update #167 from the latest accepted `main` after Phase A.
- [ ] Generate/identify the exact integration candidate SHA.
- [ ] Run `Integration acceptance gate` Full + API26/API36 DeviceMatrix on that exact candidate.
- [ ] Reject and regenerate candidate if `main` changes before merge.
- [ ] Merge only the candidate for which evidence was produced.

**Acceptance:** Source-head evidence is not reused after main movement.

## Phase D — PR #168 rebase and scope freeze

- [ ] Freeze Lounge Light feature scope; only correctness/accessibility/focus/lifecycle fixes are accepted.
- [ ] Update #168 from the newly accepted main after #167.
- [ ] Re-run host + focused device tests before changing code; treat the new results as the only valid baseline.
- [ ] Retire historical temporary/restack branches only after the active head is verified.

**Acceptance:** There is one active Lounge Light lineage and one exact baseline.

## Phase E — PR #168 TV focus/lifecycle stabilization

**Investigation order:** lifecycle/crash symptoms first, then focus symptoms.

- [ ] Root-cause `No compose hierarchies found` at 720p using Activity/lifecycle/logcat evidence.
- [ ] Root-cause Doctor Compose timeout independently.
- [ ] Model focus restoration as an explicit state transition: navigation committed -> target registered -> composition ready -> request focus -> focus observed.
- [ ] Remove duplicate/competing focus-request scheduling for Channels and Settings.
- [ ] Define deterministic fallback when the semantic restoration target no longer exists.
- [ ] Add/strengthen tests for Channels filter restoration, Settings details close, no-action modal, Doctor return and 720p first/last action reachability.
- [ ] Run targeted tests repeatedly, then all `app:tv` device tests, DeviceCurrent and DeviceMatrix.
- [ ] Produce screenshot comparison at 1080p only after behavioral gates are green.

**Acceptance:** No focus test depends on arbitrary sleeps; all five previously failing journeys pass on exact-head device evidence; no Compose hierarchy disappears unexpectedly.

## Phase F — Issue #132 single service-owned seek authority

**Objective:** Eliminate dual seek/coalescing ownership left by #166/#167.

- [ ] Inventory every owner of seek target, coalescing window, pending seek, cancellation and generation identity.
- [ ] Write service-level tests for burst seek, media-generation change, controller reconnect, stop/back and recovery transition.
- [ ] Move authoritative coalescing/pending target/cancellation into `MuxTvPlaybackService`/service-owned controller.
- [ ] Reduce UI to emitting seek intents and rendering provisional/authoritative state; UI must not schedule the final seek.
- [ ] Preserve existing HUD behavior and remote responsiveness.
- [ ] Run player unit/device tests and app TV journeys.

**Acceptance:** Exactly one component decides when an interactive seek is applied to ExoPlayer; stale UI timers cannot seek a new media generation.

## Phase G — CI, issues and branch hygiene

- [ ] Reconcile #144 against #171 and retain only residual work or close it.
- [ ] Rewrite #30/#31/#101/#141 bodies into Implemented / Remaining / Deferred sections.
- [ ] Preserve #141 until artifact transport incident rate is measured after hardening.
- [ ] Split database device validation into focused contract smoke vs full integration/migration suites under #101.
- [ ] Verify GitHub rules/protection in repository settings; require the stable PR checks and integration admission policy if supported.
- [ ] Enable merged head-branch auto-delete when safe.
- [ ] Delete obsolete `tmp/*`, `backup/*`, `rebuild/*` branches only after proving they are not an open PR head and their commits remain reachable where needed.
- [ ] Define agent branch selection rule: `main` + heads of currently open PRs only.

**Acceptance:** Remote branch namespace represents active work; Issues describe residual scope; CI naming and admission semantics do not overclaim what was run.

## Phase H — Post-stabilization alpha hardening

- [ ] #146: isolated Room 3.0.1 dependency PR with DB/unit/device/migration evidence.
- [ ] #100: ETag/If-None-Match and Last-Modified/If-Modified-Since refresh path before parser micro-optimization.
- [ ] Add API37 local-network permission smoke for LAN/external playback.
- [ ] Modernize Android SDK/emulator harness warnings without mixing them with product fixes.
- [ ] Execute physical-TV corpus: weak ARM, long playback, channel switching, network loss/recovery, codec/HDR/audio claims only on hardware that supports them.

**Acceptance:** MVP 0.1 alpha claims are limited to evidence actually demonstrated by emulator/device/hardware lanes.

## Stop Conditions

Stop the current phase and return to investigation when any of the following occurs:

1. three fix hypotheses fail for the same defect;
2. a fix requires a second owner for player/recovery/seek/focus state;
3. evidence only passes after raising a timeout without locating the delayed boundary;
4. a PR begins changing an independent subsystem outside its declared scope;
5. main moves after integration evidence is captured.
