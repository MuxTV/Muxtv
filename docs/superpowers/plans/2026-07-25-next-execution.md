# MuxTV Next Execution Plan

> **For agentic workers:** execute tasks sequentially with RED/GREEN checkpoints. Every task must leave a reviewable, working increment.

**Goal:** close the durable onboarding and catalog-hardening stack, deliver the first safe Android TV source-entry flow, then replace visual selection with deterministic D-pad focus and harden playback transport.

**Architecture:** keep credentials and full locators outside Room, navigation keys, saved Compose state, logs, traces and diagnostics. Navigation 3 owns only serializable screen keys. The add-source session owns the opaque preparation token privately and reconstructs it after process death through the durable registry merged in PR #20.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 9.3.0, Compose BOM 2026.06.00, Navigation 3 1.1.4, Room 3.0.0, Hilt 2.60.1, Coroutines 1.11.0, Media3 1.10.1, OkHttp 5.3.0.

## Execution status — 2026-07-25

- PR #20 merged as `05ffb62d97034d17ed2cb00064a6a8d81d0e3344`.
- PR #22 merged as `755fe955a8b61f33117d3e83cec9c9a526e988b6` after fixing diagnostic tracing that incorrectly changed local-JVM importer results.
- PR #32 merged as `2c61bab514248677c7b78620d615a4567f32087b` with the secure source-entry wizard.
- Draft PR #34 is current and implements real focus restoration from issue #25; its first pure focus-anchor increment is complete.
- Issue #24 stays open only for the shared API 26/current-TV D-pad and Room instrumentation acceptance gate.
- Device validation remains a gate, not a substitute for delivering product behavior.

## Global constraints

- Preserve `minSdk = 26`.
- No raw playlist locator, query, user-info, authorization value, cookie, referrer value or opaque preparation token in navigation, `rememberSaveable`, `SavedStateHandle`, Room projections, logs, traces, screenshots or exception text.
- Use standard Compose Foundation `LazyColumn`/`LazyRow`; do not introduce deprecated TV lazy layouts.
- One functional concern per PR; squash merge to `main`.
- Run TV emulators sequentially on the self-hosted Windows runner.
- Do not add Rust, a second player engine, bundled SQLite, Paging, Retrofit, Ktor or global state frameworks without a separate evidence-backed ADR.

---

## Task 1: Durable onboarding registry — code completed (PR #20)

- [x] Reparent to `main`.
- [x] Add Room 3 migration contract for 3 → 4.
- [x] Add deterministic latest-active recovery.
- [x] Add bounded corrupted-row skip and RED/GREEN contract.
- [x] Commit exact generated schema v4.
- [x] Remove temporary schema-export workflow.
- [x] Confirm exact-head Full #172.
- [x] Mark ready and squash merge.
- [ ] Execute migration instrumentation in the consolidated focus/source-entry DeviceMatrix gate.

**Acceptance:** schema v4 contains only `preparationId`, `scheme`, `host`, `createdAtEpochMillis`, `expiresAtEpochMillis`; recovery never exposes the token through public UI state.

## Task 2: Catalog staging hardening — code completed (PR #22)

- [x] Restrict the diff to importer/database/dependency/test/plan files.
- [x] Make canonical, provider and stream-variant staging one Room transaction.
- [x] Assert rollback of all three write groups after a duplicate variant failure.
- [x] Preserve stable IDs with one import-scoped SHA-256 digest.
- [x] Preserve immutable 250/1 batch ownership and ordering.
- [x] Preserve original cancellation when discard fails.
- [x] Keep async trace names static and secret-free.
- [x] Make unavailable tracing a no-op so diagnostics cannot produce `StorageFailure`.
- [x] Pass exact-head Full #189.
- [x] Squash merge as `755fe955a8b61f33117d3e83cec9c9a526e988b6`.
- [ ] Execute the retained Room atomicity instrumentation contract in the consolidated DeviceMatrix gate.

**Acceptance:** duplicate stream-variant insertion rolls back all three write groups; stable IDs remain byte-compatible; no per-entry trace or user-controlled trace name exists; tracing never changes importer behavior.

## Task 3: Secure TV source-entry wizard — code completed (PR #32 / issue #24)

