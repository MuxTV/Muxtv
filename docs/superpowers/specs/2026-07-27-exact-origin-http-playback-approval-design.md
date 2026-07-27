# Exact-Origin HTTP Playback Approval Design

**Status:** implemented in PR #42; awaiting final reviewed-head verification and merge.

**Base:** `main` at `d542e83ab7c1e997e4be95c82bffa63db90e8e83`.

## Objective

Carry explicit HTTP trust from source onboarding into playback without process-wide cleartext opt-in, implicit host trust, a duplicate Room allow-list or exposure of credential references to UI/navigation.

## Verified gap

Before PR #42:

- encrypted `RemoteSourceAccess` stored source URL and source-level `insecureHttpApproved`;
- source refresh could therefore import an explicitly confirmed HTTP playlist;
- active variants retained their source and locator, but playback resolution did not read the encrypted trust decision;
- `ResolvedPlaybackRequest` contained no approval state;
- `PlayerRoute` constructed `PlaybackSessionRequest` with the default `insecureHttpApproved = false`;
- the request-scoped Media3 interceptor required an exact root-origin approval, but the bit never reached it.

Result: an HTTP playlist could import successfully while a same-origin HTTP channel was rejected during playback.

## Selected architecture

### Single encrypted source of truth and owner

Keep approvals inside the existing encrypted source credential record. Do not add a Room approval table in this package.

`RemoteSourceAccessManager` is the single in-process read/write owner for encrypted source access. Onboarding, refresh and playback-approval resolution share the same singleton instance in the production Hilt graph. Reads, saves, removes and read-modify-write updates cross one serialized boundary, preventing an approval mutation from overwriting a concurrent source-access change with a stale record.

Benefits:

- no duplicated trust state or cross-store cleanup race;
- no Room migration for security-sensitive topology;
- credential removal/reset removes all approvals;
- no independent resolver/refresher write mutexes;
- existing Android Keystore/DataStore boundaries remain authoritative.

### Exact HTTP origin

Approval identity is:

```text
http scheme + normalized host + effective port
```

Canonical examples:

```text
http://example.test:80
http://example.test:8080
http://[2001:db8::1]:80
```

Only `http` is approvable. Different ports are different origins. Username/password are rejected. Path, query and fragment never enter the encoded/display value. Diagnostics are redacted.

### Encrypted record v2

`RemoteSourceAccess` v2 stores a bounded set of approved playback origins (`max 16`) in addition to the existing source URL, source-level HTTP confirmation and optional headers.

Backward decode:

- v1 HTTPS record → no HTTP playback origin;
- v1 HTTP with `insecureHttpApproved=true` → seed only the source URL origin;
- v1 HTTP with approval false → no origin.

A new HTTP source confirmation seeds only the exact source URL origin. A stream on another host or port requires a fresh Player confirmation.

Source-refresh approval and playback-origin approval are separate. Revoking all playback origins does not disable an already confirmed HTTP playlist refresh.

### Catalog resolver

`catalog:api` defines a narrow resolver with typed outcomes:

```kotlin
sealed interface PlaybackAccessDecision {
    data object SecureTransport : PlaybackAccessDecision
    data object Approved : PlaybackAccessDecision
    data class ApprovalRequired(val displayOrigin: String) : PlaybackAccessDecision
    data object CredentialNotFound : PlaybackAccessDecision
    data object CredentialCorrupted : PlaybackAccessDecision
    data object CredentialUnavailable : PlaybackAccessDecision
    data object InvalidLocator : PlaybackAccessDecision
}
```

`core:database` selects `sources.credentialRef` only in the internal active-variant row. It is passed to the resolver and never added to `PlayableVariant`, `ResolvedPlaybackRequest`, Compose state or navigation.

`PlaybackCatalog.resolveVariant` returns a typed resolution:

```kotlin
sealed interface PlaybackVariantResolution {
    data class Ready(val request: ResolvedPlaybackRequest) : PlaybackVariantResolution
    data class InsecureTransportApprovalRequired(
        val channelId: String,
        val variantId: String,
        val displayOrigin: String,
    ) : PlaybackVariantResolution
    data class AccessUnavailable(val reason: PlaybackAccessUnavailableReason) : PlaybackVariantResolution
}
```

