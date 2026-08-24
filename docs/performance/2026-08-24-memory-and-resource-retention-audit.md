# Memory and resource-retention audit — 2026-08-24

## Purpose

Runner-free static audit of long-lived Android resources and explicit acquire/release ownership in MuxTV.

The goal is to distinguish actual lifecycle defects from intentional process/service lifetime retention before changing code. A process-lifetime singleton is not automatically a leak, and an object implementing `AutoCloseable` does not need Activity-scoped closing if its accepted owner is the application process.

No production code, Gradle configuration, Room schema, emulator inventory or workflow is changed by this document.

---

# 1. Summary

## No confirmed unbounded leak found

The inspected ownership paths generally have explicit bounds or release logic:

- user-unlock broadcast registration is released once startup publishes unlocked state;
- MediaController pending/connected/stale states have release paths;
- Playback service cancels its service scope and releases MediaSession/ExoPlayer on service destruction;
- external playback leases are capacity/TTL bounded and consume-on-claim;
- external HTTP origin grants are bounded to a fixed maximum;
- OkHttp source/playback clients share one process-level Dispatcher and ConnectionPool;
- Room database/components are application-singleton owned;
- Compose `produceState`/remembered coroutine scopes follow composition lifetime;
- Add Source clears transient locator text/state when its session leaves composition.

## Confirmed ownership debt

`RecentPlaybackModule` constructs a second process-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.IO)` despite the app already providing `@ApplicationIoScope`. This is lifecycle/test-supervision inconsistency, not an observed leak. #203 owns the correction.

## Measurement question, not current defect

The singleton `MuxTvMediaControllerConnector` caches one connected `MediaController`. A connected controller keeps the MediaSessionService binding available; the service owns a process/service-lifetime ExoPlayer until the service is destroyed.

This may be exactly the desired background-playback/fast-reuse policy. Static code alone cannot say whether idle retention is too expensive on weak TVs.

Therefore measure the actual idle-after-stop/back memory and service/player resource state under #109/#27 before adding disconnect/release churn.

---

# 2. User-unlock receiver ownership

`UserUnlockedStartupGate` is race-safe in both directions:

```text
check unlocked
   ↓ no
register listener
   ↓
check unlocked again
```

It additionally handles a synchronous callback during registration.

The registration handle is stored atomically and `publishUnlocked()` performs:

```text
startupStarted.compareAndSet(false, true)
        ↓
unregisterUnlockListener()
        ↓
onUnlocked()
```

`unregisterUnlockListener()` uses `getAndSet(null)`, preventing duplicate release.

**Verdict:** no retained dynamic receiver identified after startup reaches unlocked state.

---

# 3. Application coroutine ownership

## Preferred process scope

`AppInfrastructureModule` provides one singleton:

```text
@ApplicationIoScope
CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

`MuxTvApplication` uses it for credential-encrypted startup work.

This is a reasonable process-lifetime owner.

## Duplicate Recent scope

`RecentPlaybackModule` constructs another standalone process-lifetime IO scope for `RecentPlaybackObserver`.

Because the observer is singleton and its jobs are finite ancillary Room writes, this is not evidence of runaway job retention. It is nevertheless unnecessary lifecycle fragmentation.

**Action:** #203 — inject the existing application scope after a RED construction/static test.

---

# 4. Application startup coroutine sequencing

`MuxTvApplication.startCredentialEncryptedStartup()` currently launches one sequential child coroutine:

```text
database initialize
   ↓
repair stale EPG matching (best effort internally)
   ↓
cleanup expired source onboarding
   ↓
source refresh scheduler reconcile
   ↓
EPG refresh scheduler reconcile
```

The EPG matching repair is explicitly best-effort. The other stages are not independently failure-isolated inside this coroutine.

This is not a memory leak. It is a resilience observation:

- database initialization failure reasonably blocks DB-dependent work;
- an onboarding cleanup failure may currently prevent both scheduler reconciliations;
- a source scheduler reconcile exception may prevent EPG scheduler reconcile.

Do not split these blindly. First classify which stages are prerequisites versus independent best-effort maintenance, then add tests if a failure-isolation correction is justified.

