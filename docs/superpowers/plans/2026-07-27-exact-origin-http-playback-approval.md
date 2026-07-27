# Exact-Origin HTTP Playback Approval Implementation Plan

> **Status:** implementation complete in PR #42; final reviewed-head Full/DeviceMatrix, merge and issue closure remain.

**Goal:** Persist exact-origin HTTP playback trust in encrypted source access, expose a safe Player confirmation flow for new origins and provide deterministic revocation without a Room approval table or global cleartext permission.

**Architecture:** A canonical HTTP-origin value lives in `core:network`; `RemoteSourceAccess` v2 stores bounded approved origins; one singleton `RemoteSourceAccessManager` owns all encrypted reads/writes; a catalog-facing resolver returns typed playback resolution; credential references remain internal to DAO/catalog; Sources exposes one bounded reset action.

**Tech Stack:** Kotlin 2.4.10, OkHttp 5.3, Android Keystore-backed `CredentialStore`, Room 3 schema v4, Compose for TV, Media3 1.10.1, coroutines 1.11.0, JUnit 4, Truth, API 26/API 36 harness.

## Global Constraints

- Base `main`: `d542e83ab7c1e997e4be95c82bffa63db90e8e83`.
- Preserve `minSdk = 26`, Room schema v4 and one process-owned ExoPlayer/MediaSession.
- Never add process-wide cleartext permission or a global allow-list.
- Approval identity is exact HTTP scheme + normalized host + effective port.
- No locator/query/cookie/header/credential value in Navigation, saveable state, public Room projections, diagnostics, semantics or screenshots.
- Parent cancellation propagates.
- No fallback, EPG, visual redesign, Rust/libmpv or second engine.

---

## Task 1 — Canonical `ExactHttpOrigin`

**Created:**
- `core/network/src/main/kotlin/app/muxtv/network/ExactHttpOrigin.kt`
- `core/network/src/test/kotlin/app/muxtv/network/ExactHttpOriginTest.kt`

- [x] Write RED tests for port normalization/distinction, host canonicalization, HTTPS/credential rejection, path/query removal, IPv6, canonical parse and redaction.
- [x] Capture RED Full run `30282871326` on the test-only head.
- [x] Implement the immutable type with OkHttp `HttpUrl`.
- [x] Keep encoded/display values path/query-free and redact `toString()`.

---

## Task 2 — Encrypted `RemoteSourceAccess` v2

- [x] Add immutable bounded `approvedPlaybackOrigins: Set<ExactHttpOrigin>` (`max 16`).
- [x] Add approve/revoke/revokeAll/withSourceUrl helpers.
- [x] Seed only the exact source origin when HTTP onboarding is explicitly approved.
- [x] Encode v2 with origin count/strings while decoding v1.
- [x] Migrate v1 approved HTTP to only its source origin; HTTPS/denied records derive none.
- [x] Reject malformed, duplicate and excessive origins.
- [x] Preserve URL/header data and redact diagnostics.

---

## Task 3 — Single safe encrypted read/update boundary

**Created:** `RemoteSourceAccessManager.kt`.

- [x] Add typed Found/NotFound/Corrupted/Unavailable read results.
- [x] Add typed Updated/Unchanged/NotFound/Corrupted/Unavailable/TooLarge update results.
- [x] Serialize save/read/update/remove through one singleton manager mutex.
- [x] Refactor `RemoteSourceRefresher` to use the shared read boundary.
- [x] Refactor the playback resolver to use the same manager rather than a second mutex/store owner.
- [x] Add a concurrent-update regression preserving both origins, URL and access headers.
- [x] Capture RED on commit `0be25d3b2a8fd61ad6f17d33ec18cb68c589b17a` before read/update contracts existed.

---

## Task 4 — Catalog-facing resolver

- [x] Define `PlaybackAccessDecision`, `PlaybackAccessMutationResult` and `PlaybackAccessPolicyResolver` without CredentialStore types.
- [x] Resolve HTTPS securely without reading credentials.
- [x] Resolve exact approved HTTP origin as approved.
- [x] Require fresh approval for another host or effective port.
- [x] Map missing/corrupt/unavailable/invalid inputs to safe typed outcomes.
- [x] Approve/revoke/revokeAll mutate only the encrypted origin set.
- [x] Preserve parent cancellation.

---

## Task 5 — Typed catalog resolution

- [x] Add internal nullable `credentialRef` only to `ActiveVariantRow`.
- [x] Keep `PlayableVariant` and public channel models free of credential references.
- [x] Return `Ready`, `InsecureTransportApprovalRequired` or `AccessUnavailable`.
- [x] Add `insecureHttpApproved` to `ResolvedPlaybackRequest`.
- [x] Add approve/revoke catalog methods that re-query the current active variant.
- [x] Inject the resolver through `RoomPlaybackCatalog`, database factory and production Hilt.
- [x] Test HTTPS, approved/unapproved HTTP, preferred source ownership and secret-safe diagnostics.
- [x] Reject a stale preferred variant instead of falling back to another active stream.

---

## Task 6 — Player confirmation state

- [x] Secure/approved resolution sends SET directly.
- [x] Unapproved HTTP sends no SET and renders only canonical exact origin.
- [x] Primary action approves and then re-resolves the current variant before setup.
- [x] Route-owned coroutine cancellation propagates; PR #38 still prevents late SET installation.
- [x] Connection epoch restarts one bounded resolution/setup attempt.
- [x] Add explicit warning and failure focus ownership.
- [x] Propagate ready `insecureHttpApproved` into `PlaybackSessionRequest`.
- [x] Add Android TV warning → D-pad approval → re-resolution → real MediaSession setup journey.

---

## Task 7 — Source-level revocation

- [x] Resolve source target/credential internally in the app layer and call `revokeAll`.
- [x] Add `Сбросить HTTP-разрешения` without rendering origins or credential IDs.
- [x] Disable during mutation and map typed safe results.
- [x] Preserve source-level HTTP refresh approval while clearing playback origins.
- [x] Missing/removed credential resolves as unavailable/not-found and leaves no independent trust store.

---

## Task 8 — Android TV acceptance and closure

API 26 and API 36 evidence on functional head `d0ceff209444e9dcb813637203a205f92107689b`:

- [x] unapproved HTTP warning appears before Media3 SET;
- [x] approval triggers re-resolution and real MediaSession setup;
- [x] different host/port and revocation behavior are covered by focused resolver/codec suites;
- [x] credential absence leaves no trust;
- [x] HTTPS flow and HTTPS → HTTP downgrade policy remain unchanged;
- [x] production manifest has no global cleartext opt-in;
- [x] D-pad/Back and secret scans pass;
- [x] DeviceMatrix run `30287803018` passed without fallback;
- [x] per profile: credentials 4, database 21, Media3 10, application 12; zero failures/errors/skips;
- [x] remove the temporary PR-specific workflow after evidence capture.

Still required after the final concurrency/stale-variant review changes:

- [ ] run final exact-head Full;
- [ ] run or justify final exact-head DeviceMatrix for the shared manager change;
- [ ] review PR patch, threads, manifests and evidence one final time;
- [ ] mark ready, squash merge and close #39.

## Self-Review

- No Room approval table or schema migration was introduced.
- Credential reference stays internal to catalog resolution.
- Exact host+port, v1 compatibility, revocation and cancellation are explicit.
- One singleton manager now owns encrypted source-access state.
- Platform defaults are not treated as the HTTP security boundary; repository clients enforce request policy.
- No unrelated product/engine scope is included.
