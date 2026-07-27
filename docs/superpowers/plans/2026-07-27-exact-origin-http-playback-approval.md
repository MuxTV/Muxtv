# Exact-Origin HTTP Playback Approval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist exact-origin HTTP playback trust in encrypted source access, expose a safe Player confirmation flow for new origins and provide deterministic revocation without a Room approval table or global cleartext permission.

**Architecture:** Add a canonical HTTP-origin value in `core:network`; version `RemoteSourceAccess`; resolve/mutate approval through a catalog-facing encrypted resolver; return typed resolution to Player; keep credential references internal to DAO/catalog; expose one bounded source-level reset action.

**Tech Stack:** Kotlin 2.4.10, OkHttp 5.3, Android Keystore-backed `CredentialStore`, Room 3 schema v4, Compose for TV, Media3 1.10.1, coroutines 1.11.0, JUnit 4, Truth, API 26/API 36 harness.

## Global Constraints

- Base `main`: `d542e83ab7c1e997e4be95c82bffa63db90e8e83`.
- Preserve `minSdk = 26`, Room schema v4 and one process-owned ExoPlayer/MediaSession.
- Never add process-wide cleartext permission.
- Approval identity is exact HTTP scheme + normalized host + effective port.
- No locator/query/cookie/header/credential value in Navigation, saveable state, Room projections, diagnostics, semantics or screenshots.
- Parent cancellation propagates.
- No fallback, EPG, visual redesign, Rust/libmpv or second engine.

---

## Task 1 — Canonical `ExactHttpOrigin`

**Create:**
- `core/network/src/main/kotlin/app/muxtv/network/ExactHttpOrigin.kt`
- `core/network/src/test/kotlin/app/muxtv/network/ExactHttpOriginTest.kt`

**Contract:**

```kotlin
@JvmInline
value class ExactHttpOrigin private constructor(private val canonical: String) {
    fun encoded(): String
    fun displayValue(): String
    override fun toString(): String
    companion object {
        fun fromUrl(url: String): ExactHttpOrigin?
        fun parse(encoded: String): ExactHttpOrigin?
    }
}
```

- [x] Write RED tests for port normalization/distinction, host canonicalization, HTTPS/credential rejection, path/query removal, IPv6, canonical parse and redaction.
- [x] Capture RED Full run `30282871326` on test-only head.
- [ ] Implement minimal type with OkHttp `HttpUrl`.
- [ ] Run `:core:network:testDebugUnitTest` and commit.

---

## Task 2 — Encrypted `RemoteSourceAccess` v2

**Modify:** `RemoteSourceAccess.kt` and codec tests.

- [ ] Add immutable bounded `approvedPlaybackOrigins: Set<ExactHttpOrigin>` (`max 16`).
- [ ] Add approve/revoke/revokeAll/withSourceUrl helpers.
- [ ] Seed exact source origin when HTTP onboarding is explicitly approved.
- [ ] Encode v2 with origin count/strings; continue decoding v1.
- [ ] v1 approved HTTP derives only source origin; HTTPS/denied records derive none.
- [ ] Reject malformed/duplicate/oversized origins and redact diagnostics.
- [ ] Run all `catalog:refresh` tests and commit.

---

## Task 3 — Safe encrypted read/update boundary

**Modify:** `RemoteSourceAccessManager` and `RemoteSourceRefresher`.

- [ ] Add typed Found/NotFound/Corrupted/Unavailable read results.
- [ ] Add typed Updated/Unchanged/NotFound/Corrupted/Unavailable/TooLarge update results.
- [ ] Serialize mutations with a singleton `Mutex`.
- [ ] Refactor refresher to use the shared read boundary.
- [ ] Test preservation of URL/headers, concurrency and cancellation.
- [ ] Commit.

---

## Task 4 — Catalog-facing resolver

**Create:**
- `catalog/api/.../PlaybackAccessPolicy.kt`
- `catalog/refresh/.../EncryptedPlaybackAccessPolicyResolver.kt`
- focused resolver tests.

- [ ] Define `PlaybackAccessDecision`, `PlaybackAccessMutationResult` and `PlaybackAccessPolicyResolver` without CredentialStore types.
- [ ] HTTPS resolves secure without reading credentials.
- [ ] Exact approved HTTP resolves approved.
- [ ] Different host/port requires approval.
- [ ] Missing/corrupt/unavailable/invalid inputs map safely.
- [ ] Approve/revoke/revokeAll mutate only origin set; cancellation propagates.
- [ ] Commit.

---

## Task 5 — Typed catalog resolution

**Modify:** catalog API, DAO, Room catalog, database factory and instrumentation tests.

- [ ] Add internal nullable `credentialRef` to `ActiveVariantRow` query only.
- [ ] Keep `PlayableVariant` free of credential references.
- [ ] Change `resolveVariant` to return `Ready`, `InsecureTransportApprovalRequired` or `AccessUnavailable`.
- [ ] Add `insecureHttpApproved` to `ResolvedPlaybackRequest`.
- [ ] Add approve/revoke catalog methods that re-query the current active variant before mutation.
- [ ] Inject resolver into `RoomPlaybackCatalog`/factory; production AppModule supplies encrypted implementation.
- [ ] Test HTTPS, approved/unapproved HTTP, preferred source ownership, stale variant and secret-safe diagnostics.
- [ ] Commit.

---

## Task 6 — Player confirmation state

**Create/modify:** bounded `PlayerSetupSession`, tests and `PlayerRoute`.

- [ ] Secure/approved resolution sends SET directly.
- [ ] Unapproved HTTP sends no SET and renders only exact origin.
- [ ] Primary action approves, then re-resolves current variant before setup.
- [ ] Cancellation during resolve/approve/setup propagates and cannot late-install.
- [ ] Connection epoch still restarts one bounded attempt.
- [ ] Add explicit focus for warning/approving/failure states.
- [ ] Propagate ready `insecureHttpApproved` into `PlaybackSessionRequest`.
- [ ] Commit.

---

## Task 7 — Source-level revocation

**Create/modify:** `SourcePlaybackApprovalActions`, Sources route and app wiring.

- [ ] App use case resolves source target/credential internally and calls `revokeAll`.
- [ ] Add `Сбросить HTTP-разрешения` without rendering origins/credential IDs.
- [ ] Disable during mutation; map typed safe results; propagate cancellation.
- [ ] Credential removal/reset tests prove no independent approval survives.
- [ ] Commit.

---

## Task 8 — Android TV acceptance and closure

API 26 and API 36:

- [ ] approved HTTP source → same-origin channel setup;
- [ ] different host and different port warnings;
- [ ] approval → re-resolution → Media3 SET;
- [ ] revocation/reset → warning returns;
- [ ] credential removal leaves no trust;
- [ ] HTTPS flow remains direct and downgrade remains rejected;
- [ ] production manifest has no global cleartext opt-in;
- [ ] D-pad/Back and secret scans pass.

Verification:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch feat/exact-origin-http-playback-approval `
  -SourceCommit <exact-head> `
  -NoDaemon
```

- [ ] Record exact evidence and test counts.
- [ ] Remove temporary PR workflow if used.
- [ ] Run final cleaned-head Full.
- [ ] Review threads/code/docs/secrets.
- [ ] Mark ready, squash merge and close #39.

## Self-Review

- All issue #39 acceptance criteria map to Tasks 1–8.
- No Room approval table or schema migration is planned.
- Credential reference stays internal.
- Exact host+port, v1 compatibility, revocation and cancellation are explicit.
- No unrelated product/engine scope is included.
