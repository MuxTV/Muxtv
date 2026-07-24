# Stack Closure, Secure Onboarding, and EPG Plan

> **Status:** The post-onboarding numbering is superseded by `2026-07-24-performance-reliability-hardening.md`. PR #20 is the durable preparation registry, PR #21 implements catalog staging/importer hardening, and XMLTV moves to PR #26.

> Execute in order. Do not start a new irreversible layer while an earlier layer has an unresolved compile or data-integrity defect.

## Objective

Close the accumulated PR stack into `main`, deliver secure and durable remote-M3U onboarding, harden the source-to-playback path, then add XMLTV revisions and the first usable Guide/now-next experience without duplicating credentials, networking, importer, Room revision, or Media3 infrastructure.

## Non-negotiable invariants

- Raw playlist URLs, query tokens, Authorization/Cookie values, User-Agent values, and Referrer values never enter Room, WorkManager Data, logs, exceptions, analytics, navigation routes, or `toString()` output.
- The UI receives only opaque IDs, sanitized scheme/host information, typed result codes, counts, and user-facing status text.
- A failed refresh, onboarding activation, registry write, catalog batch, or EPG import never replaces the previous-good active revision or creates an unsafe dangling credential reference.
- Cancellation propagates as `CancellationException` after bounded cleanup.
- Every final functional PR is one functional commit over its immediate merged base before merge.
- Full validation is required on every exact head. DeviceMatrix is consolidated where device behavior changes: Media3 service/player UI, TV focus/navigation, Room migrations, and final onboarding integration.

---

## Phase A — Close existing work

### A1. Scheduling and playback catalog

- [x] Merge PR #13 durable scheduling.
- [x] Rebuild and merge PR #14 active playback catalog.

### A2. Media3 service

- [x] Rebuild PR #15 as one Media3 commit over merged catalog.
- [ ] Resolve exact-head Full failures.
- [ ] Run API 26/API 36 DeviceMatrix for service creation, controller connection, Bundle request transport, and process-owned player lifecycle.
- [ ] Merge PR #15.

### A3. Channels, player, sources, onboarding, and registry

For PR #16, #17, #18, #19, and #20:

1. rebuild the functional diff onto the newly merged predecessor;
2. retarget to `main`;
3. squash to one functional commit;
4. run exact-head Full;
5. fix only failures owned by that PR;
6. run consolidated DeviceMatrix on the final integration head;
7. merge sequentially.

Do not use a descendant PR to hide or validate repairs that belong to an earlier PR.

---

## Phase B — PR #19 secure remote-M3U onboarding

### Domain and security

```kotlin
suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult
suspend fun activate(token: RemoteSourcePreparationToken, sourceName: String): RemoteSourceActivationResult
suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult
```

- Validate access through `RemoteSourceAccess` and `SourceUrlPolicy`.
- Reject embedded credentials, fragments, unsupported schemes, encoded control separators, and HTTP without approval.
- Store encoded access in `CredentialStore` under a random UUID; return only a redacted token and sanitized scheme/host.
- Derive a domain-separated opaque source ID without embedding the credential UUID.
- Reuse `RemoteSourceRefresher`; do not duplicate HTTP/M3U paths.
- Remove inactive source metadata only when `activeRevision = 0` and the expected credential reference still matches.
- Remove a credential only after metadata is removed or absent; retain it when metadata is active or changed.
- Perform cancellation cleanup in `NonCancellable`, then rethrow cancellation.

### Tests

- secret-free preparation, activation, cancellation, and `toString()` contracts;
- safe failure cleanup;
- active/concurrently changed metadata prevents credential deletion;
- success retains the source credential.

---

## Phase C — PR #20 durable preparation registry

- Decorate PR #19 through `catalog:onboarding`; keep `catalog:refresh` independent from Room.
- Persist only opaque preparation ID, sanitized scheme/host, creation time, and expiry time.
- Use a 24-hour TTL and bounded startup cleanup.
- Roll back the prepared credential through domain cancel if registry persistence fails.
- Remove rows after activation or complete cleanup; retain rows when cleanup is incomplete.
- Commit Room schema `4.json` and prove `MIGRATION_3_4` from representative v3 data.
- Rebuild as one functional commit after #19 merges, then run exact-head Full and DeviceMatrix.

The TV wizard begins only after the domain and registry are green. Prepared URLs and tokens must not enter `rememberSaveable` or navigation strings.

---

## Phase D — PR #21 catalog staging and importer hardening

Implemented in the current stacked PR:

- one Room transaction for canonical, provider, and stream-variant batch writes;
- Android rollback contract for a stream-variant primary-key failure;
- one-pass entity materialization;
- one SHA-256 digest instance per import;
- direct lowercase hex conversion;
- one `providerKey` computation per entry;
- buffer swapping instead of `batch.toList()`;
- exact stable-ID golden tests and 250/1 batching test.

Still required before ready-for-review:

- importer unit tests;
- database Android-test compilation;
- exact-head Full;
- API 26/API 36 atomicity test execution;
- rebuild/squash onto `main` after #20 merges.

---

## Phase E — PR #22–#25 remaining hardening

1. PR #22: stack-aware validation and ancestry gate;
2. PR #23: cancellation-safe source mutations and real D-pad focus restoration;
3. PR #24: shared OkHttp Media3 transport, immutable per-item headers, and controller reconnect;
4. PR #25: deterministic 1k/10k/50k/100k benchmark evidence and only justified structural catalog optimization.

Projection, FTS5, pagination, ID migration, asynchronous pruning, R8 gating, Rust, and a second player engine remain evidence-triggered—not default scope.

---

## Phase F — PR #26 XMLTV revisions

### Storage

- EPG source metadata with opaque credential reference only.
- Staging and active EPG revisions.
- XMLTV channel identity and canonical-channel mapping.
- Programme rows with normalized UTC start/stop, title, description, category, and optional episode metadata.
- Indices for channel/time-window queries.

### Pipeline

```text
CredentialStore
→ secure source client
→ compressed-size limit
→ bounded decoded stream
→ XmlPullParser
→ transactional batched staging
→ validation
→ atomic activation
```

No DOM and no full-document byte array. M3U and XMLTV scheduling use independent work names, states, leases, and attempt histories.

---

## Phase G — PR #27 Guide and Now/Next

- Add bounded now/next and guide-window queries.
- Show now/next without rebuilding the whole channel list.
- Implement a D-pad-optimized time-window grid.
- Preserve focus/scroll across player navigation and EPG refresh.

---

## Phase H — PR #28 Playback Recovery and TV Doctor Lite

- Persist preferred variant per profile/channel.
- Add previous/next zapping with obsolete-request cancellation.
- Classify Media3 failures into typed families.
- Apply bounded retry/fallback policy.
- Record secret-free startup/buffering/terminal observations.
- Expose manual retry and variant selection; no irreversible automatic bans.

---

## Phase I — PR #29 integration and alpha gate

- API 26/API 36 DeviceMatrix.
- Real Android TV and Fire TV HLS/MPEG-TS/redirect/header/decoder/service-recreation corpus.
- Empty/small/10k/50k/100k catalogs.
- Process death during playback, onboarding, registry cleanup, and refresh.
- Configuration-cache reuse, lint, debug/release assembly, R8, Baseline Profile, and startup/zapping benchmarks.