**Priority:** P2 resilience, not alpha memory blocker from static evidence alone.

---

# 5. MediaController connection registry

`ControllerConnectionRegistry` has four explicit states:

```text
Idle
Connecting(future)
Connected(controller)
Closed(releasedPending?)
```

Important properties:

- concurrent acquisition reuses the same pending/connected resource;
- a failed connection returns to Idle;
- completion of a stale future releases any controller it produced;
- `disconnected(controller)` only clears the currently owned controller;
- `close()` releases either pending future or connected controller once;
- a completion racing after close releases a stale controller.

**Verdict:** registry itself does not show duplicate connected-controller accumulation.

---

# 6. Singleton MediaController lifecycle

`AppModule` provides `MuxTvMediaControllerConnector` as `@Singleton`.

The connector:

- stores application Context, not Activity Context;
- has one Handler/Executor on main looper;
- owns one `ControllerConnectionRegistry`;
- installs one Player.Listener on the observed controller;
- removes that listener on disconnect/release;
- releases pending MediaController futures through `MediaController.releaseFuture`;
- releases connected controller in `releaseConnected`;
- exposes `close()`.

There is no app shutdown callback that calls `close()`, so accepted runtime behavior is effectively process-lifetime connector ownership.

## Why this is not automatically a leak

Android application singletons normally live until process death. Reconnecting/releasing MediaController on every recomposition or route transition could create more churn and race exposure than keeping one process controller.

## What must be measured

Because MediaController binds the MediaSessionService and the service owns ExoPlayer, capture:

```text
baseline app process RSS/PSS
first Player entry
first frame
Stop
Back to Channels/Home
30s idle
5min idle
second Player entry
```

Record where possible:

- process PSS/RSS;
- Java/native heap;
- MediaSessionService lifecycle state;
- ExoPlayer/player existence;
- decoder/codec resource state where observable;
- connection count/epoch;
- second-start latency.

Compare the current process-lifetime controller against any future explicit-idle-release candidate only if the retained budget is materially harmful.

A valid result is **keep the singleton connection**.

---

# 7. Playback service resources

`MuxTvPlaybackService` owns:

- service `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`;
- ExoPlayer;
- MediaSession;
- setup/recovery jobs;
- active player listener;
- seek controller/generation;
- active playback attempt state.

Service destruction cancels/clears the owned work and releases player/session.

Setup generation/callback gates prevent stale async callbacks from taking ownership after replacement.

**Verdict:** no second player or orphaned service job owner identified from static source.

Do not add Activity/feature-owned ExoPlayer release while addressing #201.

---

# 8. External playback leases

`InMemoryExternalPlaybackLeaseRegistry` is deliberately bounded:

```text
capacity = 8
TTL = 10 minutes
consume on claim
remove session on replacement
oldest entry evicted at capacity
expired entries evicted during registration/claim
```

Descriptors may contain sensitive playback locators, but they cannot accumulate without bound.

**Verdict:** memory retention is bounded; security requirement remains that descriptor contents are not logged/exported.

---

# 9. External cleartext-origin grants

`ExternalPlaybackOriginGrants` is bounded to 32 origins and persists only exact `scheme://host:port` origins, not path/query/credentials.

Corrupted restore fails closed and clears persistent storage.

**Verdict:** bounded persistent/in-memory state; no retention problem found.

---

# 10. OkHttp resources

`MuxTvHttpResources` owns a process-shared:

```text
Dispatcher
ConnectionPool
base OkHttpClient
```

Source and playback clients derive from the same base, and `playbackFor(rootUrl, ...)` builds a client that still shares the same dispatcher/pool.

This is the correct default pattern for avoiding independent connection-pool/thread proliferation.

## Process-lifetime close policy

The singleton resources are not explicitly shut down during normal app lifetime. That is expected for a process-owned OkHttp client.

Do not call:

```text
dispatcher.executorService.shutdown()
connectionPool.evictAll()
```

on Player route exit; doing so would disrupt source/EPG/playback users sharing the resources.

**Verdict:** preserve shared process ownership.

---

# 11. Room database lifetime

