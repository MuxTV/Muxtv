---
status: RESEARCH_ONLY_NO_ANDROID_TRANSPORT_NO_SOURCE_CAPABILITY
last_reviewed: 2026-10-18
issues: [401]
depends_on: [412, 413, 414, 415, 416]
---

# LC04-S3-D2 — hostile serialized-wire signed-HPKE / authenticated-TV relay

## Why

Earlier labs separately demonstrated phone enrollment, signed HPKE, authenticated
TV acknowledgements, and canonical binary framing. **No prior test exercised
them through an attacker-controlled serialized byte relay.** This is a
critical seam: header/body confusion, counterfeit identifiers, signature
mutation, replay and cross-session reflection may evade isolated crypto tests.

This work adds **CI-only** tests under `:local-control:crypto-lab` with a
test-only dependency on the accepted `:local-control:wire-lab`. The product
TV app and LAN server gain **no dependency** on this research module.

## Programmatic participants

- TV owns a new Tink X25519/HKDF-SHA256/AES-256-GCM private HPKE receiver.
- Phone creates an Ed25519 keypair. A **simulated trusted TV-local**
  action displays/approves the candidate fingerprint (the real product
  consent screen is separately in #412).
- TV checks proof of possession, then pins the approved Ed25519 key.
- Phone sends a `CHANNEL_PROBE` with a fresh 32-byte secret and 16-byte
  challenge inside a signed HPKE envelope (accepted #413 lab code).
- Sender serializes counter, ciphertext, signature and fixed session
  identifiers into the accepted #414/#415 canonical binary envelope.
- A hostile relay receives **ONLY serialized bytes** and can drop, reorder,
  replay, truncate or bit-flip either direction; it never learns the
  HPKE-decrypted secret or phone signing private key.
- TV validates the public frame context **against its own trusted pairing
  and key fingerprints** before reconstructing the signed envelope, checking
  Ed25519, decrypting HPKE and sealing the reply under AES-256-GCM.
- Phone verifies the same trusted context, canonical reply payload and
  the original #413 signed-request digest/AES-GCM tag/challenge; accepted
  reply and replay detection come from #413.

## Verified attack cases, only with GitHub Actions CI

1. Honest end-to-end serialized round trip and reject replay of reply and
   the same signed request.
2. Mutation of invitation marker, pairing, TV key fingerprint, phone key
   fingerprint, signed counter, HPKE bytes or signature before delivery:
   TV cannot construct an authenticated receipt.
3. Interception/bit-flip of receipt context, original request digest,
   counter, IV or GCM tag: phone rejects, but still accepts the genuine
   reply if its bounded failure budget remains.
4. Attacker with TV's **public HPKE key** but an unapproved phone Ed25519
   identity cannot impersonate an approved sender or steal the proof.
5. Drop/reorder: TV accepts later counter but rejects stale earlier
   request; phone cannot mistakenly accept the later TV receipt as an
   acknowledgement for the earlier request.
6. TV revocation blocks all future signed probe receipts.
7. Truncated/overlong incoming frame or receipt is rejected without any
   fallback to a plaintext browser path.

## Canonical transcript integration — additional JDK crypto regression

`LabCanonicalTypedCryptoRoundTripTest` is a separate second test seam. It
uses the **actual #415 canonical binary Ed25519 signing statement**, rather
than #413's older lab envelope statement. The phone signs this precise
binary statement; the simulated TV reconstructs the statement from bytes
and verifies the signature with the TV-approved phone public key BEFORE
Tink HPKE decryption. After decrypting the challenge/key, TV constructs a
real AES-256-GCM receipt with #415's **reverse-direction receipt AAD** and
the full original signed-probe SHA256 request ID. The phone uses
`aadForOutstandingRequest` against the original locally held signed
request before AES-GCM tag and challenge verification.

It exercises tampering of source IDs, sender/counter, ciphertext and signed
bytes, as well as changed reply digest, IV and authentication tag. This
closes the **laboratory transcript-format mismatch** for the independently
tested canonical path without changing/claiming production JDK or Android
enrollment, wire routing or network transport.

## Explicit limitation — do not overstate protocol security

#413's JDK17 `LabSignedHpkeEnvelope` and `LabAuthenticatedTvReply` use
**different signed transcript and AES-GCM AAD representations from #415's
new `Lc04TypedTranscript`**. This suite tests that the accepted lab's
*real cryptographic verification* survives serialization into #414/#415
*typed framing*. It **does not** yet prove the final production on-wire
cryptographic transcript, nor does it prove Android Tink interoperation.
Integration of #415 signing/AAD into Android API26/API36 cryptographic
endpoints remains a separate security gate.

All packets are delivered through copied in-memory byte arrays on a
GitHub-hosted JVM job; this does **not** simulate network sockets,
Android lifecycle, hostile DNS, port discovery, NAT or network handoff.

No socket, Android INTERNET permission, TV LAN listener, browser-origin
assumption, `SourceOnboarding`, M3U/Xtream URL, provider credential,
source command or bearer token is added. Failures keep source transfer
blocked.

## Next engineering acceptance gate

Freeze ONE canonical signed/AEAD transcript shared across the simulator
and native Android apps; add TV-local consent integrated with real public
phone key delivery, authenticated directional reply state, negative
MITM/restart/replay tests and Android API26/36 provider qualification in
GitHub-hosted emulators. Only then may protected source onboarding be
connected.
