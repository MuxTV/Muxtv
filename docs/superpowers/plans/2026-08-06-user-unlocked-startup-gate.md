# User-unlocked startup gate implementation plan

**Issue:** #118  
**Base:** accepted `main` at `ec2b7743183b227ef54c16989d061ae5d4775dee`  
**Execution constraint:** self-hosted runner unavailable while this package is prepared. No merge or acceptance claim is allowed until exact-head validation returns.

## Objective

Prevent application startup from touching credential-encrypted Room/Keystore/DataStore-backed state before Android reports the current user unlocked, while preserving the existing WorkManager/scheduler ownership model.

MuxTV does **not** support functional Direct Boot in this package. The main database and secrets stay credential-protected.

## Current defect

`MuxTvApplication.onCreate()` currently launches all credential-encrypted startup work unconditionally:

1. `DatabaseInitializer.initialize()`;
2. stale EPG matching repair;
3. durable remote-source onboarding cleanup;
4. source refresh scheduler reconciliation;
5. EPG refresh scheduler reconciliation.

If the process is created before user unlock, that sequence has no explicit lifecycle guard.

## Invariants

1. Zero credential-encrypted startup work before `UserManager.isUserUnlocked == true`.
2. If already unlocked, startup runs immediately and exactly once.
3. If locked, register one process-scoped `ACTION_USER_UNLOCKED` receiver and defer startup.
4. Close the check/register race by re-reading `isUserUnlocked` after registration.
5. Duplicate `start()` calls or duplicate unlock signals cannot run startup twice.
6. Receiver registration is released once startup becomes eligible.
7. Do not add a manifest boot receiver.
8. Do not mark the application or workers `directBootAware`.
9. Do not move Room, Keystore or DataStore state to device-protected storage.
10. Do not replace WorkManager or create a second scheduling lifecycle.
11. Existing unique-work reconciliation remains the post-unlock scheduling owner.
12. `BroadcastReceiver.onReceive()` only signals eligibility; the existing application coroutine owns the actual asynchronous startup work.

## Design

### Pure gate

Add a small `UserUnlockedStartupGate` with injected boundaries:

- `isUserUnlocked: () -> Boolean`;
- `registerUnlockListener: (() -> Unit) -> UserUnlockRegistration`;
- `onUnlocked: () -> Unit`.

The gate uses atomics to make two state transitions idempotent:

- listener registration requested at most once;
- credential-encrypted startup started at most once.

The registration handle is retained only long enough to unregister after startup eligibility is established. A post-registration state check handles the race where unlock happens between the first state read and receiver registration.

### Android adapter

`MuxTvApplication` owns the dynamic receiver because its lifetime is the application process. It registers only for the protected system action `Intent.ACTION_USER_UNLOCKED`; API 33+ uses `Context.RECEIVER_NOT_EXPORTED`. The broadcast callback re-checks `UserManager.isUserUnlocked` before allowing the gate to publish readiness, matching Android's guidance that user state may have changed by delivery time.

The existing startup coroutine is moved behind `launchCredentialEncryptedStartup()` without changing its ordering or failure semantics.

## Test-first contract

Add JVM tests before production code for:

1. already-unlocked startup -> callback once, no receiver registration;
2. locked startup -> no callback until unlock;
3. duplicate `start()` while locked -> one registration;
4. duplicate unlock signals -> one callback and one unregister;
5. unlock between pre-check and post-registration check -> callback once and registration released;
6. synchronous signal during listener registration -> callback once and eventual unregister.

Because the self-hosted runner is unavailable, these tests are authored test-first but **RED is not claimed as executed**. Exact execution is deferred to the acceptance section below.

## Documentation truth-sync

Update `.work/CURRENT-STATE.md` separately on the same fresh-base branch to reflect accepted `main` after #124 and distinguish accepted state from active draft work. Do not report #118 as accepted until merge.

## Acceptance when runner returns

Exact PR head must pass, at minimum:

1. app JVM unit tests, including `UserUnlockedStartupGateTest`;
2. app debug compilation and Hilt/KSP wiring;
3. Product DeviceMatrix on API26 and current API;
4. a locked-user/unlock lifecycle scenario where the environment supports it;
5. existing source/EPG unique-work reconciliation checks;
6. no Room schema change;
7. no new manifest receiver/directBootAware component;
8. unresolved review threads = 0.

If the emulator cannot faithfully simulate pre-unlock credential storage, that limitation must be recorded and the physical-device reboot/unlock check remains part of #31 release hardening.

## Non-goals

- refresh while the device is locked;
- custom WorkManager initializer;
- custom BOOT_COMPLETED/PACKAGE_REPLACED receiver;
- moving secrets/database to device-protected storage;
- scheduler redesign;
- changing lease/run-token semantics;
- claiming reboot/package replacement acceptance without exact evidence.
