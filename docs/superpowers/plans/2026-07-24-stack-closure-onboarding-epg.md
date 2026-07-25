# Stack Closure, Secure Onboarding, and EPG Plan

> Execute in order. Do not start a new irreversible layer while an earlier layer has an unresolved compile or data-integrity defect.

## Objective

Close the accumulated PR stack into `main`, deliver secure remote-M3U onboarding, then add XMLTV revisions and the first usable guide/now-next experience without duplicating the existing credentials, network, importer, Room revision, or Media3 infrastructure.

## Non-negotiable invariants

- Raw playlist URLs, query tokens, Authorization/Cookie values, User-Agent values, and Referrer values never enter Room, WorkManager Data, logs, exceptions, analytics, navigation routes, or `toString()` output.
- The UI receives only opaque IDs, sanitized scheme/host information, typed result codes, counts, and user-facing status text.
- A failed refresh, onboarding activation, or EPG import never replaces the previous-good active revision.
- Cancellation always propagates as `CancellationException` after bounded cleanup.
- Every PR is one functional commit over its immediate base before merge.
- Full validation is required on every exact head. DeviceMatrix is consolidated at integration boundaries where device behavior actually changes: Media3 service/player UI, TV focus/navigation, and final source-management/onboarding integration.

---

## Phase A — Close existing work

### A1. Scheduling and playback catalog

- [x] Merge PR #13 durable scheduling.
- [x] Rebuild PR #14 as one commit over `main`.
- [x] Run exact-head Full for PR #14.
- [x] Merge PR #14 active playback catalog.

### A2. Media3 service

- [x] Rebuild PR #15 as one Media3 commit over merged catalog.
- [ ] Resolve exact-head Full failures.
- [ ] Run API 26/API 36 DeviceMatrix covering service creation, controller connection, Bundle request transport, and process-owned player lifecycle.
- [ ] Remove stale stacked-language from the PR body.
- [ ] Merge PR #15.

### A3. Channels, player, and source management

For PR #16, #17, and #18, repeat:

1. reparent the one-commit tree onto the newly merged predecessor;
2. retarget to `main`;
3. run exact-head Full;
4. fix only concrete failures;
5. run one consolidated DeviceMatrix on PR #18 covering Channels → Player → Back and Sources controls;
6. merge sequentially.

---

## Phase B — PR #19 secure remote-M3U onboarding

### B1. Domain contract

Create `RemoteSourceOnboarding` in `catalog:refresh` with:

```kotlin
suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult
suspend fun activate(token: RemoteSourcePreparationToken, sourceName: String): RemoteSourceActivationResult
suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult
```

The input contains the locator, explicit HTTP approval, optional User-Agent/Referrer, and an allow-listed sensitive-header map. Its `toString()` must redact all values.

### B2. Preparation

- Validate source name-independent access through `RemoteSourceAccess` and `SourceUrlPolicy`.
- Reject embedded URL credentials, fragments, unsupported schemes, encoded control separators, and HTTP without explicit approval.
- Store the encoded `RemoteSourceAccess` in `CredentialStore` under a random canonical UUID.
- Return a token whose `toString()` is redacted, plus only normalized scheme and host.
- Never return the full normalized URL.

### B3. Activation

- Derive a stable source ID from the preparation token so repeated activation cannot create duplicate sources.
- Consume the existing `RemoteSourceRefresher`; do not implement another HTTP or M3U path.
- On success, retain the credential as the source credential reference and return counts/revision.
- On any non-success result, remove the temporary credential and return a typed, secret-free failure.
- On cancellation, remove the credential in `NonCancellable`, then rethrow cancellation.

### B4. Tests

- Preparation stores one encrypted access record and returns only scheme/host.
- HTTP requires explicit approval.
- Embedded credentials and fragments are rejected before storage.
- Activation calls the existing refresher with deterministic source/credential IDs.
- Failed activation removes the temporary credential.
- Successful activation retains it.
- Cancellation cleanup executes and cancellation propagates.
- All model `toString()` methods exclude locator and header values.

### B5. TV wizard

Implement after the domain contract is green:

1. source name;
2. locator and optional HTTP approval;
3. optional headers/authentication;
4. preparation result with sanitized host;
5. activate/cancel;
6. open Channels on success.

Use TV focus order, masked sensitive fields, bounded text lengths, and no secret persistence in `rememberSaveable`.

---

## Phase C — PR #20 XMLTV revisions

### C1. Storage model

- EPG source metadata with opaque credential reference only.
- Staging and active EPG revisions.
- XMLTV channel identity and canonical-channel mapping.
- Programme rows with normalized UTC start/stop, title, description, category, and optional episode metadata.
- Indices for channel/time-window queries.

### C2. Import pipeline

```text
CredentialStore
→ secure source client
→ compressed-size limit
→ bounded decoded stream
→ XmlPullParser
→ batched staging writes
→ validation
→ atomic activation
```

No DOM and no full-document byte array.

### C3. Scheduling

Reuse the durable scheduling pattern but use independent EPG work names, typed states, leases, and attempt history. M3U and XMLTV jobs for the same source may not overwrite each other's state.

---

## Phase D — PR #21 Guide and Now/Next

- Add bounded Room queries for now/next and guide windows.
- Show now/next in Channels without rebuilding the whole catalog list.
- Replace the Guide placeholder with a time-window grid optimized for D-pad navigation.
- Add search and favorites against canonical channels and overlays.
- Preserve focus and scroll across player navigation and EPG refresh.

---

## Phase E — PR #22 Playback recovery and TV Doctor Lite

- Persist preferred variant per profile/channel.
- Add previous/next zapping with cancellation of obsolete requests.
- Classify Media3 failures into bounded typed families.
- Retry the current variant only within policy, then try another active variant.
- Record secret-free observations: startup latency, buffering count, terminal family, last-success timestamp.
- Expose a TV Doctor screen with manual retry and variant selection; no irreversible automatic bans.

---

## Phase F — integration and alpha gate

- API 26 and API 36 DeviceMatrix.
- Real Android TV and Fire TV corpus for HLS, MPEG-TS, redirects, custom headers, decoder failures, and service recreation.
- Empty/small/10k/50k channel catalogs.
- Process death during playback and refresh.
- Configuration-cache reuse, lint, debug/release assembly, R8, Baseline Profile, and startup/zapping benchmarks.
