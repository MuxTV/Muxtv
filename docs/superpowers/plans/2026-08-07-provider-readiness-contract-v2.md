# Provider Readiness Contract v2 Execution Plan

**Goal:** restack issue #112's provider-neutral readiness API on accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`, preserve the existing test-first contract, then close the adversarial invariant gaps only after an executable RED is observed.

## Scope

This package is deliberately pure `catalog:api` Kotlin:

- no Room entities, schema, migrations, DAO, or persistence;
- no WorkManager or scheduler;
- no Xtream/Stalker/Jellyfin implementation;
- no credentials, URLs, headers, tokens, or free-form provider payload in the readiness API;
- no second state machine for stale-run rejection.

`USABLE` means an accepted active live catalog exists. EPG/secondary enrichment is independent and may be pending or failed without downgrading the live provider.

## Current restack state

- [x] Confirm old branch `work/provider-readiness-contract-112@2bfc399b9e0e2d082d3a660ba97da065848053cb` diverges from current main by only four unique paths.
- [x] Create `work/provider-readiness-contract-112-v2` from exact accepted main.
- [x] Port `ProviderReadiness.kt`.
- [x] Port `ProviderReadinessContractTest.kt`.
- [x] Port `ProviderReadinessInvariantTest.kt`.
- [x] Keep Room/WorkManager/provider implementations out of the restack.

## Existing authored public contract

The current model defines:

- `ProviderUsability.NOT_USABLE / USABLE`;
- active live catalog revision/count/activation time;
- trustworthy progress as completed pages + discovered items only;
- typed auth/rate-limit/timeout/network/content/storage/internal failures;
- catalog attempts with Idle/Running/Succeeded/Failed/Cancelled/Superseded;
- secondary state with previous-good active revision separate from latest attempt;
- snapshot usability derived only from active live catalog;
- source identity redacted from snapshot diagnostics.

These behaviors are authored but not execution-verified on the v2 head.

## Adversarial invariant RED

The restacked production file intentionally still lacks the follow-up fix. `ProviderReadinessInvariantTest` requires:

1. secondary `Cancelled` and `Superseded` terminal states;
2. secondary `Succeeded(revision=N)` only when `activeRevisionNumber == N`;
3. catalog `Succeeded(revision=N)` only when `activeCatalog?.revisionNumber == N`;
4. failed/cancelled/superseded attempts may coexist with previous-good active data.

### First runner-return command

```powershell
./gradlew.bat :catalog:api:test --tests app.muxtv.catalog.ProviderReadinessInvariantTest --no-daemon
```

Expected first RED on the current authored state: compilation failure because `ProviderSecondaryAttempt.Cancelled` and `ProviderSecondaryAttempt.Superseded` do not exist.

Do not add the production variants/invariants before this RED is captured.

## Minimal GREEN after observed RED

Only after the expected RED:

- add `ProviderSecondaryAttempt.Cancelled`;
- add `ProviderSecondaryAttempt.Superseded`;
- extend diagnostic mapping with `CANCELLED` / `SUPERSEDED`;
- in `ProviderSecondaryState.init`, if latest attempt is `Succeeded`, require its revision equals `activeRevisionNumber`;
- in `ProviderReadinessSnapshot.init`, if latest catalog attempt is `Succeeded`, require its revision equals `activeCatalog?.revisionNumber`.

Do not introduce run tokens, mutable state, persistence, scheduler ownership, or adapter-specific state.

## Validation after GREEN

```powershell
./gradlew.bat :catalog:api:test --tests app.muxtv.catalog.ProviderReadinessInvariantTest --no-daemon
./gradlew.bat :catalog:api:test --no-daemon
./gradlew.bat :catalog:api:test :core:common:test :core:model:test --no-daemon
```

Then run the repository Full host gate on the same exact head.

## Issue ownership

Even after the pure API contract is green, #112 should remain open as an integration umbrella until a real provider-neutral adapter/integration layer consumes the contract and demonstrates that live catalog activation is not coupled to EPG completion.

## Runner-off rule

While the self-hosted runner is unavailable:

- mechanical restack and static review are allowed;
- test contracts may be authored;
- do not claim RED/GREEN, compile-ready, or merge-ready;
- do not preempt the expected invariant RED with speculative production changes.
