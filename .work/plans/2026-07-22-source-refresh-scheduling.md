---
status: implementation
last_reviewed: 2026-07-22
branch: feat/source-scheduling
work_package: Phase 01 / WP-03
---

# Durable source refresh scheduling

## 1. Objective

Turn the already-merged secure one-shot remote M3U refresh path into a durable Android background workflow with:

- manual refresh;
- per-source periodic refresh;
- persisted policy and current status;
- bounded attempt history;
- retry classification;
- process/reboot recovery through WorkManager;
- protection from manual/periodic overlap;
- no source URL, token, cookie or authorization value in WorkManager or diagnostics.

This package does not add source-management screens, notifications, foreground long-running workers, EPG refresh or playback health probing.

## 2. Official and production references

Canonical Android references:

- WorkManager release notes: https://developer.android.com/jetpack/androidx/releases/work
- PeriodicWorkRequest: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
- ExistingPeriodicWorkPolicy: https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy
- Updating enqueued work: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/update-work
- Configuration.Provider: https://developer.android.com/reference/kotlin/androidx/work/Configuration.Provider
- HiltWorker: https://developer.android.com/reference/androidx/hilt/work/HiltWorker
- HiltWorkerFactory: https://developer.android.com/reference/androidx/hilt/work/HiltWorkerFactory
- Room migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions

Reference implementations:

- `android/nowinandroid`: Hilt-injected CoroutineWorker and WorkManager ownership.
- `AntennaPod/AntennaPod`: separate manual and periodic feed update work, network constraints and stable unique names.

MuxTV adaptations:

- use WorkManager `2.11.2`, already pinned in the repository;
- use `ExistingPeriodicWorkPolicy.UPDATE`, not replace/cancel-reenqueue, so policy changes retain scheduling history;
- use `ExistingWorkPolicy.KEEP` for repeated manual presses;
- add a Room lease because manual and periodic work have different unique names and could otherwise overlap;
- store only source identity and trigger in WorkManager Data;
- load URL, headers and credentials at execution time from Room + CredentialStore;
- record typed outcome codes rather than exception messages.

## 3. Module boundary

New module: `catalog:sync`.

Dependencies:

```text
catalog:sync
  -> catalog:refresh
  -> core:credentials
  -> core:database
  -> WorkManager
  -> AndroidX Hilt Work
```

The worker does not depend on Compose, player modules, feature modules or source-management UI.

## 4. Database schema v3

### source_refresh_policies

One row per source:

- `sourceId` primary key and cascading foreign key;
- `enabled`;
- `intervalMinutes`, minimum 15;
- `unmeteredOnly`;
- `requiresCharging`;
- `updatedAtEpochMillis`.

`unmeteredOnly` is deliberately used instead of a `wifiOnly` promise: Android can classify VPN, Ethernet and other links independently from the physical transport.

### source_refresh_states

One current state per source:

- IDLE / RUNNING / SUCCEEDED / FAILED / NEEDS_AUTH / CANCELLED;
- opaque UUID run token;
- start/completion times;
- last successful revision/time;
- typed failure family/code;
- optional safe HTTP status;
- skipped/warning counts.

No raw exception, URL, request header or credential reference is stored in state diagnostics.

### source_refresh_attempts

Recent bounded history:

- auto-generated ID;
- source ID and opaque run token;
- MANUAL / PERIODIC / STARTUP trigger;
- start/completion time;
- result state/family/code;
- revision and import counts;
- optional HTTP status.

Retention is capped at 25 attempts per source inside the completion transaction.

## 5. Concurrency contract

WorkManager uniqueness alone is insufficient because immediate and periodic work intentionally use different names:

```text
muxtv-source-refresh:<sourceId>
muxtv-source-periodic:<sourceId>
```

The Room lease therefore provides the final serialization boundary.

Acquire algorithm:

1. insert an IDLE state row if absent;
2. atomically update it to RUNNING with a new UUID token;
3. reject acquisition while a non-stale RUNNING row exists;
4. allow reclamation after 30 minutes;
5. complete only when state is RUNNING and the token still matches.

A late result from a reclaimed worker is ignored and cannot overwrite the newer run.

## 6. Work policies

### Manual/startup

- OneTimeWorkRequest;
- unique name per source;
- ExistingWorkPolicy.KEEP;
- CONNECTED network;
- exponential 30-second backoff;
- no expedited mode because playlist downloads/imports can be large and are not a UI-critical short task.

### Periodic

- PeriodicWorkRequest, minimum 15 minutes;
- ExistingPeriodicWorkPolicy.UPDATE;
- CONNECTED or UNMETERED according to policy;
- optional charging constraint;
- exponential 30-second backoff;
- cancellation when the policy is disabled.

The periodic interval is inexact by design and remains subject to Doze and vendor scheduling.

## 7. Retry matrix

Automatic retry, at most two retries / three total attempts:

- HTTP 408, 425, 429 and 5xx;
- timeout;
- DNS failure;
- generic transport I/O;
- importer storage failure;
- unexpected internal infrastructure failure.

No automatic retry:

- HTTP 401/403: NEEDS_AUTH;
- permanent 4xx;
- invalid/rejected URL;
- missing insecure-HTTP approval;
- TLS failure;
- redirect-policy rejection;
- compressed/decoded size limit;
- empty revision;
- parser/content limit;
- corrupted/missing/invalidated credential.

`CancellationException` is rethrown after a NonCancellable state transition to CANCELLED.

## 8. WorkManager/Hilt initialization

Application requirements:

1. implement `Configuration.Provider`;
2. inject `HiltWorkerFactory`;
3. pass it through `Configuration.Builder.setWorkerFactory`;
4. remove `androidx.work.WorkManagerInitializer` from the merged manifest;
5. call `WorkManager.getInstance(applicationContext)` only after injection;
6. initialize Room, then reconcile persisted policies.

The scheduler stores only the application Context and resolves WorkManager lazily, avoiding initialization during Hilt field construction.

## 9. Verification

### Unit

- successful outcome mapping;
- transient HTTP retry;
- authorization classification;
- TLS no-retry;
- stable network error codes.

### Device database contract

- overlapping lease rejection;
- stale lease reclamation;
- late old-token completion ignored;
- successful status and attempt insertion;
- policy round trip;
- no credential payload in policy data.

### Full gate

- catalog:sync unit tests;
- Room/KSP/Hilt compilation;
- app unit tests;
- debug APK;
- lint including catalog:sync;
- release APK.

### DeviceMatrix gate

Mandatory because this package changes persisted Room schema and WorkManager/Hilt application initialization:

1. old-edge Android TV image resolved by the repository harness;
2. API 36 Android TV;
3. Keystore, database and app connected tests on both;
4. no orphan emulator process;
5. evidence manifests bound to exact branch/head.

## 10. Known follow-ups

Not part of this package:

- source-management UI that creates and edits policies;
- explicit v2 fixture/exported-schema migration test cleanup;
- per-source refresh notification UX;
- foreground execution for exceptionally large provider catalogs;
- EPG scheduling;
- active catalog query contract;
- playback service.

The next code package after this merge is the active playback catalog contract, followed by the MediaSessionService-owned Media3 engine.
