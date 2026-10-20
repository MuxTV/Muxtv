---
status: PRODUCTION_CRYPTO_COMPONENT_NO_NETWORK
last_reviewed: 2026-10-20
issues: [401]
depends_on: [412, 414, 415, 416, 417, 418]
---

# LC04-S3-E2 — native Android two-sided signed HPKE probe and one-shot TV receipt

## Concrete problem

#412 implements a real Tink Ed25519 phone enrollment gate owned by the
actual TV private X25519 HPKE QR. #418 connects a strict, PUBLIC-only 3-message
OFFER/CHALLENGE/PROOF exchange. #415/#416 implement exact signed request
transcript, authenticated GCM AAD and one-shot reply guard; #417 simulated
both ends under JDK17. Previously these were disconnected across native
Android product modules, so a test could not verify the entire two-sided
challenge-response on Android providers.

This slice connects those **existing product-compatible components** without
adding a network endpoint or provider/source payload. It is NOT completed
device pairing, a functional provisioning feature or a qualified LAN service.

## Native exchange

1. Phone `PhoneProtectedProbeFactory` uses the actual strictly scanned
   `MXLC04:1` TV public HPKE key and the existing ephemeral
   `PhoneSigningIdentity`. It generates 32 fresh bytes for the reverse AES
   key and 16 fresh bytes for a one-time phone challenge. It encrypts the
   48-byte synthetic-only payload with Tink X25519/HPKE.
2. HPKE `contextInfo` is domain separated and exactly binds TV invitation,
   TV pairing, pinned TV HPKE SHA256, phone Ed25519 SHA256, message version/
   direction and monotonic request counter (max 128).
3. `PhoneSigningIdentity.signProtectedProbe` signs the #415 canonical
   `SIGNED_CHANNEL_PROBE` statement over original encrypted payload and all
   context. It never exposes or signs arbitrary text, provider source data,
   legacy HTTP pairing tokens or credentials.
4. Only the TV-owned `TvPhoneEnrollment` may redeem an already
   TV-locally-approved Ed25519 phone fingerprint; it retains this approved
   fingerprint while the original ephemeral receiver remains live.
5. TV `TvHpkeQrSession.activateVerifiedPhone` requires the actual gate's
   redeemed key fingerprint and verifies invitation, pairing and TV HPKE
   fingerprint before enabling Tink `PublicKeyVerify`. An offer or UI
   approval alone is insufficient.
6. `TvHpkeQrSession.acceptProtectedProbe` first validates canonical frame
   and complete QR/phone pins, counter and Ed25519 signature with the
   redeemed public key. Only then does it invoke the original ephemeral
   `HybridDecrypt`. It rejects forged, stale or replayed probes.
7. TV accepts only the 48-byte fixed CHANNEL_PROBE synthetic payload; no
   application/source commands. It produces AES-256-GCM receipt with unique
   random IV, #415 direction-separated AAD and digest of the *original
   signed* frame, then commits the accepted counter.
8. Phone `PhonePendingProtectedProbe` owns the exact signed request and
   #416 `Lc04OneShotReplyGuard`. Verification requires pinned full context,
   original-request SHA256, GCM tag and exact challenge. Successful receipt
   consumes the key; a replay cannot be accepted. Expiry, monotonic
   rollback, failure budget and explicit close revoke pending state.

## CI evidence requirements, NO physical tests

- Hosted JVM: actual product modules TV QR + phone key signer + bounded
  typed wire + one-shot GCM reply, accept-once, host attacker mutation
  of signed header/ciphertext/signature, wrong identity before enrollment,
  cross-QR receiver, expiry and out-of-order counters.
- Android TV GitHub-hosted emulator API26 AND API36: genuine Tink HPKE
  encrypt/decrypt, genuine Tink Ed25519 proof and canonical request verify,
  javax.crypto AES-256-GCM TV-to-phone and one-use proof/reply rejection.
- Compiled native TV and phone APK with only GitHub-hosted runners.
- Full repo validation (Gradle/build-logic/lint) on *exact HEAD*.

## Security limitations / explicit NO-GO

- There is **NO Android LAN socket, HTTP route, discovery, bootstrap address,
  network binding or INTERNET permission** in this change. No virtual
  machine network harness is claimed by Android cryptography tests.
- The signed challenge is a synthetic-only constant response. It does not
  authenticate arbitrary server application payloads or streaming data.
- No `SourceOnboarding`, M3U, Xtream, username, password or provider URL
  is accepted or transmitted in these frame kinds.
- The TV's private HPKE key exists only until original QR lifecycle expiry
  or revocation. Managed JVM memory does not guarantee complete zeroization.
- `MXW1` is an experimental encoding until the real transport protocol
  is frozen and reviewed. Untrusted endpoint discovery and host spoofing
  require a dedicated hostile-LAN CI matrix and strict size/rate limits.
- The current TV controller does NOT route product network traffic to these
  methods. Any transport binding must preserve the verified identity and
  TV-local consent gates and must not import the old plaintext `/pair`.
- This crypto path alone cannot grant `SOURCE_CREATE`. Product principal
  authorization and source provisioning are explicitly **NO-GO**.

Next proposed slice: vetted locator-independent public-only LAN adapter
with explicit TV lifecycle ownership, bounded body/time/rate limits,
hostile on-path replay/drop/reorder tests, and cryptographic TV response
checks on canonical wire before any credential handling.