`MuxTvDatabaseFactory.create()` creates one Room database inside the `@Singleton MuxTvDatabaseComponents` graph used by the app.

Repositories/initializers retain DAO/database references for process lifetime.

Android app lifetime is the accepted DB owner; closing/reopening Room per Activity or feature would be worse.

**Verdict:** no route-scoped Room lifetime defect identified.

`createInitializer(context)` separately creates a database-backed initializer for specialized use. If production call sites appear later, ensure repeated calls are not used as a hidden second database factory. Current app DI uses the singleton components path.

---

# 12. Compose / Player route async lifetime

`PlayerRoute` uses `produceState` for controller/catalog/start setup and `rememberCoroutineScope` for approval mutation.

Both are composition-lifecycle scopes, so route replacement/disposal cancels their child work.

Current controller connection itself intentionally survives because its owner is the application singleton, not the Composable.

This distinction matters:

```text
route async work        -> composition lifetime
controller connection   -> process lifetime
player                  -> MediaSessionService lifetime
```

Do not conflate these while fixing #201.

---

# 13. Add Source secret state lifetime

`AddSourceRoute` uses `TextFieldState` for the source locator and `SourceEntrySession` for transient onboarding state.

On dispose it:

```text
locatorState.clearText()
session.clearTransientLocator()
```

The UI also clears the locator when transitioning into confirmation.

This is a good secret-retention practice and should survive #202/#204 refactors.

**Verdict:** no obvious long-lived plaintext locator state found in the inspected UI path.

---

# 14. Resource ownership matrix

| Resource | Current owner | Bound/release | Finding |
|---|---|---|---|
| unlock BroadcastReceiver | `UserUnlockedStartupGate` | unregister on successful publication | GREEN |
| application IO jobs | app singleton scope | process lifetime | GREEN |
| Recent ancillary IO jobs | separate singleton scope | process lifetime | AMBER: consolidate via #203 |
| Room DB | singleton DB components | process lifetime | GREEN |
| OkHttp dispatcher/pool | singleton HTTP resources | process lifetime | GREEN |
| MediaController | singleton connector | disconnect/close/process death | AMBER: measure idle retention |
| ExoPlayer | MediaSessionService | service destroy | GREEN ownership; measure idle budget |
| service jobs/listeners | MediaSessionService | generation cleanup/service destroy | GREEN |
| external descriptor leases | singleton registry | TTL/capacity/consume/remove-session | GREEN |
| external HTTP grants | singleton bounded store | cap/revoke | GREEN |
| Player route coroutines | Compose | composition cancellation | GREEN |
| source locator edit state | AddSource composition/session | clear on confirm/dispose | GREEN |

---

# 15. Measurement additions for #109/#27

Before changing controller/service idle policy, add a lifecycle measurement scenario:

```text
launch app
→ enter channel
→ first frame
→ stop
→ back
→ idle 30s
→ idle 5m
→ reopen same/other channel
```

Compare:

- memory delta from pre-player baseline;
- whether memory returns to an acceptable steady state;
- next-start latency benefit from retention;
- decoder resource release evidence;
- no second ExoPlayer/controller creation.

Only if retained resources are materially harmful should an explicit idle disconnect/player release policy be designed.

Do not combine this with buffer tuning; otherwise memory causality becomes ambiguous.

---

# 16. Future static/runtime acceptance

After execution is available:

1. host tests for controller registry close/disconnect races;
2. host/DI test for one application IO scope after #203;
3. API36 lifecycle instrumentation for repeated Player enter/stop/back loops;
4. API26 compatibility loop;
5. memory snapshots before/after repeated loops;
6. verify external lease registry remains bounded;
7. verify no listener count or connection epoch growth per repeated route entry;
8. physical weak-TV evidence before claiming memory optimization.

No third persistent AVD is permitted.

---

# 17. Non-goals

- releasing process-shared OkHttp resources on route exit;
- closing/reopening Room per screen;
- replacing MediaSessionService;
- adding a second player pool;
- turning every singleton into a Closeable owner;
- adding lifecycle code without measured retention evidence;
- interpreting JVM heap alone as total decoder/native memory;
- claiming weak-TV memory behavior from emulator throughput.
