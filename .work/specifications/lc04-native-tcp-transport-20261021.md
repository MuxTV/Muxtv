---
status: PRODUCT_CANDIDATE_NO_APP_BINDING_NO_SOURCES
last_reviewed: 2026-10-21
issue: 401
depends_on: [412, 414, 415, 416, 417, 418, 419]
---

# LC04-S3-E3A — independent bounded TCP transport (GitHub CI only)

## Decision

Accepted #419 proves the actual TV-owned Tink HPKE receiver, TV-approved
Ed25519 verifier and phone-held one-shot AES-GCM reply guard work in the
Android-compatible code and on two hosted TV emulators. It did **not** send
messages across a socket. Reusing the old browser/legacy Ktor HTTP `/pair`
would mix a plaintext bearer/session mechanism with a much stronger QR-pinned
identity protocol and risks introducing SOURCE_* operations before bilateral
authentication.

This slice adds an **entirely separate** pure-Kotlin
`:local-control:native-transport` containing a real TCP `ServerSocket` TV
listener and `Socket` phone peer plus bounded, one-frame-per-connection
handling. It is intentionally not yet referenced by `:app:tv` or
`:app:phone`. No phone `INTERNET` permission, Android manifest listener,
service/Activity, legacy HTTP route, source type or credential input changes.

## On-wire framing (experimental)

The accepted `MXW1` binary frame is encapsulated in exactly one TCP
record per connection:

- Prefix: 4-byte big-endian positive length (1..4200).
- Request: one canonical `MXW1` frame; only `PHONE_PUBLIC_KEY`,
  `PHONE_PROOF` or `SIGNED_CHANNEL_PROBE` can enter.
- Response: another size-prefixed canonical `MXW1` frame, or a 4-byte
  **zero length** (generic no response). Zero length is NEVER an
  authenticated success, approval or proof of physical TV possession.
- The socket is then closed. There is no streaming, chunked HTTP, batch,
  credential payload, migration fallback or renegotiation.
- Incomplete header/body, out-of-range length, unknown kind, extra length,
  incompatible reply kind, timeout and early EOF reject closed. Max
  allocation of untrusted frame bytes = 4200.
- Client connect timeout 1500 ms; both directions read timeout 2500 ms.
  An explicit phone endpoint is a private RFC1918 **numeric IPv4 literal**;
  no DNS, proxy, loopback/wildcard, mDNS or IPv6 parsing.
- TV must explicitly bind a selected RFC1918 interface and an ephemeral
  port. There is no wildcard bind, no always-on listener and no port in
  the scanned QR. The location remains a **locator**, not a trust root.

## TV routing and authorization boundary

`Lc04TvFrameDispatcher` has only three request transitions:

1. `PHONE_PUBLIC_KEY` -> actual original TV QR gate's
   `beginPublicOffer`; optionally return public, context-bound
   `TV_CHALLENGE`. Remote input cannot call `approveOnTv`.
2. `PHONE_PROOF` -> existing TV owner's pending proof verifier. Only
   after **separate local UI approval** and valid Ed25519 proof does it
   activate `TvHpkeQrSession.activateVerifiedPhone`. Regardless of
   success, return no unauthenticated paired/authorized token.
3. `SIGNED_CHANNEL_PROBE` -> original TV's signed verifier, private
   HPKE decrypt and AES-GCM receipt, which the phone verifies with
   `PhonePendingProtectedProbe.verifyReply`. Only fixed synthetic
   48-byte challenge/key payloads pass this path.

TV QR lifetime remains 120 seconds; network stop/close revokes TV receiver
and enrollment state. Request handling is single-threaded per listener,
with bounded global 48/min and per-peer 12/min request budgets, max 16
peer-map entries, maximum 8 pending TCP accepts, strict socket read
deadline and fail-closed clock rollback. These are availability guardrails,
**not** a complete defense against LAN DoS or on-path peer spoofing.
A malicious sender can fill the one pending enrollment slot; mitigation
requires TV QR rotation and TV-visible refusal in the eventual UI.

## GitHub-hosted attack and interoperability evidence

`Lc04NativeTcpTransportTest` uses *real operating-system loopback sockets
on a GitHub-hosted Ubuntu runner*, not in-memory calls or physical
Android devices. A test-only internal factory permits loopback while all
production factories require RFC1918 numeric IPv4.

- TV-generated HPKE QR -> public key offer over TCP -> real challenge
  -> TV-local approval -> signed Ed25519 proof over TCP -> signed HPKE
  `CHANNEL_PROBE` over TCP -> original-request-bound AES-GCM TV
  receipt -> one-use phone verification.
- Proof transmitted **before** local approval cannot grant an identity.
- An attacker with only the public TV QR cannot replace the approved
  phone signing key; genuine signer remains operational.
- Tampered pinned-TV-key header, malformed content-length (including
  4201), truncated body, replayed probe or reply are refused.
- A hostile fake TV listening on a different real TCP port can return a
  syntactically valid context-matching receipt, but cannot provide the
  correct GCM tag/request key and therefore cannot authenticate itself.
- Stop, expired QR, loopback/public/DNS/wildcard/untrimmed address
  validation, rate budget and clock rollback are covered.
- Existing Tink/JVM, UI, Android API26/API36 and full hosted CI remain
  separately mandatory; no physical devices or self-hosted runners.

## Explicit NO-GO

This is the first real socket **library**; it is not yet a runnable
phone-to-TV feature. There is no Android composition-root ownership,
network-change callback, TV UI displaying/selecting the endpoint, phone
permission/network client integration or production protocol freeze.
Explicit private address enforcement does not make a remote endpoint
trustworthy: only the physical QR and cryptographic reply do.

DO NOT add `SourceOnboarding`, source import, M3U/Xtream URL, username,
password, bearer, cookies, arbitrary message types, or existing plaintext
`/v1/pair/*` authority to this module.

Next: if exact-head hosted CI is green, integrate opt-in start/stop with
TV foreground lifecycle and network-change revocation; establish a
secure, separately displayed locator and opt-in Android phone permission,
then test two app processes and hostile network redirection on hosted
emulators before even considering IPTV credential transport.
