# LC04-S2J2C2C — installed Android TV/Phone six-frame MXH1/MXR1 opt-in

Refs #401, parent #441 (S2J2C2B) and #440 (S2J2C2A). Base = exact #441
`dbbcb0102e5b488449867ebcb5f2e85cd4dbae24`.
This is a stacked Draft. The 31/31 exact-head hosted workflows on BOTH
parents were GREEN before this change, but neither parent is merged or
security-reviewed independently. No source credentials, bearer, or release claim.

## Why this slice exists

Prior TV and phone APKs performed a separately opted-in **four-frame** MXH1
exchange. The six-frame 104-byte AES-GCM MXR1 protocol from #440/#441 was
isolated in host-side transport and therefore did not work through the
actual Android UI. Pointing its phone at the old four-frame TV listener
would fail because the old TV owner consumes its keys after TV_FINISHED.

## Implemented Android wiring

- MainActivity's existing API37 local-network permission-gated second
  TV D-pad action calls `enableSealedMxH1ForApprovedVisibleQr()`.
  TV preserves the SAME original QR owner, original MXW1 listener,
  TV-local-approved Ed25519 identity, QR-generation fence and
  selected default Wi-Fi/Ethernet/private IPv4 address.
- TV production `Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4`
  opens a new single-peer TCP listener for F1-F6, never wildcard,
  public, loopback, hostname or VPN route. Its private ctor cannot
  be invoked outside its validated factory.
- The old four-frame TV factory and function remain for regression
  compatibility but cannot start concurrently with the new one;
  the app UI now uses ONLY the new six-frame option.
- Phone's separate, post-MXW1 `beginSealedMxH1` user action requires
  the already-verified MXW1 AES-GCM receipt and the existing same
  TV-QR-pinned ephemeral Ed25519 identity. A new public
  `Lc04MxH1SealedPrivateLanPhone` is a minimal diagnostic façade
  around the still-internal #440 one-use leased transport. The app
  does NOT import the internal test-socket seam or expose keys.
- Android phone calls **actual `selected.bindSocket(unconnected)`**
  before connecting. `selectedRouteEligible` checks the exact
  initial default Network, unchanged original private-link addresses,
  Wi-Fi/Ethernet capability, absence of VPN/cellular, API37+
  `ACCESS_LOCAL_NETWORK` permission, active original session phase
  and application visibility/lifetime (onStop closes session).
  The lease rechecks route before/after each protocol frame.
- Existing Activity.onStop, onDestroy and callback-driven route
  invalidation synchronously close active sockets on both ends.
  Source/credential fields are still absent, retry remains prohibited.
- The UI uses the same second-stage port field and D-pad controls,
  now labelled MXH1/MXR1 six-frame synthetic confirmation. The
  first QR scan never implicitly establishes any TCP connection.

## Exactly what passes over the network

SIGNED_SEED_OFFER, TV_KEY_CONFIRM, PHONE_KEY_CONFIRM, TV_FINISHED,
one sealed AES-256-GCM 104-byte synthetic F5 with phone half-close,
and one sealed AES-256-GCM 104-byte synthetic F6 with TV EOF.
No provider URL, username, password, arbitrary plaintext, HTTP
`/pair`, session grant, durable key, or SourceOnboarding permission.
The TV never emits F6 before authenticating F5 plus exact EOF.

## Hosted CI admission

The existing GitHub-hosted LC04 Android private-LAN workflow now
includes a source-boundary static test for both actual Android caller
paths; tests `:local-control:mxh1-lan:test`, historic MXW1 and
MXH1/MXR1 hostile TCP suites, native transport and Android TV/phone
`compileDebugKotlin` at exact HEAD. Android API26/API36 device
matrices are additionally required when triggered. No skips or
local/self-hosted runners; independent cryptography/security review.

## Residual STOP gates

1. This change wires **installed Android code** rather than only
   JVM lab, but GitHub-hosted compilation and single-AVD tests do
   **not establish successful cross-device TV ↔ phone Wi-Fi/Ethernet
   interoperability**. That must be tested with two actual selected
   network devices and a controlled hostile peer before acceptance
   for end users.
2. API37/Android 17 runtime permission grant, denial and revocation
   need actual API37 device evidence. API26/API36 cannot substitute.
3. A one-shot six-frame synthetic result is **not** a usable
   application-data channel. The current key port intentionally
   rejects arbitrary message bodies and closes keys. Next separately
   security-reviewed slice must design a bounded, replay-safe,
   typed, session-owned application record protocol with explicit
   TV consent, then integrate the existing
   `ProtectedSourceStaging` / `SourceOnboarding.prepare/activate/cancel`.
   Failure/cancellation must never blindly retry an unknown
   activation outcome.
4. Final user acceptance requires phone M3U/Xtream form, TV approval
   and staging/activation into catalog, visible TV channels and first
   Media3 frame, with safe secret handling and cancellation tests.

**No merge until all required exact-head checks and independent review;
merge parents in dependency order.** A green synthetic protocol test
is not permission to transfer IPTV credentials.
