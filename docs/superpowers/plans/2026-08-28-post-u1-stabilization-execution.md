# Post-U1 Stabilization and M0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge repository truth after accepted U1 and accepted M0 measurement correctness, then complete clean architecture boundaries before the first provider/catch-up product slice.

**Architecture:** Keep each concern independently reviewable. U0 remains unmerged characterization provenance, U1 is accepted product behavior, M0 changes measurement authority only, and architecture/provider work stays in independent PRs. Git/GitHub remain live-state authorities; durable documents record only accepted checkpoints.

**Tech Stack:** Kotlin, Room 3, Compose for TV, Navigation 3, Media3, PowerShell/Bash CI harnesses, GitHub-hosted Actions, canonical Android TV API26/API36 emulators.

**Spec:** Existing authorities #179, #205, #212, #184, `.work/ARCHITECTURE.md`, `.work/ROADMAP.md`, and #27/#178 for measurement correctness.

## Global Constraints

- Repository-owned Android TV identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- 1080p/720p/density/stress profiles reuse the canonical AVDs; no third persistent MuxTV AVD.
- M0/#178 is accepted; later performance/DB/buffer/cache conclusions still require owner scope and reproducible before/after evidence.
- `MuxTvPlaybackService` remains the single ExoPlayer/MediaSession/semantic-seek mutation owner.
- No raw URL, header, credential, token or exception payload enters durable diagnostics/evidence.
- PR #190 remains compatibility evidence only, never a dependency mega-merge.
- Provider expansion starts only after #202 and #201, with a minimal capability seam, one Xtream Live vertical slice, then provider catch-up; DVR/local timeshift/Stalker remain later independent decisions.

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

**Produces:** reviewed snapshot at accepted M0 main with GitHub-hosted CI truth and #202/#201 as current stabilization owners.

- [x] Replace obsolete self-hosted README wording with GitHub-hosted Windows/Linux + KVM execution while preserving local verification commands.
- [x] Advance reviewed snapshot through accepted U1/#213 and accepted M0 `main@76816014180b30872cd0517b1d2f692d1850ae0f` / PR #178.
- [x] Record accepted #211 hosted-CI migration, accepted U0→U1 result and accepted M0 published-result Search-boundary correctness.
- [x] Remove U0/U1/M0 from `known_gaps`; retain architecture/provider/release/lifecycle residuals.
- [x] Keep #118 implementation closed while explicitly assigning remaining reboot/unlock/package-replace operational evidence to open release owner #31.
- [x] Change current critical path to `#202 -> #201 -> provider capability/Xtream/catch-up -> isolated dependency/release closure` without moving DVR/local timeshift/Stalker forward.
- [ ] Run repository documentation/truth validation through the normal hosted validation PR gate and merge #214.

### Task 3: Complete M0 measurement correctness in PR #178

**Files:** measurement/debug/test/CI only; production Search/Room query/ranking/schema files remain out of scope.

**Consumes:** accepted U1 `main`; #27 measurement authority; #178 proven root-cause statement.

**Produces:** measurement harness where expected Search boundary is derived from the published result set rather than a global first-channel sentinel, with bounded production-path admission evidence.

- [x] Restack/rebuild #178 onto accepted U1 main so its review diff contains measurement-only changes.
- [x] Trace the expected-boundary value from fixture generation through published Search result IDs to assertion.
- [x] Retain the regression contract for selective `canonical-49999`: expected boundary is the published channel's programme boundary.
- [x] Verify hosted RED against the unfixed routing/oracle contract.
- [x] Apply the minimal runner change to call the pure published-result-set fixture helper; do not change production Search semantics.
- [x] Reject the first over-broad 50k×5 automatic PR-gate design and restore #27 ownership: timed 50k remains manual stress evidence.
- [x] Add bounded `CatalogDatabaseMeasurementCorrectnessTest` over real Room/Search/EPG at 10k scale on canonical API36.
- [x] Prove both selective-last-channel and broad-top-100 published-boundary cases.
- [x] Run exact-head Hosted CI contract, Hosted validation, bounded API36 M0 correctness and API26/API36 database matrix.
- [x] Review the M0 artifact: exact source SHA, non-zero test count, zero failure/error/skip, `thresholdApplied=false`, `claimEligible=false`.
- [x] Squash-merge #178 with expected-head protection as `76816014180b30872cd0517b1d2f692d1850ae0f`.

### Task 4: Establish the post-M0 architecture gate

**Files:** implementation remains in separate PRs.

**Produces:** executable dependency-direction contracts before provider expansion.

- [x] Add static RED contracts capturing the confirmed feature→adapter leaks in #202 and #201.
- [ ] Accept Sources inversion first (#202 / PR #215): `feature:sources` consumes stable catalog/source-management/onboarding ports while WorkManager/Room run-token ownership remains unchanged.
- [ ] Accept Player inversion second (#201 / PR #216): `feature:player` consumes `player:api`; Media3 surface/controller types remain adapter-owned while the service stays the only player/seek owner.
- [ ] Preserve applied-vs-accepted semantic seek evidence and opaque/stable track identity during Player cutover.
- [ ] Validate each inversion as an independent exact-head host/device PR; do not combine them with provider implementation.

### Task 5: Start the first product-breadth train

**Files:** separate designs/issues/PRs under #184/#205 after architecture gates.

**Produces:** real provider capability without a provider mega-abstraction.

- [ ] Introduce the minimal `ProviderCapability`/descriptor seam required by existing UI/readiness flows; preserve #112 activation/readiness ownership.
- [ ] Implement exactly one Xtream Live vertical slice with defensive type normalization, existing credential boundaries and independent Live activation.
- [ ] Add sanitized Xtream fixtures to the compatibility corpus before claiming dialect support.
- [ ] Add first-class provider catch-up playback intent/resolution after Live is accepted; Guide launches past programmes without constructing provider URLs.
- [ ] Keep local timeshift, DVR, Stalker/Ministra, multiview and alternate player engines outside this train.

## Acceptance order

`#213 accepted -> U0 tracking retired -> #178/M0 accepted -> truth sync #214 accepted -> #202/#215 -> #201/#216 -> ProviderCapability -> Xtream Live -> provider catch-up -> dependency/release closure`.

No later item authorizes skipping an earlier evidence gate.
