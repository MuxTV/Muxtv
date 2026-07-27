# MuxTV Post-Media3 Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continue MuxTV from the merged hardened M3U → catalog → Channels → Media3 Player vertical slice through exact-origin HTTP playback approval, measured corpus, EPG/daily-use features, bounded recovery, visual modernization and release hardening.

**Architecture:** Each package starts from merged `main`, changes one ownership boundary and lands as an independently reviewable squash merge. Security/schema work precedes new product surfaces; corpus evidence precedes structural optimization, fallback engines or compatibility claims.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 9.3, Compose BOM 2026.06, Navigation 3, Room 3 schema v4, Android Keystore/DataStore, OkHttp 5.3, Media3 1.10.1, WorkManager, Windows self-hosted PowerShell Android TV harness.

## Global Constraints

- Repository: `MuxTV/Muxtv`; base every new package on merged `main`.
- Preserve `minSdk = 26` unless an explicit compatibility ADR changes it.
- Preserve one process-owned `ExoPlayer` and one `MediaSession`.
- Keep playlist locators, query values, cookies, Authorization/Referer values, sensitive headers and credential payloads out of Navigation, SavedState, Room projections, logs, traces, screenshots, semantics and exception text.
- Keep functional concerns isolated: documentation, security/schema, corpus, EPG, UX, recovery and release hardening use separate PRs.
- Do not add process-wide cleartext permission, a global “allow HTTP” switch or implicit host trust.
- Do not adopt Rust/UniFFI, libmpv, bundled SQLite, Paging or a second player engine without reproducible corpus evidence and a separate ADR.
- Emulator evidence proves Android API/lifecycle contracts, not vendor MediaCodec, HDR, passthrough, Fire OS or constrained ARM hardware.
- Every runtime task uses RED/GREEN tests, exact-head Full validation and appropriate API 26/API 36 device evidence.

---

## Completed Baseline — 2026-07-27

- [x] PR #34: secure source entry, deterministic D-pad focus and Player-return restoration.
- [x] PR #35: repository truth synchronized to the first functional vertical slice.
- [x] PR #36: request-scoped Media3 OkHttp transport, immutable headers and redirect policy.
- [x] PR #37: retryable MediaController ownership and disconnect invalidation.
- [x] PR #38: cancellable setup protocol, remote-session reconnect epoch and late-install prevention.
- [x] Issue #26 closed by squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304`.
- [x] API 26/API 36 DeviceMatrix run `30222900566` passed without fallback.
- [x] Cleaned exact-head Full run `30223482178` passed.

---

## Package 1 — Repository Truth Closure (#40)

**Purpose:** Remove stale execution records before beginning the next schema/security change.

**Files:**
- Modify: `README.md`
- Modify: `.work/CURRENT-STATE.md`
- Modify: `.work/meta/status.yaml`
- Create: `docs/superpowers/plans/2026-07-27-next-execution.md`
- Modify: `docs/superpowers/plans/2026-07-25-next-execution.md`
- Update: issue #33 execution status

- [x] **Step 1: Record merged Media3 packages**

Record PR #36, #37 and #38 squash commits, Full/matrix evidence and closed issue #26.

- [x] **Step 2: Replace issue #26 as the current blocker**

Set issue #39 exact-origin HTTP playback approval as the next runtime milestone.

- [x] **Step 3: Preserve deferred decisions**

Keep Rust, libmpv, a second player engine, full KMP database and unsupported platforms explicitly deferred.

- [ ] **Step 4: Mark the old execution plan superseded**

Replace the obsolete immediate-order section with a pointer to this plan while retaining the historical evidence.

- [ ] **Step 5: Update issue #33 status**

Mark PR #22/#32 prerequisites and D2 focus ownership as completed; leave visual row presentation, light shell, player overlay, Sources simplification and device QA open.

- [ ] **Step 6: Open documentation PR**

