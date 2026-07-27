# Media3 Setup Cancellation and Reconnect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining issue #26 lifecycle criteria by making visible Player routes reconnect after controller disconnect and by ensuring a cancelled setup cannot remain the active service-owned playback request.

**Architecture:** Preserve one process-owned `ExoPlayer` and `MediaSession`. Add a pure bounded setup coordinator and opaque setup command correlation ID, expose a connector connection epoch, make `PlayerRoute` restart one bounded attempt on epoch changes, and use an explicit cancel command for timeout/parent cancellation races.

**Tech Stack:** Kotlin 2.4.10, coroutines 1.11.0, StateFlow, Media3 1.10.1, Compose for TV, Android TV API 26/36, JUnit 4, Truth.

## Global Constraints

- Base is `main` commit `66cf8dbaddafa87be7bfd619515452ceb3c46354`.
- Preserve `minSdk = 26`.
- Preserve one process-owned `ExoPlayer` and one `MediaSession` in `MuxTvPlaybackService`.
- Do not create a player or controller inside a Composable.
- Do not persist setup IDs, playback locators, query values, cookies, Authorization, Referer or sensitive header values.
- Parent coroutine cancellation must propagate as `CancellationException` after best-effort setup cancellation is posted.
- Cancel commands must be sent on the configured controller application looper.
- A stale setup cancel must never stop a newer setup.
- Do not add fallback, TV Doctor, corpus, EPG, visual redesign, cleartext approval persistence, Rust, libmpv or a second engine.

---

## File map

**Create**

- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSetupId.kt` — opaque bounded setup correlation value.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSetupCoordinator.kt` — pure bounded service state machine.
- `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSetupCoordinatorTest.kt` — cancellation/install ownership contracts.
- `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSetupCommandCodecTest.kt` — setup/cancel Bundle contracts.
- `.github/workflows/pr38-device-matrix.yml` — temporary old/current acceptance workflow scoped to this branch.

**Modify**

- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt` — setup/cancel commands and bounded Bundle envelope.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt` — coordinator-backed setup/cancel handling.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/ControllerConnectionRegistry.kt` — matching-disconnect result.
- `player/media3/src/test/kotlin/app/muxtv/player/media3/ControllerConnectionRegistryTest.kt` — disconnect signal tests.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt` — connection epoch and best-effort setup cancel.
- `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt` — observe epoch and rerun bounded setup.
- `app/tv/src/androidTest/kotlin/app/muxtv/MediaSessionServiceSmokeTest.kt` — command protocol/device acceptance.
- `README.md`, `.work/CURRENT-STATE.md`, `.work/meta/status.yaml`, `docs/superpowers/plans/2026-07-25-next-execution.md` — repository truth after green evidence.

---

### Task 1: Opaque setup ID and pure cancellation coordinator

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSetupId.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSetupCoordinator.kt`
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSetupCoordinatorTest.kt`

**Interfaces:**

```kotlin
@JvmInline
value class PlaybackSetupId private constructor(private val value: String) {
    fun encoded(): String
    override fun toString(): String

    companion object {
        fun create(): PlaybackSetupId
        fun parse(raw: String?): PlaybackSetupId?
    }
}

internal enum class PlaybackSetupInstallResult { Installed, Cancelled }
internal enum class PlaybackSetupCancelResult { PendingCancelled, ActiveCleared, AlreadyCancelled }

internal class PlaybackSetupCoordinator<T : Any>(
    private val cancelledCapacity: Int = 64,
    private val install: (T) -> Unit,
    private val clearInstalled: () -> Unit,
) {
    fun install(id: PlaybackSetupId, value: T): PlaybackSetupInstallResult
    fun cancel(id: PlaybackSetupId): PlaybackSetupCancelResult
}
```

- [ ] **Step 1: Write RED tests**

Add focused tests:

```kotlin
@Test fun `cancel before install rejects the matching setup`()
@Test fun `cancel of active setup clears exactly once`()
@Test fun `stale cancel never clears a newer setup`()
@Test fun `repeated cancel is idempotent`()
@Test fun `cancel memory evicts the oldest id at the configured bound`()
@Test fun `setup id rejects malformed values and redacts diagnostics`()
```

