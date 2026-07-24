# TV Remote Source Onboarding Wizard Plan

## Goal

Let an Android TV user add a remote M3U source through the secure onboarding and durable preparation-registry layers without persisting secrets in Compose state restoration, navigation, logs, or Room.

## UX flow

1. **Input** — source name and locator; locator is masked by default and never stored with `rememberSaveable`.
2. **Transport approval** — explicit confirmation only when the domain reports that plain HTTP requires approval.
3. **Prepared endpoint** — show only normalized `scheme://host`; clear the raw locator from memory before rendering confirmation.
4. **Activation** — invoke the durable coordinator and show a bounded progress state.
5. **Result** — on success open Channels; on a typed failure show a generic actionable message without technical details or secret values.
6. **Cancel/Back** — if an opaque preparation token exists, invoke domain cancel before leaving. Process death is covered by the durable TTL registry.

## Security requirements

- Do not place locator, token, User-Agent, Referrer, Authorization, Cookie, or other header values in `AppDestination`, `SavedStateHandle`, `rememberSaveable`, semantic descriptions, analytics, or exceptions.
- The opaque token exists only in ordinary in-memory Compose state and the encrypted/Room domain layers.
- Mask locator input by default. A reveal toggle is explicit and resets to hidden after preparation.
- Enforce bounded input lengths before domain invocation: name 200 characters, locator 8192 characters.
- Clear raw locator after preparation succeeds or when the user cancels.
- Back during preparation/activation is disabled until the operation reaches a cleanup-safe state; cancellation of the coroutine must still propagate to the domain cleanup boundary.

## Module boundary

Create `feature:sourceonboarding` depending on:

- `catalog:refresh` for public result models;
- `core:designsystem` for TV actions;
- Compose foundation/activity for secure text entry and Back handling.

The feature receives `RemoteSourceOnboarding`; it does not know about Room, `CredentialStore`, OkHttp, WorkManager, or importer implementations.

## Navigation

- Add non-top-level `AppDestination.AddSource` with no arguments.
- Sources screen gets `onAddSource` and displays an `Добавить источник` action.
- `MainActivity` injects `RemoteSourceOnboarding` and passes it into `AppNavigation`.
- Successful activation opens `Channels`; Back returns to `Sources` after safe cancellation.

## Verification

- Reducer/unit tests for input validation and typed message mapping.
- Compose test: locator text is masked by default.
- Compose test: prepared state displays only scheme/host and no raw locator.
- Compose test: Back invokes cancel when a token exists.
- Hilt and Navigation3 compilation.
- API 26/API 36 DeviceMatrix on the final Sources → Add source → Channels integration head.
