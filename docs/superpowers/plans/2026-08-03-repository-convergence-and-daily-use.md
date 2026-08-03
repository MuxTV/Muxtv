# Repository Convergence and Daily-Use Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge the current Room-v8 branch graph into accepted `main`, finish the pending Channels/Favorites product slices, and establish the next daily-use implementation sequence without mixing performance-only or native rewrites into the critical path.

**Architecture:** Keep Kotlin/Room/Media3 as the accepted runtime. Product work remains bounded behind `PlaybackCatalog`, `EpgGuideRepository`, `ChannelPreferencesRepository` and process-owned playback/session contracts. Performance work stays on independent measurement-gated PRs; Rust/UniFFI and alternate player/storage engines remain ADR-gated by repeated evidence.

**Tech Stack:** Kotlin 2.4.x, Coroutines/Flow, Compose for TV, Navigation 3, Room 3 schema v8, WorkManager, OkHttp, Media3, Android TV self-hosted validation.

## Global Constraints

- Repository: `MuxTV/Muxtv`; default branch: `main`.
- `minSdk = 26`; accepted Room schema is v8.
- Do not place locators, query tokens, cookies, credentials, provider identities, sensitive headers or raw exception text into public state, navigation, logs or diagnostics.
- No full-catalog/full-guide materialization in Compose.
- No FTS until bounded Room search is measured inadequate.
- Recent writes only after confirmed successful playback, never on click/open/failed resolve.
- Performance PRs require comparable before/after evidence; green compilation alone is insufficient.
- Rust/UniFFI, bundled SQLite, libmpv and a second player engine require a separate evidence-backed ADR.

---

### Task 1: Synchronize Repository Truth

**Files:**
- Modify: `README.md`
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Create: `docs/superpowers/plans/2026-08-03-repository-convergence-and-daily-use.md`

**Interfaces:**
- Consumes: accepted `main@9325e0b4b124402a8eb5b1731442bce40a5404a8`, PR #90/#91/#89/#83/#87 status and exact-head evidence.
- Produces: human- and machine-readable branch graph used for subsequent work.

- [ ] Replace obsolete #81/#86 references with clean #90/#91 replacements.
- [ ] Record #90 exact-head Full `30785039850` as green while keeping TV-device evidence as the remaining merge gate.
- [ ] Record #89 Full `30784628497` and API26/API36 matrix `30784628471` as green while keeping comparable allocation evidence mandatory.
- [ ] Keep #83/#87 measurement-gated and note that their current heads have no fresh PR workflow evidence.
- [ ] Keep Search → Recent → Guide as the next product sequence after #90/#91.
- [ ] Run exact-head documentation validation through the existing Full lane.

### Task 2: Add Product Android-TV Device Acceptance Lane

**Files:**
- Create on PR #90 branch: `.github/workflows/android-tv-product-device-matrix.yml`

**Interfaces:**
- Consumes: repository-owned `tools/android/Invoke-TvDeviceValidation.ps1 -Mode DeviceMatrix`.
- Produces: API26/API36 product-journey evidence for changes in `app/tv`, channel/player features and Media3 boundaries.

- [ ] Trigger only for product/UI/player paths plus the workflow/harness itself.
- [ ] Use the existing self-hosted Windows X64 runner and Android SDK initialization.
- [ ] Run the repository-owned API26/API36 DeviceMatrix sequentially.
- [ ] Upload logs, JSON, screenshots, instrumentation reports and outputs.
- [ ] Do not replace Full validation; this lane is product/device acceptance only.

### Task 3: Accept and Merge PR #90

**Files:**
- Review the final PR #90 diff only; no new feature scope after Task 2.

**Interfaces:**
- Consumes: exact-head Full, exact-head product DeviceMatrix, review-thread state.
- Produces: accepted Channels Now/Next + destination-scoped state + playback-session projection on `main`.

- [ ] Verify exact current head SHA.
- [ ] Verify Full success on that SHA.
- [ ] Verify API26/API36 product DeviceMatrix success and inspect journey/MediaSession evidence.
- [ ] Verify no unresolved review threads or semantic blockers.
- [ ] Mark PR ready only after evidence is complete.
- [ ] Squash-merge with `expected_head_sha` guard.
- [ ] Record the merge SHA in repository truth.

### Task 4: Clean-Rebuild Favorites on Accepted Main

**Files:**
- Transfer only the current PR #91 Favorites delta (16-file scope) onto a fresh branch from post-#90 `main`.
- Preserve Room v8 and all #90 accepted files as the base tree.

**Interfaces:**
- Consumes: `ChannelPreferencesRepository`, existing `user_channel_overlays.isFavorite`, accepted #90 Channels state owner.
- Produces: independent Favorites PR with no inherited #90 commit ancestry.

