# Issue #26 Final Setup and Reconnect Design

**Status:** accepted for execution by the explicit user request to audit, plan and implement the current unfinished repository work.

**Base:** `main` at `66cf8dbaddafa87be7bfd619515452ceb3c46354` after PR #37.

**Objective:** Close the remaining lifecycle/cancellation acceptance criteria of issue #26 without changing playback transport, adding fallback/TV Doctor, persisting cleartext approvals, or creating another player/session.

## Current verified baseline

PR #36 moved playback to request-scoped Media3 OkHttp data-source/media-source chains and executable redirect/header isolation. PR #37 added retryable controller ownership, disconnect invalidation, explicit controller application looper, callback-based cancellable future waiting, typed secret-free failures, and API 26/API 36 reconnect smoke evidence.

The remaining gap is product-level recovery while `PlayerRoute` is already visible and command-level cancellation ownership:

1. `PlayerRoute` does not currently restart its setup when the cached controller receives `onDisconnected`.
2. Cancelling the coroutine waiting for `sendCustomCommand` cancels only the result future. It does not provide an application protocol proving that a delayed setup cannot remain installed after the route attempt has been cancelled.
3. Repository truth documents still describe the pre-PR #36/#37 blocker and must be synchronized only after the new executable contracts are green.

## Approaches considered

### A. Expand PR #37 in place

Rejected. PR #37 already had green Full and DeviceMatrix evidence and a bounded ownership purpose. Mixing a new service command protocol into the same review unit would weaken rollback and make the evidence harder to attribute.

### B. Recreate the MediaSession/ExoPlayer from UI after disconnect

Rejected. This violates the process-owned playback invariant and would create competing player/session ownership.

### C. Separate setup-token protocol plus connection epoch

Selected.

- The connector publishes a monotonically increasing, process-local connection epoch when the currently owned controller disconnects.
- `PlayerRoute` observes that epoch. A change cancels the old `produceState` attempt and starts one bounded reconnect/setup attempt for the same route request.
- Every setup command carries a fresh opaque `PlaybackSetupId` separate from channel/source/credential identity.
- If the waiting coroutine is cancelled or times out, the connector posts a matching cancel command on the controller application looper.
- The service owns a bounded setup coordinator. A cancel arriving before install prevents installation. A cancel matching the currently installed setup clears/stops only that setup. A stale cancel never stops a newer setup.
- The service continues to own exactly one `ExoPlayer` and one `MediaSession`.

## Architecture

### 1. `PlaybackSetupId`

A small validated value type in `player:media3`.

- generated from UUID text by the connector;
- maximum 64 characters;
- ASCII letters, digits and hyphen only;
- redacted `toString()`; no channel/source/locator material;
- bundle encoding is bounded and versionless because it is an ephemeral command correlation ID, not durable storage.

### 2. `PlaybackSetupCoordinator<T>`

A pure Kotlin, service-owned state machine with no Android or Media3 dependency.

Responsibilities:

- remember a bounded set of cancelled setup IDs;
- reject an install whose ID was already cancelled;
- install a non-cancelled request and make its ID current;
- when cancelling the current ID, invoke `clearInstalled` exactly once;
- never clear a newer setup when a stale ID is cancelled;
- keep cancellation memory bounded with deterministic oldest-entry eviction;
- expose stable enum outcomes only, never request payloads or exception text.

It runs on the MediaSession application thread in production, but unit tests execute it synchronously.

### 3. Session command contract

Keep the existing setup action string for compatibility, but change its arguments to a bounded envelope:

```text
SET_PLAYBACK_REQUEST
  setup_id: String
  request: Bundle

CANCEL_PLAYBACK_SETUP
  setup_id: String
```

Only own-package controllers receive both commands. Malformed IDs or requests return `ERROR_BAD_VALUE`. A cancel for a valid unknown ID is idempotent success.

### 4. Connector API

Public coroutine API:

```kotlin
val connectionEpoch: StateFlow<Long>

suspend fun awaitPlaybackRequest(
    controller: MediaController,
    request: PlaybackSessionRequest,
    timeoutMillis: Long,
): SessionResult
```

