# Exact-Origin HTTP Playback Approval Design

**Status:** accepted for implementation under issue #39.

**Base:** `main` at `d542e83ab7c1e997e4be95c82bffa63db90e8e83`.

## Objective

Carry explicit HTTP trust from source onboarding into playback without process-wide cleartext, implicit host trust, a duplicate Room allow-list or exposure of credential references to UI/navigation.

## Verified gap

- encrypted `RemoteSourceAccess` already stores source URL and source-level `insecureHttpApproved`;
- source refresh can therefore import an explicitly confirmed HTTP playlist;
- active variants retain their source and locator, but playback resolution does not read the encrypted trust decision;
- `ResolvedPlaybackRequest` contains no approval state;
- `PlayerRoute` constructs `PlaybackSessionRequest` with the default `insecureHttpApproved = false`;
- the request-scoped Media3 interceptor correctly requires an exact root-origin approval, but the bit never reaches it.

Result: an HTTP playlist may import successfully while a same-origin HTTP channel is rejected during playback.

## Selected architecture

### Single encrypted source of truth

Keep approvals inside the existing encrypted source credential record. Do not add a Room approval table in this package.

Benefits:

- no duplicated trust state or cross-store cleanup race;
- no Room migration for security-sensitive topology;
- credential removal/reset removes all approvals;
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

Approval/revocation methods identify the current variant by profile/channel/variant ID and re-query the active catalog before mutating encrypted trust. A stale warning cannot approve an inactive locator.

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
- a future source-edit operation must clear old approvals when origin changes.

## Redirect and header behavior

This package does not weaken PR #36:

- HTTPS root → HTTP target remains rejected;
- HTTP approval authorizes only the exact approved root origin;
- cross-origin sensitive headers remain stripped;
- Android manifest/network-security config remains globally cleartext-denied.

## Concurrency and cancellation

- encrypted record mutations are serialized by `RemoteSourceAccessManager`;
- parent cancellation propagates;
- cancellation before write leaves the old record unchanged;
- Player approval is serialized and re-resolves current catalog state;
- merged PR #38 SET/CANCEL ownership still prevents a cancelled route from installing a late request.

## Testing

### Unit/JVM

- exact origin canonicalization/redaction;
- encrypted v1/v2 codec compatibility and bounds;
- safe read/update/revoke results;
- exact host/port resolver decisions;
- catalog internal credential lookup without public leakage;
- Player approval/re-resolution/cancellation sequencing;
- Sources reset action mapping.

### Android TV

API 26 and API 36:

- approved HTTP source → same-origin channel setup;
- different host and different port warnings;
- approval → re-resolution → Media3 SET;
- revoke/reset → warning returns;
- credential removal → no surviving trust;
- HTTPS → HTTP downgrade still rejected;
- production manifest contains no global cleartext opt-in;
- reports/logcat/screenshots/semantics contain no locator/query/header/credential fixtures.

## Non-goals

- global HTTP switch;
- automatic trust inferred from a successful request;
- provider-specific exceptions;
- active-source deletion redesign;
- full Sources visual redesign;
- fallback/TV Doctor, EPG, Rust/libmpv or another player engine;
- physical codec/HDR/Fire OS claims.

## Exit criteria

1. Explicitly approved HTTP source can play same-origin variants.
2. Another host or effective port requires fresh confirmation.
3. Trust is persisted only in encrypted source access.
4. Revocation/reset removes trust deterministically.
5. Missing/corrupt credential never implies approval.
6. New source/origin does not inherit old trust.
7. HTTPS downgrade remains rejected.
8. Production manifest retains no process-wide cleartext opt-in.
9. No secret locator/header/credential value crosses durable/UI/diagnostic boundaries.
10. Focused tests, Full and API 26/API 36 DeviceMatrix pass on reviewed exact heads.