Expected PR scope: documentation and issue metadata only; no runtime, schema or dependency changes.

- [ ] **Step 7: Run Full and merge**

Run `tools/verify-local.ps1 -Mode Full`, inspect the exact head, squash merge and close #40.

---

## Package 2 — Exact-Origin HTTP Playback Approval (#39)

### Design decision

Use the existing encrypted `RemoteSourceAccess` record as the single source of truth. Do **not** add a Room approval table unless implementation proves the encrypted boundary cannot satisfy revocation and lookup requirements.

The encrypted record already owns source URL, HTTP approval and sensitive access data. Evolve it from the legacy source-level boolean to a bounded set of approved exact HTTP playback origins. Room stores only the opaque source credential reference already associated with the source.

### Data flow

```text
Source onboarding HTTP confirmation
    ↓
Encrypted RemoteSourceAccess v2
    ├─ source URL/access headers
    └─ approvedPlaybackOrigins: bounded exact HTTP origins
    ↓
PlaybackCatalog variant resolution
    ↓ reads source credential by opaque reference
PlaybackAccessPolicyResolver
    ↓ exact scheme + host + effective port match
ResolvedPlaybackRequest.insecureHttpApproved
    ↓
PlaybackSessionRequest
    ↓
PlaybackRequestPolicyInterceptor
```

### Files

**Create**

- `core/network/src/main/kotlin/app/muxtv/network/ExactHttpOrigin.kt` — canonical exact-origin value.
- `core/network/src/test/kotlin/app/muxtv/network/ExactHttpOriginTest.kt` — normalization and rejection contracts.
- `catalog/api/src/main/kotlin/app/muxtv/catalog/PlaybackAccessPolicyResolver.kt` — catalog-facing resolver interface and typed outcomes.
- `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/EncryptedPlaybackAccessPolicyResolver.kt` — CredentialStore-backed implementation.
- `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/EncryptedPlaybackAccessPolicyResolverTest.kt` — exact-origin, corruption, missing and cancellation contracts.
- `feature/player/src/main/kotlin/app/muxtv/feature/player/HttpPlaybackApprovalSession.kt` — bounded warning/approval state holder if UI state exceeds the route-local threshold.
- focused tests under `feature/player/src/test` and Android TV instrumentation.

**Modify**

- `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/RemoteSourceAccess.kt`
- `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/RemoteSourceRefresher.kt`
- `catalog/api/src/main/kotlin/app/muxtv/catalog/PlaybackCatalog.kt`
- `core/database/src/main/kotlin/app/muxtv/database/PlaybackCatalogDao.kt`
- `core/database/src/main/kotlin/app/muxtv/database/RoomPlaybackCatalog.kt`
- `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabaseFactory.kt`
- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSessionRequest.kt`
- `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt`
- `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- source deletion/reset path where the credential is removed
- tests and `tools/verify-local.ps1` only if a new suite needs permanent inclusion

### Task 2.1: Exact HTTP origin value

**Interfaces:**

```kotlin
@JvmInline
value class ExactHttpOrigin private constructor(private val canonical: String) {
    fun encoded(): String
    override fun toString(): String

    companion object {
        fun fromUrl(url: String): ExactHttpOrigin?
        fun parse(encoded: String): ExactHttpOrigin?
    }
}
```

Canonical form uses lowercase ASCII host and explicit effective port:

```text
http://example.test:80
http://example.test:8080
```

- [ ] Write RED tests proving default port normalization, non-default port distinction, case normalization, IPv6 canonical handling, HTTPS rejection, embedded-credential rejection, fragment rejection and redacted diagnostics.
- [ ] Run focused test and verify the production type is absent.
- [ ] Implement the minimum immutable value using the repository OkHttp URL parser/security policy.
- [ ] Run GREEN and commit `feat: add exact HTTP playback origin`.

### Task 2.2: Version encrypted source access

**Interfaces:**

