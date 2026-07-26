# Media3 Controller Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make MediaController connection ownership retryable, disconnect-aware and cancellation-safe without creating another player/session or mixing in active playback recovery, cleartext UI, corpus, EPG or TV Doctor.

**Architecture:** Keep `MuxTvPlaybackService` as the single owner of `ExoPlayer` and `MediaSession`. Extract a generic synchronized connection registry so failure, cancellation, disconnect and close races are executable JVM contracts without mocking Media3. Keep the existing future API for low-level instrumentation, add callback-based suspending connector operations for UI use, and refactor `PlayerRoute` to stop blocking an IO thread with `Future.get()`.

**Tech Stack:** Kotlin 2.4.10, coroutines 1.11.0, Guava `ListenableFuture` supplied through Media3, Media3 1.10.1, Android TV API 26/36, JUnit 4, Truth.

## Global Constraints

- Start from merged `main` commit `f241d0c7eb1b8dfbd89b81e3f21dee75aa34940e`.
- Preserve `minSdk = 26`.
- Preserve one process-owned `ExoPlayer` and one `MediaSession` in `MuxTvPlaybackService`.
- Do not create a player/controller in a Composable.
- Do not add Retrofit, Ktor, RxJava, a second player engine or a new state container.
- Never expose locator, query, cookie, Authorization, Referer, header value or raw exception text in UI, logs or diagnostics.
- Parent coroutine cancellation must propagate as `CancellationException`; it must not be converted into a user-visible failure.
- `MediaController.releaseFuture(...)` must run on the configured controller application looper.
- `MediaController.Listener.onDisconnected` invalidates the cached controller and does not release it again.
- This PR does not claim automatic service-restart recovery while Player is already visible; that is the following isolated PR.
- This PR does not enable production cleartext playback; persisted per-host approval remains a later isolated PR.

---

## File map

**Create**

- `player/media3/src/main/kotlin/app/muxtv/player/media3/ControllerConnectionRegistry.kt` — generic synchronized ownership state machine.
- `player/media3/src/test/kotlin/app/muxtv/player/media3/ControllerConnectionRegistryTest.kt` — failure/cancellation/disconnect/close race contracts.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/ListenableFutureAwait.kt` — callback-based cancellable suspend bridge.
- `player/media3/src/test/kotlin/app/muxtv/player/media3/ListenableFutureAwaitTest.kt` — success/failure/timeout/cancellation contracts.
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MediaControllerOperationFailure.kt` — typed secret-free failure family.

**Modify**

- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt` — registry ownership, explicit looper/listener, suspend operations.
- `player/media3/build.gradle.kts` — add `coroutines-test` for JVM contracts.
- `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt` — use suspend connector API and cancellation guards.
- `app/tv/src/androidTest/kotlin/app/muxtv/MediaSessionServiceSmokeTest.kt` — prove disconnect invalidation permits a fresh controller.
- `docs/superpowers/plans/2026-07-25-next-execution.md` — record PR #36 merge and next #26 boundaries only after implementation is green.

---

### Task 1: Generic connection ownership state machine

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/ControllerConnectionRegistry.kt`
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/ControllerConnectionRegistryTest.kt`

**Interfaces:**

```kotlin
internal class ControllerConnectionRegistry<T : Any>(
    private val releasePending: (ListenableFuture<T>) -> Unit,
    private val releaseConnected: (T) -> Unit,
) : AutoCloseable {
    fun acquire(start: () -> ListenableFuture<T>): ListenableFuture<T>
    fun complete(future: ListenableFuture<T>, result: Result<T>)
    fun disconnected(controller: T)
    override fun close()
}
```

- [ ] **Step 1: Write RED tests**

Add tests using `SettableFuture<String>` for all required transitions:

```kotlin
@Test fun `concurrent acquire shares pending future and caches success`()
@Test fun `failed future returns to idle and next acquire starts again`()
@Test fun `cancelled future returns to idle and next acquire starts again`()
@Test fun `disconnect invalidates only the matching connected instance`()
@Test fun `close releases connected instance exactly once`()
@Test fun `close releases pending future exactly once`()
@Test fun `late success after close is released instead of cached`()
@Test fun `acquire after close returns a failed future`()
```

Assertions must count `start`, `releasePending` and `releaseConnected` invocations, and use reference identity for connected objects.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest --tests "app.muxtv.player.media3.ControllerConnectionRegistryTest" --no-daemon
```

Expected: compilation failure because `ControllerConnectionRegistry` does not exist.

- [ ] **Step 3: Implement minimal registry**

Use one private lock and states `Idle`, `Connecting(future)`, `Connected(controller)`, `Closed`. Invoke release callbacks outside the synchronized block. `complete` must release a successful stale/late controller when the registry no longer owns the supplied future.

- [ ] **Step 4: Run GREEN**

Run the command from Step 2. Expected: all registry tests pass.

- [ ] **Step 5: Commit**

```text
feat: add retryable controller connection registry
```

---