- [ ] Create a fresh `rebuild/channel-favorites-after-90` branch from accepted `main`.
- [ ] Copy exactly the #91 parent→head content delta; do not copy historical #86 blobs.
- [ ] Preserve the corrected `initialFocusRequester` navigation reference.
- [ ] Compare new branch against `main`; expected review surface is Favorites-only and no Room schema bump.
- [ ] Open a replacement PR or retarget only if the resulting diff is clean and independently reviewable.
- [ ] Close superseded stacked PR #91 only after the replacement is verified.
- [ ] Obtain exact-head Full + product DeviceMatrix and merge with SHA guard.

### Task 5: Continue Performance Work in Parallel, Not on the Product Critical Path

**Files:**
- PR #89: `core/database/.../RoomEpgMatchingStore.kt`, `RoomEpgGuideRepository.kt`.
- PR #83: existing Stage-1 core allocation branch.
- PR #87: `StreamingXmltvParser.kt`.
- Measurement reports under `.work/evidence` / `docs/performance` as already established by issue #27.

**Interfaces:**
- Consumes: deterministic corpus and `current-normal`, `old-edge-normal`, `current-low-ram` profiles.
- Produces: merge/no-merge decisions supported by comparable allocation/time/GC/peak-memory evidence.

- [ ] For #89, collect same-corpus/same-profile before/after allocation evidence before merge.
- [ ] For #83, obtain a fresh exact-head Full after the AndroidX Benchmark compatibility change, then rebuild cleanly on accepted `main`.
- [ ] For #87, collect XMLTV allocation evidence before claiming a win.
- [ ] Run 5× repetitions for all three issue #27 profiles and keep cross-profile interpretation separate.
- [ ] Classify measured operations as `hard-gate`, `warning-only` or `descriptive-only` only after variance is known.

### Task 6: Implement Search as the Next Daily-Use Slice

**Files (expected after design approval):**
- `catalog/api/...` bounded search query/result contract.
- `core/database/...` Room search DAO/repository implementation.
- `feature/search/...` destination-scoped ViewModel/UI.
- `app/tv/...` navigation/DI wiring.
- Focus and repository tests adjacent to those modules.

**Interfaces:**
- Consumes: canonical channel identity, active/visible profile overlay projection, current-policy active programme metadata.
- Produces: bounded/debounced search results without full catalog/guide materialization.

- [ ] Define one bounded query boundary covering effective channel name, number, group and active programme metadata.
- [ ] Keep debounce in screen state; repository receives normalized bounded queries.
- [ ] Exclude hidden/inactive channels and stale-policy EPG rows.
- [ ] Preserve D-pad focus and Player/Back state.
- [ ] Measure bounded Room query behavior before considering FTS.

### Task 7: Implement Recent, Then Bounded/Lazy Guide

**Files (expected after each design approval):**
- Recent: new profile-scoped durable history storage/repository and migration if required.
- Guide: bounded channel/time-window query contract, Room projection and lazy TV UI.

**Interfaces:**
- Recent consumes confirmed successful playback state; Guide consumes current-policy EPG matches/revisions.
- Produces bounded profile history and a viewport-limited Guide without whole-guide materialization.

- [ ] Persist Recent only after a confirmed successful playback transition.
- [ ] Bound retention and isolate rows by profile; do not overload `user_channel_overlays` for history.
- [ ] Build Guide queries around channel IDs + `[from,to]` + explicit limits.
- [ ] Keep programme/channel rows lazy and viewport-bounded.
- [ ] Preserve focus/list position across Player/Back and filter/route changes.

### Task 8: Finish Fallback/Doctor and Alpha Hardening

**Files:** Follow issue #30 for fallback/diagnostics and issue #31 for release hardening; keep them in separate reviewable PRs.

**Interfaces:**
- Produces deterministic recovery and evidence-bounded alpha claims.

- [ ] Implement bounded variant-attempt/time ladder with typed failure families and no retry storms.
- [ ] Ensure temporary fallback never mutates preferred variant implicitly.
- [ ] Implement redacted TV Doctor Lite/export.
- [ ] Enable/validate R8/resource shrinking and compiler metrics.
- [ ] Add Macrobenchmark + Baseline/Startup Profiles and process/native-memory evidence.
- [ ] Validate upgrade/Keystore/Room recovery and physical Android/Google TV/Fire TV before alpha claims.
- [ ] Produce signed alpha, changelog, SBOM/licenses and reproducible release checklist.

## Self-Review

- Spec coverage: current clean PR graph, daily-use issue #29, performance issue #27, recovery issue #30 and alpha issue #31 are all assigned to explicit tasks.
- Placeholder scan: future feature files are marked as expected only where design is not yet approved; no implementation claim is made for them.
- Type consistency: the plan preserves existing repository boundaries and does not introduce unapproved runtime abstractions.