`awaitPlaybackRequest` creates one setup ID, sends the setup envelope, and waits using the existing cancellable bridge. On timeout or parent cancellation it posts `CANCEL_PLAYBACK_SETUP` for that exact ID before propagating/mapping the failure. Cancel-command result values are not surfaced to UI and contain no secret payload.

The low-level setup method accepts an explicit setup ID for instrumentation tests.

### 5. Disconnect/reconnect signal

`ControllerConnectionRegistry.disconnected` returns whether it invalidated the currently owned controller. The connector increments a `MutableStateFlow<Long>` only for a matching owned disconnect. Unrelated/stale disconnect callbacks do not trigger UI retries.

`PlayerRoute` collects the public read-only flow and includes the epoch in the `produceState` keys. A disconnect therefore cancels the previous attempt and starts a new bounded connection plus setup. The request is resolved again from `PlaybackCatalog`, so no locator/header is stored in navigation or saveable state.

### 6. Player service ownership

`MuxTvPlaybackService` constructs one coordinator beside its one player/session.

- install callback performs the existing stop/clear/set/prepare/play sequence;
- clear callback stops and clears media items only when the cancelled ID is still current;
- service destruction still releases the one MediaSession and ExoPlayer;
- no additional service, player, controller cache or state container is added.

## Cancellation and race semantics

| Race | Required result |
|---|---|
| route cancelled before setup command creation | no setup command sent |
| cancel recorded before delayed setup is handled | setup rejected; player unchanged |
| setup installed, then matching cancel handled | matching playback stopped/cleared |
| setup A installed, setup B installed, then cancel A | B remains active |
| controller disconnects during visible Player | old attempt cancelled; epoch changes; one new bounded setup attempt starts |
| stale controller disconnect callback | no epoch change |
| connector closed with pending controller | existing PR #37 release ownership remains unchanged |

This protocol guarantees no cancelled setup remains the active service-owned request. It does not claim zero transient decoder work if SET is processed immediately before CANCEL; it guarantees deterministic final ownership and no stale playback surviving cancellation.

## Error and security boundaries

- `PlaybackSetupId.toString()` is redacted.
- No setup outcome contains `PlaybackSessionRequest`.
- Existing typed `MediaControllerOperationFailure` remains the UI boundary.
- Cancel failures are best-effort and not rendered because the original timeout/cancellation remains authoritative.
- Locator, query, cookie, Authorization, Referer and header values remain absent from logs, exception messages, focus tags, navigation and durable state.
- The command envelope is in-process Binder data only and is not persisted.

## Testing

### Pure JVM RED/GREEN contracts

`PlaybackSetupCoordinatorTest`:

- cancel-before-install rejects the install;
- matching active cancel clears exactly once;
- stale cancel does not clear a newer setup;
- repeated cancel is idempotent;
- cancellation memory remains bounded and deterministic;
- result/toString values contain no request payload.

`ControllerConnectionRegistryTest`:

- matching disconnect returns true;
- stale disconnect returns false.

`PlaybackSetupCommandCodecTest`:

- setup/cancel bundles round-trip valid IDs;
- malformed/missing ID or nested request is rejected;
- diagnostics redact the ID and request.

### Android integration

Extend `MediaSessionServiceSmokeTest`:

- setup with ID A succeeds;
- cancel A succeeds;
- setup with pre-cancelled ID B is rejected and does not replace the active/newer request;
- released controller reconnect remains covered.

Extend the Player journey only where deterministic service restart can be executed without shell-only assumptions. At minimum, API 26/API 36 instrumentation must prove command availability, setup/cancel semantics and fresh controller connection.

## Verification gates

1. Focused JVM tests.
2. `:player:media3:testDebugUnitTest` and Android-test compilation.
3. repository Full validation.
4. sequential API 26/API 36 DeviceMatrix with non-zero Media3/app tests.
5. artifact secret review.
6. documentation truth synchronization.
7. squash merge and close issue #26 only if every acceptance criterion is evidenced.

## Explicit non-goals

- persisted per-source/per-host cleartext approval UI;
- automatic variant fallback;
- TV Doctor;
- HLS/XMLTV corpus and benchmarks;
- EPG, Guide, Search, Favorites, Recent;
- R8/Baseline Profile/release signing;
- physical-device compatibility claims;
- Rust, libmpv or a second playback engine.
