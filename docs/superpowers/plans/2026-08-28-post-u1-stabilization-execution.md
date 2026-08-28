# Post-U1 Stabilization and M0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge repository truth after accepted U1, complete M0 measurement correctness before any performance claim, then prepare clean architecture boundaries for the first provider/catch-up product slice.

**Architecture:** Keep each concern independently reviewable. U0 remains unmerged characterization provenance, U1 is accepted product behavior, M0 changes measurement authority only, and later architecture/provider work must not be stacked into M0. Git/GitHub remain live-state authorities; durable documents record only accepted checkpoints.

**Tech Stack:** Kotlin, Room 3, Compose for TV, Navigation 3, Media3, PowerShell/Bash CI harnesses, GitHub-hosted Actions, canonical Android TV API26/API36 emulators.

**Spec:** Existing authorities #179, #205, #212, #184, `.work/ARCHITECTURE.md`, `.work/ROADMAP.md`, and #27/#178 for measurement correctness.

## Global Constraints

- Repository-owned Android TV identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- 1080p/720p/density/stress profiles reuse the canonical AVDs; no third persistent MuxTV AVD.
- No performance/DB/buffer/cache conclusion is accepted before M0 measurement correctness.
- `MuxTvPlaybackService` remains the single ExoPlayer/MediaSession/semantic-seek mutation owner.
- No raw URL, header, credential, token or exception payload enters durable diagnostics/evidence.
- PR #190 remains compatibility evidence only, never a dependency mega-merge.
- Provider expansion after M0 starts with a minimal capability seam, one Xtream Live vertical slice, then provider catch-up; DVR/local timeshift/Stalker remain later independent decisions.

---

### Task 1: Accept U1 and retire completed characterization tracking

**Files:** none in product tree.

**Produces:** accepted `main` containing #213; #188 closed completed; #189 closed unmerged as characterization provenance.

- [x] Re-read #213 exact head and verify current workflow verdicts.
- [x] Squash-merge #213 with expected-head SHA protection.
- [x] Verify #212 auto-closes.
- [x] Verify #189 exact-head characterization workflows are green.
- [x] Record final U0 disposition on #188 and close it completed.
- [x] Record why #189 is retained as immutable evidence but not merged; close it unmerged.

### Task 2: Synchronize durable repository truth

**Files:**
- Modify: `README.md`
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Modify: `.work/ROADMAP.md`
- Create: `docs/superpowers/plans/2026-08-28-post-u1-stabilization-execution.md`

**Produces:** reviewed snapshot at accepted U1 main with GitHub-hosted CI truth and M0 as current stabilization owner.

- [ ] Replace obsolete self-hosted README wording with GitHub-hosted Windows/Linux + KVM execution while preserving local verification commands.
- [ ] Advance reviewed snapshot to `main@4a6634f51cb03f90708b7d1f02ff97632515d150` / PR #213.
- [ ] Record accepted #211 hosted-CI migration and accepted U0→U1 result.
- [ ] Remove U0/U1 from `known_gaps`; retain M0/#178 and release/lifecycle residuals.
- [ ] Keep #118 implementation closed while explicitly assigning remaining reboot/unlock/package-replace operational evidence to open release owner #31.
- [ ] Change current critical path to `M0 -> architecture boundary remediation -> provider capability/Xtream/catch-up -> isolated dependency/release closure` without moving DVR/local timeshift/Stalker forward.
- [ ] Run repository documentation/truth validation through the normal hosted validation PR gate.

### Task 3: Complete M0 measurement correctness in PR #178

**Files:** exact paths are resolved from the current #178 diff before editing; product Search/Room runtime files are out of scope unless root-cause evidence contradicts the existing issue diagnosis.

**Consumes:** accepted post-U1 `main`; #27 measurement authority; #178 proven root-cause statement.

**Produces:** measurement harness where expected Search boundary is derived from the published result set rather than a global first-channel sentinel, with CI paths that actually trigger the measurement correctness gate.

- [ ] Restack/rebuild #178 onto accepted current `main` so its review diff contains measurement-only changes.
- [ ] Inspect the existing fixture helper/test and the measurement runner assertion; trace the expected-boundary value from fixture generation through published Search result IDs to assertion.
- [ ] Write/retain the failing regression contract for selective `canonical-49999`: expected boundary must be that published channel's programme boundary.
- [ ] Verify RED against the unfixed runner assertion, not a compile/setup failure.
- [ ] Apply the minimal runner change to call the pure fixture helper; do not change production Search semantics.
- [ ] Add a static workflow trigger-path RED requiring `measurement-variance-smoke.yml` to react to the measurement runner/tests it validates.
- [ ] Verify RED before workflow-path correction.
- [ ] Add only the missing trigger ownership and matching static harness assertion.
- [ ] Run focused unit/measurement correctness tests, then hosted validation and measurement variance smoke on the exact final head.
- [ ] Review artifacts/logs and require non-zero selected tests with no performance claim.
- [ ] Squash-merge #178 only after exact-head evidence is green.

### Task 4: Establish the post-M0 architecture gate

**Files:** implementation starts in separate PR(s) only after M0 merge.

**Produces:** executable dependency-direction contract before provider expansion.

- [ ] Add a static RED that captures the confirmed feature→adapter leaks from #202 and #201.
- [ ] Fix Sources inversion first: `feature:sources` consumes stable catalog/source-management ports while WorkManager/Room run-token ownership remains unchanged.
- [ ] Fix Player inversion second: `feature:player` consumes `player:api`; Media3 surface/controller types remain adapter-owned while the service stays the only player/seek owner.
- [ ] Validate each inversion as an independent host/device PR; do not combine them with provider implementation.

### Task 5: Start the first product-breadth train

**Files:** separate designs/issues/PRs under #184/#205 after architecture gates.

**Produces:** real provider capability without a provider mega-abstraction.

- [ ] Introduce the minimal `ProviderCapability`/descriptor seam required by existing UI/readiness flows; preserve #112 activation/readiness ownership.
- [ ] Implement exactly one Xtream Live vertical slice with defensive type normalization, existing credential boundaries and independent Live activation.
- [ ] Add sanitized Xtream fixtures to the compatibility corpus before claiming dialect support.
- [ ] Add first-class provider catch-up playback intent/resolution after Live is accepted; Guide launches past programmes without constructing provider URLs.
- [ ] Keep local timeshift, DVR, Stalker/Ministra, multiview and alternate player engines outside this train.

## Acceptance order

`#213 accepted -> U0 tracking retired -> truth sync accepted -> #178/M0 accepted -> architecture boundary PRs -> ProviderCapability -> Xtream Live -> provider catch-up -> dependency/release closure`.

No later item authorizes skipping an earlier evidence gate.