```kotlin
class RemoteSourceAccess(
    val url: String,
    val insecureHttpApproved: Boolean = false,
    val approvedPlaybackOrigins: Set<ExactHttpOrigin> = emptySet(),
    ...
) {
    fun approvesPlayback(url: String): Boolean
    fun withApprovedPlaybackOrigin(origin: ExactHttpOrigin): RemoteSourceAccess
    fun withoutApprovedPlaybackOrigin(origin: ExactHttpOrigin): RemoteSourceAccess
}
```

- [ ] Add RED codec tests for v2 round-trip, maximum origin count, duplicate normalization, malformed origin, trailing data and redacted diagnostics.
- [ ] Add backward-compatibility fixture for v1 records.
- [ ] Define v1 migration semantics: an HTTP v1 source with `insecureHttpApproved=true` approves only the exact source URL origin; HTTPS v1 records approve no cleartext playback origin.
- [ ] Bump codec version to `2` while decoding both versions.
- [ ] Keep encoded size bounded and erase temporary byte arrays.
- [ ] Run all `catalog:refresh` tests and commit `feat: version encrypted playback approvals`.

### Task 2.3: Credential-backed resolver and mutation boundary

**Interfaces:**

```kotlin
sealed interface PlaybackAccessResolution {
    data object Approved : PlaybackAccessResolution
    data object ApprovalRequired : PlaybackAccessResolution
    data object SecureTransport : PlaybackAccessResolution
    data object CredentialNotFound : PlaybackAccessResolution
    data class CredentialUnavailable(val reason: CredentialUnavailableReason) : PlaybackAccessResolution
    data object CredentialCorrupted : PlaybackAccessResolution
}

interface PlaybackAccessPolicyResolver {
    suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessResolution

    suspend fun approve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult

    suspend fun revoke(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult
}
```

- [ ] RED: HTTPS resolves `SecureTransport` without writing.
- [ ] RED: exact approved HTTP host+port resolves `Approved`.
- [ ] RED: another host or port resolves `ApprovalRequired`.
- [ ] RED: missing/corrupt/unavailable credential maps to typed safe outcome.
- [ ] RED: parent cancellation propagates.
- [ ] RED: approve/revoke rewrites the encrypted record without changing URL/header data.
- [ ] Implement `RemoteSourceAccessManager.read/update` with secret lifetime and cancellation safety.
- [ ] Implement `EncryptedPlaybackAccessPolicyResolver` and commit `feat: resolve encrypted playback approval`.

### Task 2.4: Carry opaque source ownership through catalog resolution

**Interfaces:**

```kotlin
data class ActiveVariantRow(
    ...,
    val credentialRef: String,
)

data class ResolvedPlaybackRequest(
    ...,
    val insecureHttpApproved: Boolean,
    val approvalRequired: Boolean,
)
```

The credential reference is internal to the resolution path and must not appear in `PlayableVariant`, UI state, navigation, semantics or diagnostics.

- [ ] RED DAO/Room test proving the selected active variant includes the source credential reference internally.
- [ ] RED `RoomPlaybackCatalog` tests for HTTPS, approved HTTP, unrelated origin and missing credential outcomes.
- [ ] Add `sources.credentialRef` only to `ActiveVariantRow` query.
- [ ] Inject `PlaybackAccessPolicyResolver` into `RoomPlaybackCatalog` through `MuxTvDatabaseFactory`/AppModule.
- [ ] Extend `ResolvedPlaybackRequest` with safe booleans only.
- [ ] Ensure `toString()` remains locator/credential-safe.
- [ ] Run database unit/instrumentation suites and commit `feat: resolve playback approval with variants`.

### Task 2.5: Player warning and approval flow

Required UI behavior:

1. HTTPS or already-approved HTTP proceeds directly to setup.
2. Unapproved HTTP shows a dedicated warning before sending SET.
3. Warning displays only sanitized exact origin, never path/query.
4. Primary action: `Разрешить для этого адреса`.
5. Secondary action: `Назад к каналам`.
6. Approve mutation re-resolves the variant before setup.
7. Failure state exposes typed safe copy and retry/back only.