Tests use a small `RequestRef` class and counters. Assert reference ownership and exact enum results. The bound test uses capacity `2`, cancels A/B/C, then proves A may install after deterministic eviction while B/C remain cancelled.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest --tests "app.muxtv.player.media3.PlaybackSetupCoordinatorTest" --no-daemon
```

Expected: compilation failure because production types do not exist.

- [ ] **Step 3: Implement minimal production types**

`PlaybackSetupId` accepts UUID-form text using `[A-Za-z0-9-]{1,64}` and returns `PlaybackSetupId(<redacted>)` from `toString()`.

Coordinator uses insertion-ordered `LinkedHashSet<PlaybackSetupId>`, tracks only `activeId`, and invokes callbacks outside no lock because MediaSession uses one application thread. `cancel` records the ID before checking active ownership. When capacity is exceeded, remove exactly the oldest ID.

- [ ] **Step 4: Run GREEN**

Run Task 1 RED command. Expected: all tests pass.

- [ ] **Step 5: Commit**

```text
feat: add bounded playback setup ownership
```

---

### Task 2: Session setup/cancel command envelope

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt`
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSetupCommandCodecTest.kt`

**Interfaces:**

```kotlin
internal data class PlaybackSetupCommand(
    val id: PlaybackSetupId,
    val request: PlaybackSessionRequest,
)

val cancelPlaybackSetupCommand: SessionCommand
fun setupArgs(id: PlaybackSetupId, request: PlaybackSessionRequest): Bundle
fun cancelArgs(id: PlaybackSetupId): Bundle
fun parseSetupArgs(args: Bundle): PlaybackSetupCommand?
fun parseCancelArgs(args: Bundle): PlaybackSetupId?
fun cancelled(): SessionResult
```

- [ ] **Step 1: Write RED codec tests**

```kotlin
@Test fun `setup args round trip id and request`()
@Test fun `cancel args round trip id`()
@Test fun `missing malformed or oversized setup id is rejected`()
@Test fun `missing nested request is rejected`()
@Test fun `command diagnostics do not expose setup id locator or headers`()
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest --tests "app.muxtv.player.media3.PlaybackSetupCommandCodecTest" --no-daemon
```

Expected: compilation failure for missing contract APIs.

- [ ] **Step 3: Implement minimal envelope**

Use private keys `setup_id` and `request`. Keep the existing action `SET_PLAYBACK_REQUEST`; add `CANCEL_PLAYBACK_SETUP`. Return a stable `SessionResult` code for cancelled setup using `SessionError.ERROR_SESSION_DISCONNECTED` only if available in Media3 1.10.1; otherwise use `SessionError.ERROR_UNKNOWN` and document the mapping in the test. Do not put ID/request values in result extras.

- [ ] **Step 4: Run GREEN**

Run Task 2 RED command. Expected: all codec tests pass.

- [ ] **Step 5: Commit**

```text
feat: add playback setup command envelope
```

---

### Task 3: Service-owned setup coordinator

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Extend: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSetupCoordinatorTest.kt`

**Production behavior:**

```kotlin
private lateinit var setupCoordinator: PlaybackSetupCoordinator<PlaybackSessionRequest>
```

Construct it after player creation:

```kotlin
setupCoordinator = PlaybackSetupCoordinator(
    install = ::install,
    clearInstalled = {
        player.stop()
        player.clearMediaItems()
    },
)
```

`onConnect` exposes both own-package commands. `onCustomCommand` routes setup and cancel separately.

- [ ] **Step 1: Add RED ownership tests**

Add:

```kotlin
@Test fun `install B then cancel A leaves B installed`()
@Test fun `cancel active A then install B clears once and installs B`()
```

These make the required service callback ordering explicit.

- [ ] **Step 2: Run RED**

Run Task 1 focused test command. Expected: new assertions fail until coordinator behavior is complete.

- [ ] **Step 3: Implement service routing**

- setup: parse envelope; `badValue` on malformed; call coordinator; return success or cancelled result;
- cancel: parse ID; `badValue` on malformed; call coordinator; return success for all valid idempotent outcomes;
- unsupported and permission paths remain unchanged;
- no request or ID is logged.

- [ ] **Step 4: Compile service and tests**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest :player:media3:compileDebugAndroidTestKotlin --no-daemon
```

Expected: success.

- [ ] **Step 5: Commit**

```text
feat: coordinate cancellable playback setup in service
```

---

### Task 4: Connection epoch and connector cancellation

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/ControllerConnectionRegistry.kt`
- Modify: `player/media3/src/test/kotlin/app/muxtv/player/media3/ControllerConnectionRegistryTest.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt`

**Interfaces:**

```kotlin
fun disconnected(controller: T): Boolean

val connectionEpoch: StateFlow<Long>

fun sendPlaybackRequest(
    controller: MediaController,
    setupId: PlaybackSetupId,
    request: PlaybackSessionRequest,
): ListenableFuture<SessionResult>
```

- [ ] **Step 1: Write RED registry tests**

