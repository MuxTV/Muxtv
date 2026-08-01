# EPG Orchestration Review Addendum

> Status: active review addendum after PR #74 merged as `ab96f0fee5b80ebc8ae7f5a2cc23608ee5450030`.

This addendum refines `2026-08-01-post-remote-epg-execution.md` from the implementation review of #70/P1B. It does not replace the wider product sequence; it narrows the correctness gates that must be satisfied before deterministic matching starts.

## Verified P1A baseline

PR #74 is merged. Exact-head evidence before merge:

- Full validation: `30703994191` — success;
- API26/API36 database migration/device matrix: `30703994190` — success;
- Room v5 and v6 schema exports are committed and migration state is repository-reproducible.

P1A is therefore no longer an active implementation package. P1B must build directly on the squash-merged `main` tree rather than carrying the historical stacked commits.

## P1B review gates — issue #70

### Gate B1 — source-access publication ownership

Remote XMLTV import must not write mutable source metadata from an in-flight request. Staged EPG data may activate only when the current opaque `accessRef` still equals the binding captured by the worker.

### Gate B2 — refresh-lease publication ownership

`accessRef` equality alone is insufficient. A worker whose DB lease was reclaimed, cancelled, or removed must not activate staged data even if the endpoint binding itself is unchanged.

Activation therefore requires one Room transaction to prove both:

1. current source `accessRef` equals the captured binding; and
2. `epg_refresh_states` still contains `RUNNING` with the worker's current opaque `runToken`.

Mismatch deletes the staging revision and returns `Superseded`. This is the data-publication analogue of old-token completion rejection.

Required contracts:

- reclaimed old token cannot activate;
- policy/state removal prevents the cancelled old worker from activating;
- access replacement still supersedes and discards staging.

### Gate B3 — completion ownership includes nullable bindings

The captured access binding is a value, not an optional guard. `expectedAccessRef = null` means the worker observed no access binding at start. If a new binding appears before that old run completes, its `MISSING_REFERENCE`/auth/failure completion must become `SUPERSEDED` rather than mutating the repaired source.

For every completion:

- compare current nullable binding to the captured nullable binding;
- for successful 200/304, also verify the embedded validator binding equals the captured non-null binding;
- mismatch cannot publish success, validators, auth state or failure state.

### Gate B4 — WorkManager identity and startup constraints

Issue #70 requires EPG work identity to be derived only from opaque source identity and trigger. Manual and startup one-shot work therefore need deterministic trigger-distinct unique names while keeping `ExistingWorkPolicy.KEEP`; periodic work remains unique periodic `UPDATE`.

This also prevents a policy-constrained pending STARTUP request from suppressing an explicit MANUAL refresh through a shared unique-work name.

Constraint semantics:

- MANUAL: explicit CONNECTED one-shot override;
- STARTUP: inherit the durable policy's UNMETERED and charging requirements;
- PERIODIC: inherit the same durable policy requirements;
- DB lease remains the final same-source overlap authority if trigger-distinct work becomes runnable concurrently.

### Gate B5 — cancellation remains authoritative

Cancellation finalization is best-effort persistence in `NonCancellable`, but a Room/storage exception during finalization must not replace the original `CancellationException`.

Required contract:

- even when cancellation-state persistence throws, the original coroutine cancellation object is rethrown;
- previous-good EPG data remains untouched.

### Gate B6 — final verification

Before #70 closes:

1. focused RED/GREEN contracts for B1-B5;
2. exact-head hosted/unit compile smoke during iteration;
3. remove all temporary hosted workflow files;
4. exact-head repository Full;
5. exact-head API26/API36 migration/device matrix;
6. review final diff for URL/credential/validator/programme/run-token value leakage;
7. merge #75 and update repository truth.

## P1C — issue #76 source-refresh ownership hardening

Implementation review found the same stale-publication class in the existing M3U path: `RemoteSourceRefresher` captures a credential reference, and `CatalogRevisionImporter` later upserts that old reference and activates without comparing the current source binding/lease.

Issue #76 is therefore inserted before #71 consumes catalog + EPG revision identities.

Required behavior mirrors the EPG invariants without creating a second framework:

- in-flight remote M3U imports do not rewrite current source metadata;
- activation compares the captured credential binding atomically;
- durable activation also proves the current source-refresh `runToken`;
- stale success/auth/network outcomes cannot mutate a replacement source;
- cancellation persistence cannot mask coroutine cancellation;
- superseded staging is discarded while previous-good catalog remains active.

## Updated critical sequence

1. **#70/P1B** — finish B1-B6 and merge #75.
2. **#76/P1C** — harden the existing M3U refresh publication boundary using the same ownership model.
3. **#71/P2** — deterministic EPG matching and bounded now/next only after both revision producers have stable publication ownership.
4. **#28/P3** — end-to-end XMLTV → refresh → activation → matching → now/next closure evidence.
5. **#27** continues only in free runner windows; five-run campaigns must not delay correctness/device gates.
6. **#29** daily-use: Channels now/next → Favorites → Recent → Search → Guide.
7. **#33** TV-first UX.
8. **#30** bounded playback fallback + TV Doctor.
9. **#31** release/physical-device alpha gate.

## Review invariant

The shared invariant for source and EPG refresh is:

> An asynchronous result may publish data or durable operational state only while it still proves ownership of both the resource binding it started with and the current durable refresh lease.

WorkManager uniqueness is an orchestration optimization, not the final ownership boundary. Room transactions and immutable revision activation remain authoritative.