- [ ] RED route/state tests for direct secure setup, approval-required state, successful approval, cancelled mutation, failed mutation and stale-route cancellation.
- [ ] Implement the smallest state holder that serializes approve/re-resolve/setup; do not add repository-wide MVI.
- [ ] Add explicit FocusRequester for warning, mutation and failure states.
- [ ] Do not store locator, credentialRef or request in `rememberSaveable`/Navigation.
- [ ] Propagate `ResolvedPlaybackRequest.insecureHttpApproved` into `PlaybackSessionRequest`.
- [ ] Run feature/app compilation and commit `feat: add exact-origin HTTP playback warning`.

### Task 2.6: Revocation, deletion and source changes

- [ ] RED: revoking origin prevents the next setup without deleting the source.
- [ ] RED: deleting the source removes the encrypted record; later resolution returns typed unavailable/not-found without trust.
- [ ] RED: changing source URL to another origin does not carry approval to that origin.
- [ ] Ensure removal paths do not leave a separate approval store because no duplicate store exists.
- [ ] Add a bounded revoke action under source playback/security settings; do not place it in the daily Channels path.
- [ ] Commit `feat: revoke HTTP playback approval`.

### Task 2.7: Security and device acceptance

API 26 and API 36 journeys:

- [ ] onboard/activate an explicitly approved local HTTP source;
- [ ] open its same-origin HTTP channel and reach Player setup;
- [ ] show warning for a different host or port;
- [ ] approve that exact origin and retry successfully;
- [ ] revoke approval and prove warning returns;
- [ ] delete source and prove no approval survives;
- [ ] prove HTTPS → HTTP redirect remains rejected;
- [ ] inspect merged production manifest and assert no process-wide cleartext opt-in;
- [ ] scan reports/logcat/screenshots/semantics for locator, query, header and credential fixtures.

Run sequence:

```powershell
.\gradlew.bat :core:network:testDebugUnitTest :catalog:refresh:testDebugUnitTest :core:database:testDebugUnitTest :feature:player:testDebugUnitTest --no-daemon
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 -Mode DeviceMatrix -SourceBranch local -SourceCommit local -NoDaemon
```

- [ ] Record exact heads, run IDs, test counts and artifact review.
- [ ] Remove any temporary branch-specific workflow.
- [ ] Run final cleaned-head Full.
- [ ] Mark PR ready, squash merge and close #39.

---

## Package 3 — Deterministic IPTV Corpus and Measurements (#27)

**Purpose:** Produce reproducible evidence before adding structural optimizations, fallback policy, Rust/libmpv or compatibility claims.

### Corpus

- [ ] synthetic small/medium/large M3U fixtures;
- [ ] malformed attributes, controls, duplicate identities and mixed encodings;
- [ ] HLS master/media playlists, relative URLs, redirects and header-sensitive resources;
- [ ] starter XMLTV fixtures with timezone, malformed and scale cases;
- [ ] no provider credentials, private playlists or copyrighted guide dumps.

### Measurements

- [ ] streaming parse wall time and allocations;
- [ ] 250-entry batch staging;
- [ ] activation transaction;
- [ ] active channel/source-overview queries;
- [ ] Player request installation and first-frame proxy;
- [ ] startup/frame/memory baselines on normal and low-RAM virtual profiles;
- [ ] exact machine, JDK, Gradle, Android SDK, emulator and API metadata.

### Exit

- [ ] benchmark thresholds are descriptive first, not arbitrary failing budgets;
- [ ] regressions use stable fixtures and documented variance;
- [ ] no architecture dependency is adopted merely because it benchmarks well in isolation;
- [ ] close #27 with a reviewed evidence report.

---

## Package 4 — Streaming XMLTV and Immutable EPG (#28)