- [x] Define no-argument `AppDestination.AddSource`.
- [x] Make destinations serializable `NavKey` values.
- [x] Replace the transient back stack with `rememberNavBackStack`.
- [x] Add “Добавить источник” from loading, empty, failed and content states.
- [x] Build a session with private token and public redacted state.
- [x] Implement HTTPS prepare, HTTP approval, restore, activate and cancel states.
- [x] Mask the locator with `BasicSecureTextField` and clear it after preparation/disposal.
- [x] Intercept system Back and require cleanup before leaving.
- [x] Add unit contracts for sanitization, HTTP approval, restore, activation and cleanup retention.
- [x] Compile the complete Hilt/Navigation 3 graph.
- [x] Add a Compose semantics contract proving the masked field does not publish the locator as text.
- [x] Align serialization dependencies with the tracing baseline merged from PR #22.
- [x] Pass exact-head Full #192.
- [x] Squash merge as `2c61bab514248677c7b78620d615a4567f32087b`.
- [ ] Close issue #24 after the shared API 26/current-TV touch-free journey and deferred Room contracts execute.

**Acceptance:** HTTPS source can be prepared and activated; HTTP requires explicit approval; process recreation restores only a sanitized endpoint; Back cannot silently abandon a stored credential.

## Task 4: Real TV focus restoration — current (issue #25 / PR #34)

### 4.1 Pure focus-anchor policy

- [x] Introduce `FocusAnchor(itemKey, previousIndex, scrollOffset)`.
- [x] Resolve exact key first.
- [x] If removed, choose the nearest preceding valid position.
- [x] Clamp shrinking lists to a valid previous item.
- [x] Fall back to the first focusable item.
- [x] Cover reorder, removal, empty list and bounded-index cases with JVM contracts.
- [ ] Confirm exact-head Full for the synchronized PR #34 branch.

### 4.2 Channels implementation

- [ ] Replace the visual `•` marker with one `FocusRequester` per visible stable channel key.
- [ ] Save focused channel identity separately from `LazyListState` position.
- [ ] Scroll to the resolved target before `requestFocus()`.
- [ ] Restore actual focus after Player → Back.
- [ ] Prevent repeated requests during ordinary recomposition.
- [ ] Add bounded Compose restoration tests before device journeys.

### 4.3 Sources, Add Source and Player

- [ ] Define deterministic initial focus for every state.
- [ ] Restore the source/action that launched a modal or nested destination.
- [ ] Ensure disabled controls cannot receive or execute focus actions.
- [ ] Ensure cleanup-pending states focus the safe recovery action.

### 4.4 Executable TV journeys

- [ ] Add stable secret-free semantics/test tags.
- [ ] Execute non-zero D-pad journeys on the current TV image first.
- [ ] Execute the old supported edge (API 26, or nearest available old TV image recorded in evidence).
- [ ] Execute Room 3→4 migration and catalog atomicity contracts in the same sequential matrix.
- [ ] Close issues #24 and #25 only after their explicit journey criteria are evidenced.
- [ ] Add representative API 30 and low-RAM profiles after the browser/player journeys are stable, not on every ordinary PR.

## Task 5: Media3 transport and reconnect hardening (issue #26)

- [ ] Add `media3-datasource-okhttp` aligned to Media3 1.10.1.
- [ ] Use immutable per-playback request headers; never mutate one shared factory between channels.
- [ ] Prove A → B manifest and segment requests do not leak headers.
- [ ] Define redirect, HTTP downgrade and cross-origin credential policy consistently with refresh.
- [ ] Replace blocking future waits with cancellation-aware suspend/timeout logic.
- [ ] Clear failed and disconnected controller instances so retry reconnects.
- [ ] Preserve one process-owned player/session.

## Task 6: Deterministic corpus and measured decisions (issue #27)

- [ ] Add a secret-free M3U/HLS corpus with malformed, large, redirect and encoding cases.
- [ ] Benchmark parse, staging, activation and first-frame paths.
- [ ] Record device/API/tool versions in evidence.
- [ ] Use measurements before adopting bundled SQLite, Rust, a second player engine or preload.

## Task 7: XMLTV, Guide and user value (issues #28–#29)

- [ ] Stream XMLTV into immutable EPG revisions.
- [ ] Keep active/previous-good revision semantics.
- [ ] Build now/next first, then bounded Guide windows and search.
- [ ] Add Favorites and Recent using canonical channel identity, not raw stream URLs.

## Task 8: Playback recovery and release hardening (issues #30–#31)

- [ ] Add bounded variant fallback and TV Doctor Lite with typed secret-free diagnostics.
- [ ] Add Baseline Profile only with before/after measurements.
- [ ] Update command-line tools before claiming the highest available TV API image.
- [ ] Use the release matrix: old supported edge, representative API 30, and highest actually available Android TV image.
- [ ] Run physical Android TV/Google TV/Fire TV playback and codec checks before alpha.

## Definition of done for the current sequence

1. PR #34 restores actual Channels focus and passes exact-head Full.
2. The sequential DeviceMatrix proves old/current Android TV behavior and executes the deferred Room contracts.
3. Issues #24 and #25 close with executable journey evidence rather than code-only claims.
4. Issue #26 removes shared mutable playback headers before stream fallback or diagnostics are added.
