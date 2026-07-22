# Source refresh scheduling review

## Scope

Branch: `feat/source-scheduling`

This checkpoint adds durable per-source WorkManager scheduling and Room-backed refresh state. It does not add source-management UI, EPG scheduling or foreground notifications.

## Implemented

- `catalog:sync` module;
- Hilt-injected `CoroutineWorker`;
- manual/startup unique work with `ExistingWorkPolicy.KEEP`;
- periodic unique work with `ExistingPeriodicWorkPolicy.UPDATE`;
- 15-minute minimum interval;
- connected/unmetered and charging constraints;
- bounded exponential retry policy;
- WorkManager Data limited to source ID and trigger;
- encrypted access resolved only inside the worker;
- typed result mapping for success, authentication, HTTP, URL, redirect, size, transport and importer failures;
- Room v3 policy, state and attempt tables;
- conditional UUID lease with stale-run reclamation;
- late old-token completion rejection;
- 25-attempt per-source retention;
- HiltWorkerFactory through application `Configuration.Provider`;
- default WorkManager initializer removal;
- persisted schedule reconciliation after database initialization;
- unit outcome-policy tests and Android Room lease contract.

## Security review

- no URL, authorization value, cookie, referrer or User-Agent is placed in WorkManager Data;
- no raw exception text is persisted;
- status stores typed family/code and safe HTTP status only;
- credential records remain in Android Keystore-backed storage;
- a WorkRequest contains only `sourceId` and MANUAL/PERIODIC/STARTUP;
- cancellation writes a typed CANCELLED state and rethrows `CancellationException`;
- failed refreshes cannot activate a revision because activation remains owned by the existing importer transaction.

## Required gates

1. Fresh self-hosted Full on exact PR head.
2. DeviceMatrix because Room schema and application/WorkManager initialization change.
3. Final diff and evidence review.

## Deferred

- source policy UI;
- exported v2 migration fixture cleanup;
- foreground notification policy for very large catalogs;
- EPG scheduling;
- active playback catalog queries.
