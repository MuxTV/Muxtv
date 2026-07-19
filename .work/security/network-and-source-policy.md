---
status: accepted
last_reviewed: 2026-07-19
owners: [network, security, provider]
---

# Network and source policy

## 1. Клиенты

MuxTV использует отдельные логические HTTP clients/policies:

```text
SourceClient        playlists/provider API
EpgClient           XMLTV
MediaClient         manifests/segments through player integration
ImageClient         logos/artwork
UpdateClient        official release metadata/artifacts
LocalControlClient  browser-facing local API is separate server boundary
```

Cookies, auth, cache and redirect settings не разделяются автоматически между clients.

## 2. SourceNetworkPolicy

```kotlin
data class SourceNetworkPolicy(
    val addressScope: AddressScope,
    val cleartextMode: CleartextMode,
    val redirectPolicy: RedirectPolicy,
    val credentialPolicy: CredentialPolicy,
    val timeoutProfile: TimeoutProfile,
    val proxyPolicy: ProxyPolicy,
    val certificatePolicy: CertificatePolicy,
)
```

Defaults:

```text
addressScope = PublicInternetOnly
cleartextMode = Deny
redirectPolicy = SameOriginSensitiveHeaders
credentialPolicy = OriginBound
certificatePolicy = SystemTrustOnly
```

User may explicitly approve `PrivateNetworkAllowed` or cleartext per source/host after warning. There is no global «allow everything» shortcut in simple mode.

## 3. Address validation

Reject by default:

- loopback IPv4/IPv6;
- link-local;
- multicast;
- unspecified/broadcast;
- IPv4-mapped forms of blocked IPv4;
- cloud metadata ranges/endpoints;
- malformed/obfuscated numeric addresses;
- local device content/file schemes from remote input.

RFC1918/ULA/private addresses require source-level LAN approval. Resolution is checked:

1. before request;
2. for every redirect target;
3. against actual connected address where transport permits;
4. after DNS changes for long-lived re-resolution.

A hostname approved for public internet that resolves to private address is blocked pending explicit approval.

## 4. Redirect policy

Default budgets:

```text
maximum redirects: 5
protocol downgrade HTTPS→HTTP: blocked
cross-origin Authorization/Cookie: stripped
cross-origin Referer: reduced/stripped by policy
redirect loop: error
```

Origin is scheme + host + effective port. Subdomain is cross-origin unless source explicitly defines trusted origin set.

Sensitive headers may cross origin only through explicit provider adapter rule with visible scope and tests. User-entered generic headers do not imply trust of all redirects.

## 5. Cleartext HTTP

Android Network Security Configuration provides baseline restrictions, but runtime user-added hosts require application-level enforcement too.

Policy:

- HTTPS preferred/default;
- HTTP stream/source can be approved per source/host because real IPTV ecosystems still use it;
- approval UI states traffic is unencrypted and host can observe requests;
- credentials over HTTP require stronger warning and are disabled by default;
- update and extension distribution never use cleartext;
- no trust-all certificate mode;
- self-signed/private CA support, if added, uses scoped user-imported trust material and separate ADR.

## 6. Headers and credentials

Sensitive names:

```text
Authorization
Proxy-Authorization
Cookie
Set-Cookie
X-Api-Key and configured secret names
provider token/password/MAC/device identifiers
```

Rules:

- headers represented as typed policy entries with sensitivity;
- logs store only name and redacted marker;
- user-agent/referer may be configured, but value is still redacted if it embeds token;
- CR/LF/control characters rejected;
- duplicate conflicting auth headers rejected;
- Host/Content-Length/Connection and transport-controlled headers cannot be overridden generically;
- source credentials are not sent to EPG/logo URLs unless independently configured;
- cookie jar scoped to source/origin and bounded.

## 7. Timeouts and retries

Different operations use profiles:

```text
MetadataFast
PlaylistDownload
EpgLargeDownload
MediaManifest
HealthProbe
UpdateDownload
```

Timeouts include connect/read/write/call plus progress watchdog for large downloads. Retry policy distinguishes idempotent GET from mutations/provider login.

- 401/403 not blindly retried;
- 429/503 respects validated Retry-After;
- DNS/connect/reset may retry with backoff;
- media segment retries coordinated with Media3 policy;
- no retry explosion across OkHttp, Media3, WorkManager and orchestrator layers;
- a single layer owns each retry class.

## 8. Proxy/VPN

- application follows system VPN by default;
- per-source HTTP/SOCKS proxy is expert-only and credentials stored securely;
- proxy cannot bypass destination address policy without explicit mode;
- diagnostics distinguish origin DNS from proxy resolution where knowable;
- no bundled public proxy/VPN;
- proxy secrets redacted;
- certificate interception errors are not bypassed automatically.

## 9. TLS

- system trust and hostname verification mandatory baseline;
- certificate errors produce actionable diagnostics, not «ignore SSL»;
- certificate pinning is not used for arbitrary providers because endpoints/CDNs change;
- updater may use stronger repository/artifact verification rather than brittle TLS pins;
- custom CA/import and mTLS require separate provider-specific design;
- TLS versions/ciphers follow platform unless security compatibility ADR documents exception.

## 10. Images

ImageClient:

- no provider auth inheritance by default;
- scheme/address/redirect validation;
- maximum download bytes and decoded dimensions;
- content sniffing and supported image formats;
- SVG allowed only with safe renderer/no scripts/external resources, otherwise rejected;
- cache key strips secrets safely while preventing credential-context collision;
- failed images do not block catalog commit;
- image load cannot redirect into local network unless source policy permits.

## 11. Update client

UpdateClient is fixed to official repository/release endpoints and cannot be configured through playlist/provider data.

- HTTPS only;
- no arbitrary redirects outside approved GitHub asset infrastructure without validation;
- package/certificate/checksum verified;
- provider headers/cookies never attached;
- GitHub API rate/cache behavior handled separately;
- downloaded APK stored app-private until PackageInstaller handoff.

## 12. Local provider discovery

Automatic LAN discovery is deferred. When added:

- explicit user action/time window;
- protocol-specific discovery only;
- no broad port scan;
- results displayed before saving;
- discovered host receives `PrivateNetworkAllowed` only after confirmation;
- discovery stops when screen/session ends.

## 13. Observability

Network event fields:

```text
operation/correlation ID
source/variant opaque ID
policy ID/version
status/error category
redirect count/origin-change flag
bytes/timings
address family and network transport
cache result
```

Hostname/raw path disclosed only in expert diagnostics by explicit user action; secrets remain redacted.

## 14. Tests

- IPv4/IPv6 address classification including mapped/encoded forms;
- DNS rebinding simulation;
- same-origin/cross-origin redirect matrix;
- sensitive header stripping;
- HTTPS→HTTP downgrade rejection;
- per-host cleartext approval;
- invalid certificate no bypass;
- Retry-After/backoff ownership;
- cookie isolation between source/EPG/image;
- oversized logo and redirect SSRF;
- updater cannot use user source policy;
- cancellation and process death for large downloads;
- log redaction property tests.

## 15. Acceptance criteria

- user-added public URL cannot silently access private/loopback targets;
- approved LAN source works with explicit scoped policy;
- credentials never cross origin by default;
- HTTP can be enabled only per source/host with warning;
- TLS verification cannot be disabled globally;
- image/EPG/update traffic does not inherit stream credentials;
- retry ownership prevents multiplicative retry loops;
- update traffic is isolated from user-configurable clients.