### Task 2: Explicit MediaController looper, listener and release ownership

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/ControllerConnectionRegistryTest.kt`

**Interfaces:**

Keep the existing low-level API:

```kotlin
fun connect(): ListenableFuture<MediaController>
fun sendPlaybackRequest(
    controller: MediaController,
    request: PlaybackSessionRequest,
): ListenableFuture<SessionResult>
```

- [ ] **Step 1: Replace connector-owned mutable fields**

Remove `controller` and `pending` fields. Construct `ControllerConnectionRegistry<MediaController>` with callbacks that dispatch to the main/application looper.

- [ ] **Step 2: Build controllers with explicit application thread**

```kotlin
MediaController.Builder(applicationContext, token)
    .setApplicationLooper(mainHandler.looper)
    .setListener(controllerListener)
    .buildAsync()
```

- [ ] **Step 3: Wire completion**

The future listener runs on `mainExecutor`, converts completed `get()` into `Result<MediaController>`, and calls `registry.complete(future, result)`. Calling `get()` is allowed only inside the completion callback where the future is already done.

- [ ] **Step 4: Wire disconnect**

`MediaController.Listener.onDisconnected(controller)` calls `registry.disconnected(controller)`. It must not call `release()` because Media3 documents the controller as unavailable and already disconnected afterwards.

- [ ] **Step 5: Wire close and pending release**

`close()` delegates to registry. Pending release uses `MediaController.releaseFuture(future)` on the main/application looper. Connected release uses `controller.release()` on that same looper. Both paths must be idempotent.

- [ ] **Step 6: Run focused and compile verification**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest :player:media3:compileDebugAndroidTestKotlin --no-daemon
```

Expected: success.

- [ ] **Step 7: Commit**

```text
feat: invalidate disconnected MediaController connections
```

---

### Task 3: Cancellable ListenableFuture suspension

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/ListenableFutureAwait.kt`
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/ListenableFutureAwaitTest.kt`
- Modify: `player/media3/build.gradle.kts`

**Interface:**

```kotlin
internal suspend fun <T> ListenableFuture<T>.awaitCancellable(
    timeoutMillis: Long,
    cancelFutureOnCancellation: Boolean,
): T
```

- [ ] **Step 1: Add test dependency**

```kotlin
testImplementation(libs.coroutines.test)
```

- [ ] **Step 2: Write RED tests**

```kotlin
@Test fun `completed future resumes with value`() = runTest
@Test fun `failed future resumes with original cause`() = runTest
@Test fun `timeout cancels command future when requested`() = runTest
@Test fun `timeout does not cancel shared connection future`() = runTest
@Test fun `parent cancellation ignores late success`() = runTest
```

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest --tests "app.muxtv.player.media3.ListenableFutureAwaitTest" --no-daemon
```

Expected: compilation failure because `awaitCancellable` does not exist.

- [ ] **Step 4: Implement callback bridge**

Use `withTimeout(timeoutMillis)` and `suspendCancellableCoroutine`. Register one direct-executor listener. On completion, unwrap `ExecutionException.cause`; use `tryResume`/`completeResume` and ignore a result after the continuation is cancelled. `invokeOnCancellation` calls `future.cancel(true)` only when `cancelFutureOnCancellation` is true.

- [ ] **Step 5: Run GREEN**

Run Step 3 command. Expected: all await tests pass.

- [ ] **Step 6: Commit**

```text
feat: add cancellable Media3 future suspension
```

---

### Task 4: Typed connector operations

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MediaControllerOperationFailure.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt`

**Interfaces:**

```kotlin
enum class MediaControllerOperationFailure {
    ConnectorClosed,
    ConnectionTimedOut,
    ConnectionCancelled,
    ConnectionFailed,
    CommandTimedOut,
    CommandCancelled,
    CommandFailed,
}

class MediaControllerOperationException(
    val failure: MediaControllerOperationFailure,
) : Exception("Media controller operation failed: $failure")

suspend fun awaitController(timeoutMillis: Long): MediaController

suspend fun awaitPlaybackRequest(
    controller: MediaController,
    request: PlaybackSessionRequest,
    timeoutMillis: Long,
): SessionResult
```

- [ ] **Step 1: Add failure type**

The exception message contains only the stable enum value. Do not attach raw Media3/IPC exceptions as its message.

- [ ] **Step 2: Implement `awaitController`**

Call `connect().awaitCancellable(timeoutMillis, cancelFutureOnCancellation = false)`. Re-throw parent `CancellationException`. Map timeout, underlying future cancellation, connector-closed and other failures to stable reasons.

- [ ] **Step 3: Implement `awaitPlaybackRequest`**

Call the existing `sendPlaybackRequest(...).awaitCancellable(timeoutMillis, cancelFutureOnCancellation = true)`. Re-throw parent cancellation; map timeout/cancel/failure to stable reasons.

- [ ] **Step 4: Add focused mapping tests**

Test the mapping helpers with safe synthetic exceptions. Assert that `message` and `toString()` contain no synthetic locator/header fixture.

- [ ] **Step 5: Verify**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest :player:media3:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 6: Commit**

```text
feat: expose typed controller operations
```

---

