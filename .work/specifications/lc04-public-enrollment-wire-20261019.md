---
status: PRODUCTION_CANDIDATE_PUBLIC_ONLY_NO_NETWORK
last_reviewed: 2026-10-19
issues: [401]
depends_on: [407, 410, 411, 412, 414, 415, 417]
---

# LC04-S3-E1 — public enrollment offer, challenge, proof adapter

## Decision

Stop inventing additional unrelated crypto labs. The accepted native Tink
`:local-control:enrollment` and TV receiver `:local-control:tv-qr` already
verify Ed25519 key possession but originally exchanged Kotlin objects inside
tests. The accepted `:local-control:wire-lab` already rejects malformed
binary MXW1 frames, but had no authorization boundary.

This slice connects the two **strictly at the PUBLIC enrollment boundary**
in a product-compatible pure Kotlin module and on both real Android Tink
providers in the GitHub-hosted TV API26/API36 emulators. It does NOT enable
a TCP port, HTTP route, Android INTERNET permission, source API, credential
transfer or an automatic approval.

The MXW1 envelope remains experimentally specified. Connecting this
adapter to a real network requires an explicit protocol freeze, DoS limits
and adversarial transport CI. Until then it is production-candidate code
without a product-facing packet path.

## Three concrete binary messages

1. `PHONE_PUBLIC_KEY` — constructed by Android phone from the **actual
   parsed TV public HPKE QR** and its ephemeral Tink ED25519 public-only
   keyset; includes 16-byte invitation and pairing IDs, 32-byte TV HPKE
   fingerprint and SHA256 of the phone keyset. It carries **no private key,
   login, URL, source operation, bearer or credential**.
2. `TV_CHALLENGE` — constructed from an actual pending TV enrollment gate.
   The 32-byte random nonce plus all context markers and exact pending
   phone fingerprint are serialized. The phone checks every context field
   against its OWN scanned QR and OWN ephemeral identity. The challenge
   itself is public and is NOT authenticated as coming from TV yet; an
   attacker can substitute it, but the actual TV will not validate a
   proof of a different nonce.
3. `PHONE_PROOF` — detached 64/69-byte Tink Ed25519 proof signed over
   the independently frozen `MuxTV-LC04-PHONE-ENROLL-v1` transcript.
   TV checks invitation, pairing, TV HPKE fingerprint and its OWN pending
   phone fingerprint before invoking `TvPhoneEnrollment.verify`.
   The same TV-local fingerprint consent gate, expiry, failure budget and
   replay protections remain in effect.

The adapter verifies canonical frame bytes through the accepted bounded
parser. The phone offer's declared SHA256 must equal the digest of its
actual public keyset. TV then validates the public-only canonical Tink
Ed25519 keyset before creating pending UI state. A malicious sender cannot
approve its own fingerprint by encoding a valid offer.

No wire message contains the verified identity/capability result. The
result is private to the TV process. It is NOT a URL, cookie, SOURCE_CREATE
grant, persisted pairing token, or authenticated reply.

## Security and negative CI matrix

- Android API26/API36: real Tink phone key creation, real HPKE TV QR,
  serialized offer → challenge → proof, denial before TV consent,
  acceptance only after explicit consent and replay refusal.
- Host JVM: malformed/truncated/extra-long frames; wrong version/kind;
  wrong TV invitation/pairing/fingerprint or different phone key;
  tampered phone key payload/fingerprint; altered TV challenge;
  false proof; TTL/TV receiver revocation. Rejections must not create
  a verified identity or fall back to plaintext pairing.
- TV's exact original QR receiver owns gate and revokes it on Activity
  stop/network change. All operations are in-process test calls only.
- Build logic prevents the existing old HTTP server from importing
  the enrollment module; it cannot dispatch a credential or SOURCE
  operation through these three message types.

## Open risks and next required implementation

- The first untrusted public offer can occupy the single pending slot
  for its TTL (availability DoS). A future real LAN endpoint must enforce
  source-neutral rate/concurrency limits, TV-visible rejection/QR rotation,
  bounded request bodies, timeouts and no sensitive response leaks.
- The unauthenticated 32-byte TV challenge is **not server identity
  verification**. The phone must use a signed-HPKE probe with an
  authenticated TV reply as qualified in lab #417, with single-use
  response state #416. A plaintext HTTP success is forgeable.
- A QR with HPKE public key alone is not the IP address/port. Endpoint
  discovery may be untrusted; the app must never treat a LAN address as
  the authentication root or reuse the old `http://LAN/pair` bearer.
- The public `MXW1` message framing must be frozen with exact
  cross-platform canonical codecs and failure budgets before real sockets.
- Android product LAN transport, synchronized TV consent and full hostile
  packet tests (MITM, drop, reorder, replay, rollback, revoke, restart)
  still require exact-head GitHub CI and API26/API36 AVD qualification.
- No `SourceOnboarding` authorization or M3U/Xtream URL, token,
  username or password shall be sent until the entire mutual-auth
  transport and TV consent pass security review and CI.

This step deliberately **does not** claim a complete phone-to-TV pairing
flow or functional IPTV source import.