A Ready request carries only the safe boolean `insecureHttpApproved` required by Media3.

Approval/revocation methods identify the current variant by profile/channel/variant ID and re-query the active catalog before mutating encrypted trust. A supplied stale variant ID returns `NotFound`; it never falls back to another active stream.

### Player flow

```text
Connecting → Resolving → Ready
                      ↘ HTTP approval required
                         → Approving
                         → re-resolve active variant
                         → Ready / safe failure
```

The warning displays only canonical exact origin and two actions:

- `Разрешить для этого адреса`
- `Назад к каналам`

The route never stores locator, headers, credential reference or `PlaybackSessionRequest` in saveable state. It re-resolves after approval rather than using the pre-approval request.

### Revocation

Issue #39 adds a bounded source-level action `Сбросить HTTP-разрешения`, backed by the existing source credential reference obtained through `SourceRefreshStore.getTarget(sourceId)` in the app layer. It renders no origin list or credential value.

There is no complete active-source deletion product yet. The executable security invariant is:

- approvals exist only inside the credential record;
- credential removal/reset leaves no independent trust state;
- missing credential always resolves as unavailable, never approved;
- changing a source URL through a future edit operation must clear old origins before seeding a newly confirmed source origin.

## Redirect, manifest and header behavior

This package does not weaken PR #36:

- HTTPS root → HTTP target remains rejected;
- HTTP approval authorizes only the exact approved root origin;
- cross-origin sensitive headers remain stripped;
- the production manifest adds no process-wide cleartext opt-in or global allow-list;
- repository-owned source and Media3 clients remain responsible for request-scoped HTTP policy, including on older API levels where platform defaults alone are not the security boundary.

## Concurrency and cancellation

- all encrypted source-access reads and mutations cross one shared `RemoteSourceAccessManager`;
- read-modify-write mutations are serialized with the singleton manager mutex;
- parent cancellation propagates through manager, resolver, refresher and Player operations;
- cancellation before a credential write leaves the old record unchanged;
- Player approval re-queries the current active variant before setup;
- merged PR #38 SET/CANCEL ownership still prevents a cancelled route from installing a late request.

## Testing

### Unit/JVM

- exact origin canonicalization/redaction;
- encrypted v1/v2 codec compatibility and bounds;
- safe read/update/revoke results;
- concurrent updates preserve both approvals and unrelated access fields;
- exact host/port resolver decisions;
- catalog internal credential lookup without public leakage;
- stale variant cannot approve another active stream;
- Player approval/re-resolution/cancellation sequencing;
- Sources reset action mapping.

### Android TV

API 26 and API 36:

- unapproved HTTP renders only canonical origin before SET;
- approval triggers a fresh catalog resolution before real MediaSession setup;
- exact-host/port, missing credential and revocation contracts are exercised by the same reviewed build and focused suites;
- HTTPS → HTTP downgrade remains rejected;
- production manifest contains no global cleartext opt-in;
- reports/logcat/screenshots/semantics contain no locator/query/header/credential fixtures.

The emulator matrix validates Android API, lifecycle, Room, Keystore, focus and MediaSession contracts. It does not certify vendor decoders, HDR, passthrough, Fire OS or constrained ARM hardware.

## Non-goals

- global HTTP switch;
- automatic trust inferred from a successful import or request;
- provider-specific exceptions;
- active-source deletion redesign;
- full Sources visual redesign;
- fallback/TV Doctor, EPG, Rust/libmpv or another player engine;
- physical codec/HDR/Fire OS claims.

## Exit criteria

1. Explicitly approved HTTP source can play same-origin variants.
2. Another host or effective port requires fresh confirmation.
3. Trust is persisted only in encrypted source access.
4. Revocation/reset removes playback trust deterministically without breaking source refresh approval.
5. Missing/corrupt credential never implies approval.
6. New source/origin does not inherit old trust.
7. HTTPS downgrade remains rejected.
8. Production manifest retains no process-wide cleartext opt-in.
9. No secret locator/header/credential value crosses durable/UI/diagnostic boundaries.
10. Focused tests, Full and API 26/API 36 DeviceMatrix pass on reviewed exact heads.
