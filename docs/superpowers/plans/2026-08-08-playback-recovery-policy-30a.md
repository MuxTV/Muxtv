# Playback recovery policy #30A execution plan

**Issue:** #30  
**Base:** `main@e9dd0336716e27e9b51f4eb10da82169112e71d1`  
**Scope:** pure player/catalog policy only; no Media3 runtime, Room, Compose, WorkManager, provider protocol or alternate engine.

## Goal

Define the smallest deterministic contract that can later drive bounded same-channel playback fallback without weakening the existing process-owned Media3 player, first-rendered-frame success boundary, catalog truth or secret-redaction guarantees.

This slice deliberately does **not** choose product retry defaults. `maxAttempts` and total recovery duration are explicit inputs. The focused M3U 10k/50k series under #27 is parser evidence and does not justify playback retry timings. Product defaults remain deferred until later playback/network/device evidence exists.

## Existing boundary

Current `PlaybackCatalog` owns active/profile-visible channel truth and exposes a `PlayableChannel` with an ordered variant list plus one-at-a-time `resolveVariant(...)` access resolution. `PlayableVariant` itself contains locator/user-agent/referrer data, so #30A must **not** use it as the policy candidate type. Player API already owns playback state/error/session contracts, and `core:common` already owns `CanonicalChannelId` and `StreamVariantId`.

#30A therefore keeps raw locators/headers/credentials out of recovery-policy models. A later catalog adapter may convert accepted ordered variant identities into policy candidates, while actual locator/header resolution remains behind `PlaybackCatalog.resolveVariant(...)` one candidate at a time.

## Non-negotiable invariants

1. Every recovery plan is scoped to one canonical channel.
2. Preferred variant is first when it exists in the candidate set.
3. Remaining candidates retain deterministic source order.
4. Duplicate variant identities cannot create an unbounded retry loop.
5. A foreign-channel candidate is rejected, never silently accepted.
6. Attempt count is explicitly bounded.
7. Total recovery duration is explicitly bounded.
8. Cancellation/supersession invalidates the active recovery generation.
9. A stale generation cannot advance a newer one.
10. Temporary fallback success never persists a new preferred variant.
11. First rendered frame remains the only successful-playback completion boundary; #30A does not redefine success.
12. Policy/diagnostic `toString()` output contains no raw locator, request headers, tokens or credentials.

## Media3 and failure-classification boundary

Media3 `LoadErrorHandlingPolicy` retries are a lower-level loader concern. MuxTV same-channel candidate switching is an application/catalog policy. They must not be modeled as the same fallback mechanism.

#30A also must **not** encode transport-specific rules such as `HTTP 401 always stops recovery`. Different same-channel candidates may resolve through different source/access boundaries. The pure policy should consume a small already-classified recovery disposition such as `TRY_NEXT_CANDIDATE` or `STOP_RECOVERY`; #30B owns the contextual mapping from typed catalog/Media3 failure observations to that disposition.

#30B will count Media3 loader retry time inside the same total user-visible recovery deadline so loader retries × candidate attempts cannot grow without bound.

## TDD sequence

### RED 1 — preferred candidate first

Add only a JVM test in `player/api` that requires a pure recovery plan to put an explicitly preferred same-channel candidate first.

Expected failure: production recovery policy types/API do not exist yet.

Do not add production code before this RED executes on the current accepted source head.

### GREEN 1

Add only the minimal identity-only candidate/budget/plan types required to make RED 1 pass. No failure taxonomy, generation state or runtime code yet.

### RED/GREEN 2 — deterministic remainder order

Require candidates after the preferred one to retain source order.

### RED/GREEN 3 — duplicate identity

Require duplicate variant ids to be rejected or deterministically deduplicated by contract so one generation cannot retry the same identity indefinitely.

### RED/GREEN 4 — same-channel boundary

Require a candidate for another canonical channel to be rejected at plan construction.

### RED/GREEN 5 — attempt budget

Require a positive explicit `maxAttempts` and prove a decision cannot return more attempts than the configured bound.

### RED/GREEN 6 — total deadline

Use monotonic elapsed-time inputs in the pure API; do not read wall clock internally. Prove deadline exhaustion terminates recovery.

### RED/GREEN 7 — recovery disposition

Add only the smallest policy-level disposition needed to choose between advancing to the next same-channel candidate and stopping recovery. Do not classify DNS/TLS/HTTP/access/decoder failures inside the pure policy, and do not expose raw exception messages.

### RED/GREEN 8 — generation invalidation

Prove cancellation/supersession makes old decisions inert and stale callbacks cannot advance the next generation.

### RED/GREEN 9 — preferred identity remains immutable

Prove a temporary fallback success returns the successful candidate identity without rewriting the user-selected preferred identity.

## Verification gates

For pure #30A commits:

```powershell
./gradlew.bat :player:api:test --no-daemon
./gradlew.bat :catalog:api:test :player:api:test --no-daemon
```

Then repository Full exact-source validation before merge. Android device matrix is required only when the slice begins integrating real catalog/player runtime behavior; pure JVM policy should not consume emulator time merely to duplicate JVM assertions.

## Stop conditions

Stop and split scope if implementation requires any of the following during #30A:

- Media3 classes;
- `Context`, Service, Activity or ViewModel;
- Room entity/schema/migration;
- WorkManager;
- `PlayableVariant`, raw stream locator or request-header fields;
- cross-channel fallback;
- hidden default retry counts/deadlines;
- transport-specific failure classification;
- persistent preferred-variant mutation;
- a second ExoPlayer/player owner;
- alternate engine/Rust/libmpv work.

## Current state

Only RED 1 is authored in this branch. No production recovery policy code is added until the RED is actually observed on accepted `main@e9dd0336716e27e9b51f4eb10da82169112e71d1`.