### Task 5: Remove blocking Future.get from PlayerRoute

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt`

- [ ] **Step 1: Remove blocking imports/helper**

Delete `Future`, `Dispatchers`, `runInterruptible`, the local `awaitFuture` function and direct `withTimeout` plumbing.

- [ ] **Step 2: Await controller through connector**

```kotlin
val controller = controllerConnector.awaitController(CONTROLLER_TIMEOUT_MILLIS)
```

Map only `MediaControllerOperationException.failure` to fixed Russian UI messages. Never render exception text.

- [ ] **Step 3: Guard cancellation before command**

After catalog/channel/request resolution and immediately before `awaitPlaybackRequest`, call:

```kotlin
currentCoroutineContext().ensureActive()
```

This prevents a catalog implementation that returns after parent cancellation from sending a stale setup command.

- [ ] **Step 4: Await command and guard Ready publication**

Use `awaitPlaybackRequest`. Re-check `ensureActive()` before setting `PlayerRouteState.Ready`. Parent cancellation is re-thrown.

- [ ] **Step 5: Compile feature and app**

```powershell
.\gradlew.bat :feature:player:testDebugUnitTest :app:tv:compileDebugKotlin :app:tv:compileDebugAndroidTestKotlin --no-daemon
```

Expected: success and no `Future.get()` in `PlayerRoute.kt`.

- [ ] **Step 6: Commit**

```text
refactor: make Player setup cancellation-aware
```

---

### Task 6: Real connector disconnect/retry smoke journey

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/MediaSessionServiceSmokeTest.kt`

- [ ] **Step 1: Extend the existing real-service test**

Connect the first controller, verify the custom command is available, release the controller on the instrumentation main thread, then poll with a bounded deadline until `connector.connect()` yields a newly connected controller instance. Send the malformed command through the second controller and keep the existing `ERROR_BAD_VALUE` assertion.

- [ ] **Step 2: Keep cleanup deterministic**

`connector.close()` remains in `finally`. Do not log controller/session objects or command bundles.

- [ ] **Step 3: Compile instrumentation**

```powershell
.\gradlew.bat :app:tv:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 4: Commit**

```text
test: prove MediaController reconnect after disconnect
```

---

### Task 7: Full review and verification

**Files:**
- Modify after evidence: `docs/superpowers/plans/2026-07-25-next-execution.md`
- Modify this plan checkboxes only for completed steps.

- [ ] **Step 1: Static scope review**

Confirm changed files are limited to controller registry, future suspension, Player setup, one instrumentation journey, build dependency and plans. Reject changes to network transport, catalog schema, EPG, Guide, fallback, TV Doctor or visual design.

- [ ] **Step 2: Fast verification**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Fast -SourceBranch feat/media3-controller-lifecycle -SourceCommit <exact-head> -NoDaemon
```

- [ ] **Step 3: Full verification**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -SourceBranch feat/media3-controller-lifecycle -SourceCommit <exact-head> -NoDaemon
```

- [ ] **Step 4: DeviceCurrent RED/GREEN evidence**

Run the real service connector journey on API 36. If lifecycle behavior changes as planned, run the sequential API 26/API 36 DeviceMatrix before merge.

- [ ] **Step 5: Secret review**

Scan manifests, test-count JSON, validation logs, application logcat and screenshots for all synthetic locator/header fixtures. System image/Play Store URLs are environment noise and must be distinguished from MuxTV data.

- [ ] **Step 6: Self-review**

Check:

- failure/cancelled pending future can retry;
- disconnected controller is never returned again;
- close and late completion release exactly once;
- no blocking `Future.get()` remains in `feature/player`;
- parent cancellation is re-thrown;
- one player/session ownership is unchanged;
- no raw exception reaches UI;
- no active-session auto-reconnect claim is made yet.

- [ ] **Step 7: Update PR and merge**

Remove any temporary matrix workflow, pass final clean-head Full, mark ready, squash merge, and add evidence to issue #26. Keep #26 open for active-session reconnect and host approval.

---

## Subsequent isolated PRs after this plan

### PR B — active Player reconnect and stale setup generation

- expose disconnect events keyed to controller identity;
- permit one bounded automatic reconnect while Player remains visible;
- prevent reconnect loops/retry storms;
- introduce a setup generation/token so cancellation or a newer channel selection cannot publish/install stale state;
- execute current-TV service/session restart journey and API 26/API 36 matrix;
- preserve the same service-owned player/session.

### PR C — per-source/per-host cleartext approval

- resolve approval from durable source access policy, not from URL scheme;
- carry only a short-lived boolean/policy reference to playback;
- warning/confirmation UI for HTTP host;
- credentials over HTTP disabled by default with stronger explicit confirmation;
- exact-origin enforcement and secret-safe device journeys;
- define the Android platform cleartext capability boundary before enabling release HTTP.

### Then

1. close issue #26 only after PR B and PR C acceptance evidence;
2. issue #27 deterministic 1k/10k/50k corpus and benchmark gate;
3. issue #28 XMLTV/immutable EPG revisions;
4. issue #29 Guide/Search/Favorites/Recent;
5. issue #30 bounded fallback/TV Doctor;
6. issue #31 R8/Baseline Profile/physical alpha gate;
7. issue #33 visual modernization as small independent UI PRs, not mixed into functional packages.
