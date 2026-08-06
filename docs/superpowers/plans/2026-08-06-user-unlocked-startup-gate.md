# User-unlocked startup gate implementation plan

**Issue:** #118  
**Base:** accepted `main` at `ec2b7743183b227ef54c16989d061ae5d4775dee`  
**Execution constraint:** self-hosted runner unavailable while this package is prepared. No merge or acceptance claim is allowed until exact-head validation returns.

## Objective

Prevent application startup from touching credential-encrypted Room/Keystore/DataStore-backed state before Android reports the current user unlocked, while preserving the existing WorkManager/scheduler ownership model.

MuxTV does **not** support functional Direct Boot in this package. The main database and secrets stay credential-protected.

## Current defect

`MuxTvApplication.onCreate()` previously launched all credential-encrypted startup work unconditionally:

1. `DatabaseInitializer.initialize()`;
2. stale EPG matching repair;
3. durable remote-source onboarding cleanup;
4. source refresh scheduler reconciliation;
5. EPG refresh scheduler reconciliation.

There is a second, less obvious boundary: those dependencies were injected directly into the Application. Resolving `DatabaseInitializer`/`EpgMatchingStore` reaches the singleton `MuxTvDatabaseComponents` provider, which calls `MuxTvDatabaseFactory.create(...)`. Therefore merely delaying method calls would not be a complete CE boundary if Hilt resolves that graph during Application injection.

## Invariants

1. Zero credential-encrypted startup work **and zero eager construction of the CE dependency graph** before `UserManager.isUserUnlocked == true`.
2. If already unlocked, startup runs immediately and exactly once.
3. If locked, register one process-scoped `ACTION_USER_UNLOCKED` receiver and defer startup.
4. Close the check/register race by re-reading `isUserUnlocked` after registration.
5. Duplicate `start()` calls or duplicate unlock signals cannot run startup twice.
6. Receiver registration is released once startup becomes eligible.
7. CE-bound Application dependencies use Dagger `Lazy` and are resolved only inside the post-unlock startup path.
8. Do not add a manifest boot receiver.
9. Do not mark the application or workers `directBootAware`.
10. Do not move Room, Keystore or DataStore state to device-protected storage.
11. Do not replace WorkManager or create a second scheduling lifecycle.
12. Existing unique-work reconciliation remains the post-unlock scheduling owner.
13. `BroadcastReceiver.onReceive()` only signals eligibility; the existing application coroutine owns the actual asynchronous startup work.

## Design

### Pure gate

Add a small `UserUnlockedStartupGate` with injected boundaries:

- `isUserUnlocked: () -> Boolean`;
- `registerUnlockListener: (() -> Unit) -> UserUnlockRegistration`;
- `onUnlocked: () -> Unit`.

The gate uses atomics to make two state transitions idempotent:

- listener registration requested at most once;
- credential-encrypted startup started at most once.

The registration handle is retained only long enough to unregister after startup eligibility is established. A post-registration state check handles the race where unlock happens between the first state read and receiver registration. Unlock signals also re-read the authoritative user state before publishing readiness.

### Hilt boundary

Inject the CE-dependent Application fields as `dagger.Lazy<T>`:

- `DatabaseInitializer`;
- `EpgMatchingStore`;
- `DurableRemoteSourceOnboarding`;
- `SourceRefreshScheduler`;
- `EpgRefreshScheduler`.

Do not call `.get()` until the gate publishes user-unlocked readiness. `HiltWorkerFactory` and the application coroutine scope remain ordinary injected infrastructure; worker dependencies continue to be created through Hilt/WorkManager when work actually executes.

### Android adapter

`MuxTvApplication` owns the dynamic receiver because its lifetime is the application process. It registers only for `Intent.ACTION_USER_UNLOCKED`, which Android documents as a registered-receiver-only signal for credential-encrypted storage availability. The gate re-checks `UserManager.isUserUnlocked` because Android explicitly warns that user state may have changed by broadcast delivery time.

#### API 33+ receiver-flag finding from offline review

The current authored branch uses `Context.RECEIVER_NOT_EXPORTED` on API 33+. That choice must **not** be accepted yet.

Current Android broadcast guidance says a context-registered receiver listening for system broadcasts should use `RECEIVER_EXPORTED`; `RECEIVER_NOT_EXPORTED` can miss broadcasts originating from privileged framework apps outside the system UID. Android's platform manifest lists `android.intent.action.USER_UNLOCKED` as a `protected-broadcast`, so third-party apps cannot legitimately spoof that action.

Required follow-up when execution is available:

1. add an executable Android contract around the registered `ACTION_USER_UNLOCKED` path on API33/current;
2. observe the current branch behavior before changing production code;
3. if the expected delivery gap is reproduced/confirmed, switch this single-action receiver to the exported system-broadcast registration path;
4. keep the authoritative `UserManager.isUserUnlocked` re-check even after the flag fix;
5. do not generalize `RECEIVER_EXPORTED` to unrelated app-internal receivers.

No production flag change is made while the runner is unavailable because the existing Package #118 code was already authored and TDD requires an executed failing contract before a behavior fix.

The existing startup coroutine is moved behind `launchCredentialEncryptedStartup()` without changing operation ordering or EPG matching best-effort failure semantics.

## Test-first contract

Add JVM tests before production code for:

1. already-unlocked startup -> callback once, no receiver registration;
2. locked startup -> no callback until unlock;
3. locked/spurious signal -> still no callback;
4. duplicate `start()` while locked -> one registration;
5. duplicate unlock signals -> one callback and one unregister;
6. unlock between pre-check and post-registration check -> callback once and registration released;
7. synchronous signal during listener registration -> callback once and eventual unregister.

Because the self-hosted runner is unavailable, these tests are authored test-first but **RED is not claimed as executed**. Exact execution is deferred to the acceptance section below.

## Documentation truth-sync

Update `.work/CURRENT-STATE.md` separately on the same fresh-base branch to reflect accepted `main` after #124 and distinguish accepted state from active draft work. Do not report #118 as accepted until merge.

## Acceptance when runner returns

Exact PR head must pass, at minimum:

1. app JVM unit tests, including `UserUnlockedStartupGateTest`;
2. app debug compilation and Hilt/KSP wiring;
3. static/diff review confirming CE Application fields remain lazy and no CE `.get()` occurs before the gate;
4. Product DeviceMatrix on API26 and current API;
5. a locked-user/unlock lifecycle scenario where the environment supports it;
6. explicit API33/current verification of `ACTION_USER_UNLOCKED` delivery and receiver flags before accepting the dynamic-receiver implementation;
7. existing source/EPG unique-work reconciliation checks;
8. no Room schema change;
9. no new manifest receiver/directBootAware component;
10. unresolved review threads = 0.

If the emulator cannot faithfully simulate pre-unlock credential storage, that limitation must be recorded and the physical-device reboot/unlock check remains part of #31 release hardening.

## Non-goals

- refresh while the device is locked;
- custom WorkManager initializer;
- custom BOOT_COMPLETED/PACKAGE_REPLACED receiver;
- moving secrets/database to device-protected storage;
- scheduler redesign;
- changing lease/run-token semantics;
- claiming reboot/package replacement acceptance without exact evidence.
