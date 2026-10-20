---
status: LAB_ONLY_NO_NETWORK_NO_SOURCE_CAPABILITY
last_reviewed: 2026-10-18
issues: [401]
depends_on: [412, 413, 414, 415]
---

# LC04-S3-D1C — one-shot TV reply guard, hostile-response JVM matrix

## Why the additional state is necessary

The accepted #413 JVM experiment demonstrated how an HPKE-decrypted random
phone-secret allows TV to authenticate an AES-GCM reply. #415 binds AAD to
the *locally held exact signed request* and defines strict canonical typed
wire payloads. Neither property alone makes a **reusable reply key safe**:
a client still needs expiry, cancellation, consumed-state and an incorrect-
response budget, with revocation on monotonic-clock rollback.

This is a pure JVM laboratory component. There is NO production Android
transport, TV service route or provider/source authorization.

## Contract

The phone constructs `Lc04OneShotReplyGuard` from:
- The original, complete, canonical signed HPKE `SIGNED_CHANNEL_PROBE`
  frame stored locally before sending. An untrusted reply is never used
  to choose the original request or to derive its digest.
- A fresh 32-byte response secret and a unique 16-byte phone challenge
  created for the signed HPKE payload.
- A monotonic creation timestamp, local elapsed-clock provider and bounded
  timeout of 1–120 seconds (default 120 seconds).
- A 1–8 verification-failure limit (default three attempts).

`verify(received)` serializes verification of a strict typed
`SEALED_TV_RECEIPT` via #415's `aadForOutstandingRequest`. Only when
the request counter, invitation/pairing IDs, TV and phone fingerprints and
digest of the original signed request agree can AES-256-GCM decrypt with the
**private reply secret**. The decrypted content must equal the fixed receipt
domain plus the 128-bit expected challenge (constant-time comparison).

On successful verification it clears the key, challenge and pending state.
On failed context match, invalid GCM tag or wrong plaintext challenge it
increments a bounded failure counter and eventually locks permanently.
On timeout, elapsed-clock rollback or explicit close it erases the key and
will never accept another response from that instance.

The key is **cloned and never published** by this module. Managed JVM
memory cannot guarantee erasure of all historical secret copies.

## Negative test matrix (GitHub-hosted only)

- Genuine response accepted once; identical replay is refused.
- Rewritten TV/phone public-key fingerprint, request SHA256 or request
  counter is refused before GCM verification.
- Modified ciphertext or intercepted IV without recomputed tag fails GCM.
- Third invalid response exhausts default budget; a later genuine response
  cannot revive the pending request.
- A TV forged with the wrong reply secret or wrong phone challenge fails.
- Deadline boundary, monotonic clock rollback and explicit revocation fail
  closed, including the case where the clock later recovers.
- Constructor rejects wrong key/challenge sizes or a receipt supplied
  as the request.
- Only the existing GitHub-hosted JVM job compiles/tests these cases;
  no physical phone, TV, Android device, self-hosted runner or extra AVD.

## Nonclaims / release blocker

This guard is *not* the phone-side implementation of the entire protocol.
It assumes, but cannot itself establish, that the TV HPKE key came from a
physically scanned QR, the Ed25519 sender is TV-approved, the HPKE decrypt
succeeded and the TV uses a unique key/nonce pair for every reply.
The laboratory fixture simulates possession of the test-only reply key; it
does not inject that key into a real TV over LAN.

The final production state machine still needs a bounded endpoint/transport,
device key pinning, TV-local D-pad consent from #412, Android-qualified
cryptography and adversarial on-path checks (drop, reorder, spoof, replay,
network handoff and lifecycle revocation).

No `SourceOnboarding`, M3U/Xtream, provider credentials or untrusted
plain HTTP pairing capability can be reached from this module.
