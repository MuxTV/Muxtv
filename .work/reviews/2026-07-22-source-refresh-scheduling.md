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

## Verified Full gate

Self-hosted run `29946568554` passed on head `64d2629b3a72b6688b4e5c1b4a35a370770ec8df`.

It covered:

- Kotlin and Android unit tests, including `catalog:sync` outcome mapping;
- Room/KSP and Hilt worker generation;
- app compilation with custom WorkManager initialization;
- Android lint for the sync module and application;
- debug and release APK assembly.

## Verified DeviceMatrix gate

Self-hosted run `29947548905` passed on head `bac0d10b43c14f533636890ec79bceb2a337daee`. Artifact `self-hosted-validation-29947548905-1` is bound to the same branch/head.

The matrix resolved both requested profiles without fallback:

1. `system-images;android-26;android-tv;x86`, AVD `MuxTV_TV_OLD_API26`, 1536 MB RAM;
2. `system-images;android-36;android-tv;x86_64`, AVD `MuxTV_TV_CURRENT_API36`, 2048 MB RAM.

Both profiles passed sequentially:

- build-logic and configuration-cache create/reuse;
- pure Kotlin and Android unit tests;
- `catalog:sync` tests and app/Hilt compilation;
- debug APK, Android lint and release APK;
- real Android Keystore instrumentation;
- Room/database instrumentation, including lease overlap, stale reclamation, late-token rejection and policy privacy;
- application instrumentation with the custom WorkManager configuration;
- emulator shutdown and evidence collection.

The DeviceMatrix manifest completed with `status=passed`, no fallback, no failure and exact commit identity `bac0d10b43c1`.

## Remaining merge gates

1. commit the generated Room schema v3 JSON;
2. restore the permanent workflow without the PR-only DeviceMatrix override;
3. pass a final self-hosted Full run on the exact final head;
4. perform final diff and evidence review.

## Deferred

- source policy UI;
- exported v2 fixture migration test cleanup;
- foreground notification policy for very large catalogs;
- EPG scheduling;
- active playback catalog queries.