Order:

- [ ] bounded streaming XMLTV parser;
- [ ] EPG source/access contract separate from M3U access;
- [ ] immutable EPG revisions and previous-good retention;
- [ ] atomic activation/cleanup;
- [ ] channel matching with explicit confidence/reason;
- [ ] timezone/DST validation;
- [ ] API 26/API 36 migration and refresh journeys;
- [ ] close #28 before Guide implementation.

---

## Package 5 — Daily-Use Discovery (#29)

Order:

- [ ] now/next projections keyed by canonical channel identity;
- [ ] bounded Guide window and lazy program rows;
- [ ] bounded/debounced channel+programme search;
- [ ] Favorites mutation as profile overlay;
- [ ] Recent history with retention/privacy limits;
- [ ] D-pad, focus restoration, recreation and empty/error journeys;
- [ ] no URL/program payload in navigation keys or focus tags.

---

## Package 6 — Bounded Recovery and TV Doctor Lite (#30)

Only after #27:

- [ ] deterministic variant ordering;
- [ ] explicit maximum attempts and wall-clock budget;
- [ ] no endless retry or hidden source failure;
- [ ] typed DNS/TLS/HTTP/redirect/manifest/decoder/playback observations;
- [ ] secret-free TV Doctor summary;
- [ ] safe user-confirmed recovery actions;
- [ ] Media3 remains the only engine unless an ADR proves otherwise.

---

## Package 7 — Light TV-First Visual Modernization (#33)

Already complete and not to be reimplemented:

- [x] stable channel focus identity;
- [x] Player → Back focus restoration;
- [x] reorder/removal fallback;
- [x] deterministic Home/Sources/Add Source/Player safe focus;
- [x] remote-session reconnect ownership.

Remaining small PRs:

1. light semantic palette and navigation rail;
2. dedicated channel row presentation using existing focus ownership;
3. hidden-by-default Player overlay;
4. simplified Sources cards/details;
5. isolated credential-free logo loader only after rows stabilize;
6. 720p/1080p/4K/long-text/reduced-motion/low-RAM QA and release assets.

Do not invent Home rails or EPG progress before #28/#29 data exists.

---

## Package 8 — Release Hardening and Physical Alpha Gate (#31)

- [ ] enable R8/resource shrinking with tested keep rules;
- [ ] Baseline Profile for startup, Sources, Channels, Player and Guide;
- [ ] startup/frame/memory evidence on normal and low-RAM profiles;
- [ ] signed reproducible artifacts, SBOM, changelog and rollback instructions;
- [ ] Room migration and Keystore persistence/reset release checks;
- [ ] representative Android/Google TV physical device;
- [ ] constrained physical device;
- [ ] Fire TV/Appstore Quality Central evidence where available;
- [ ] bounded codec/HDR/passthrough claims only for tested devices;
- [ ] publish `0.1.0-alpha` only after issue #31 acceptance is met.

---

## Immediate Execution Order

1. Finish and merge documentation PR for #40.
2. Design and implement issue #39 without a Room approval table unless proven necessary.
3. Build #27 corpus and measurements.
4. Implement #28 immutable EPG.
5. Implement #29 daily-use discovery.
6. Implement #30 bounded recovery.
7. Execute remaining #33 visual PRs in data-dependency order.
8. Complete #31 release/physical-device gate.

## Plan Self-Review

- Spec coverage: completed Media3 state, #40, #39, #27–#31 and #33 are explicitly mapped.
- Scope: documentation and runtime/security changes are separate; no mixed mega-PR.
- Type consistency: issue #39 interfaces consistently use `ExactHttpOrigin`, `PlaybackAccessPolicyResolver`, `PlaybackAccessResolution` and `ResolvedPlaybackRequest` booleans.
- Security: approval remains encrypted and exact-origin; Room receives only the already-existing opaque credential reference internally.
- No placeholders or speculative engine adoption remain.