```kotlin
@Test fun `matching disconnect reports invalidation`()
@Test fun `stale disconnect reports no invalidation`()
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest --tests "app.muxtv.player.media3.ControllerConnectionRegistryTest" --no-daemon
```

Expected: compile failure because `disconnected` returns `Unit`.

- [ ] **Step 3: Implement disconnect result and epoch**

Return true only when connected reference identity matches. In connector, increment a private `MutableStateFlow<Long>` with `update { it + 1 }` only when true and expose `asStateFlow()`.

- [ ] **Step 4: Add setup cancellation semantics**

`awaitPlaybackRequest`:

1. create `PlaybackSetupId`;
2. call `currentCoroutineContext().ensureActive()`;
3. send setup envelope;
4. await with command-future cancellation enabled;
5. on timeout, post cancel command then map timeout;
6. on parent cancellation, post cancel command then rethrow;
7. on future failure, preserve typed mapping.

`postCancel` must use `runOnApplicationLooper`. It must never throw into the caller. The cancel command result is ignored after attaching a direct no-op listener.

- [ ] **Step 5: Run GREEN**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest :player:media3:compileDebugAndroidTestKotlin --no-daemon
```

Expected: success.

- [ ] **Step 6: Commit**

```text
feat: expose controller reconnect generation
```

---

### Task 5: PlayerRoute reconnect ownership

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt`

- [ ] **Step 1: Add the executable dependency key**

Collect `controllerConnector.connectionEpoch` with Compose `collectAsState()` and include the current epoch in the `produceState` keys.

- [ ] **Step 2: Preserve cancellation gates**

Keep `ensureActive()` before request conversion/setup and before publishing `Ready`. Do not store `PlaybackSessionRequest` in `rememberSaveable` or Navigation.

- [ ] **Step 3: Compile feature and app**

```powershell
.\gradlew.bat :feature:player:compileDebugKotlin :app:tv:assembleDebug --no-daemon
```

Expected: success.

- [ ] **Step 4: Commit**

```text
feat: reconnect visible Player after service disconnect
```

---

### Task 6: Android TV acceptance journeys

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/MediaSessionServiceSmokeTest.kt`
- Create: `.github/workflows/pr38-device-matrix.yml`

- [ ] **Step 1: Write RED instrumentation path**

Add one test that:

1. connects an own-package controller;
2. sends valid setup A with a local debug/instrumentation-safe request and asserts success;
3. sends cancel A and asserts success;
4. pre-cancels B, then sends setup B and asserts non-success;
5. proves the session remains usable by installing C;
6. releases the controller and proves a fresh controller can reconnect.

Do not put locator/header fixtures in test names or assertion messages.

- [ ] **Step 2: Add temporary matrix workflow**

Copy the PR #37 workflow shape, scope it to `feat/media3-setup-reconnect`, and use unique names/concurrency/artifact names.

- [ ] **Step 3: Run Full and DeviceMatrix through GitHub Actions**

Expected:

- Full success on exact head;
- API 26 and API 36 profiles, no fallback unless explicitly reported;
- non-zero credentials/database/media3/app tests;
- zero failures/errors/skips.

- [ ] **Step 4: Review artifacts for known secret fixtures**

Check reports, logcat, manifests and screenshots. Record exact run IDs and test counts.

- [ ] **Step 5: Commit**

```text

test: verify playback setup cancellation and reconnect
```

---

### Task 7: Repository truth, cleanup and issue closure

**Files:**
- Delete: `.github/workflows/pr38-device-matrix.yml`
- Modify: `README.md`
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Modify: `docs/superpowers/plans/2026-07-25-next-execution.md`
- Modify: this plan (mark completed steps and record evidence)

- [ ] **Step 1: Synchronize factual status**

Record:

- PR #36 transport/header isolation merged;
- PR #37 controller lifecycle merged as `66cf8dbaddafa87be7bfd619515452ceb3c46354`;
- final setup/reconnect behavior and evidence;
- issue #26 completion only if every acceptance criterion is proved;
- next production package is issue #27 corpus/benchmarks;
- cleartext approval persistence remains a separate tracked follow-up and is not claimed.

- [ ] **Step 2: Remove temporary workflow**

Delete only after a green matrix is captured.

- [ ] **Step 3: Run final cleaned-head Full**

Expected: success on the exact head without the temporary workflow.

- [ ] **Step 4: Self-review**

- no placeholders or stale PR #34 tasks;
- README, CURRENT-STATE and status YAML agree;
- no planned module is reported as implemented;
- no public/physical-device claim;
- no secret fixture in changed text.

- [ ] **Step 5: Mark PR ready, squash merge and close issue #26**

Only after fresh exact-head Full and acceptance evidence.
