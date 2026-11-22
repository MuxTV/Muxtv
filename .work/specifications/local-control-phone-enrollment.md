---
status: proposed_security_boundary
last_reviewed: 2026-10-18
issues: [401, 397]
depends_on: [410, 411]
---

# LC04-S3-C — TV-confirmed native phone Ed25519 enrollment

## Decision

Keep the **accepted** native phone scanner and TV's canonical MXLC04:1 HPKE receiver.
Introduce a product-compatible, Android API26-capable proof-of-possession module, rather
than importing the JVM/JCA Ed25519 crypto-lab or treating the legacy HTTP pairing
bearer as authority. Use Tink ED25519 and the **actual** scanned TV public QR fields.
No custom curve code, encryption primitive or plaintext provider transfer.

This is a **non-network** production candidate and is deliberately not a functional
pairing experience yet. Neither :app:phone nor the old LAN /pair site can remotely
enroll, call approveOnTv(), authenticate a protected session or reach onboarding.

## Ownership and transcript

- :local-control:enrollment implements bounded, strict canonical **PUBLIC Tink
  Ed25519 keyset** validation and an ephemeral phone signer. Keyset: precisely
  one enabled Ed25519 v0 public key of 32 bytes, ≤256 serialized bytes, canonical
  Tink representation, public material only. The phone's SHA-256 fingerprint
  covers the complete canonical public keyset, not a supplied arbitrary label.
- :local-control:tv-qr exclusively owns the receiver-generated MXLC04:1
  serialized public key and lazily constructs at most one enrollment gate;
  closing/expiring its actual receiver closes and invalidates the gate.
- The proposed phone key remains pending with a 32-byte random challenge and
  monotonically measured lifetime ≤120 seconds. Another key cannot replace an
  outstanding key, even if an attacker tries to race approval.
- A trusted TV-local UI **must** show and explicitly confirm the pending phone
  key fingerprint. There is intentionally no HTTP route to invoke consent.
- The native phone scanner now creates a **fresh in-memory Tink Ed25519 signing identity**
  after a canonical TV QR scan and shows the phone public-key SHA-256 fingerprint.
  It disposes the private-key reference on scan restart or Activity.onStop.
  The phone first parses the physical TV QR through the already merged #410
  runtime parser and refuses challenge mismatch to that QR or its own identity.

Canonical signed transcript (ASCII, fixed-width hex values and separators):

```text
MuxTV-LC04-PHONE-ENROLL-v1|<invitation32>|<pairing32>|<tv-key-sha256-64>|<phone-key-sha256-64>|<challenge32-byte-hex>
```

The TV verifies the proof through **Tink PublicKeyVerify** with the approved
public-only key, after TV-local fingerprint consent. Tink signatures can carry a
TINK output prefix and are bounded to 64–69 bytes. Invalid attempts are bounded; exhaustion locks the gate until a fresh TV QR,
proofs are single-use, expiration and elapsed-clock rollback fail closed, and
explicit receiver closure revokes pending state. The successful result is only a
verified phone identity; it is **not** SOURCE_CREATE or a bearer capability.

Phone signing keys are ephemeral and are never persisted by this module or the phone Activity. Clearing
managed key references does not guarantee in-heap private key zeroization.

## Attack/negative test matrix

| Scenario | Expected behavior |
|---|---|
| TV owner has not approved fingerprint | TV_CONSENT_REQUIRED |
| Malicious phone using a different signing key | INVALID_PROOF |
| Reused proof after successful acceptance | REPLAYED |
| Wrong physical TV public QR, invitation or pairing | Phone refuses to sign |
| A second phone tries to replace pending candidate | No replacement |
| Invalid/oversized/secret keyset | Reject before enrollment |
| Challenge lifetime expired or elapsed clock rollback | Reject and revoke pending |
| Original TV QR receiver revoked/expired | Enrollment gate closed |
| D-pad reject on TV consent surface | Revoke entire TV QR key session |
| Candidate on TV without explicit approval | Deny; D-pad default focus is Reject |
| Wrong signature, repeated failures | Rejected; pending key invalidated at limit |
| Existing LC03 HTTP session without Ed25519 proof | No access to this component |

Device qualification: run the standalone Tink proof on existing GitHub-hosted
Android TV API26/API36 AVDs, as well as host JVM tests and both app builds.
Do not introduce a third AVD, self-hosted runner or release signing.

## Nonclaims and next security gate

- The **actual phone** has not sent any enrollment bytes to TV over LAN.
- A TV-local consent screen and D-pad handlers now exist, with default focus
  on Reject. The pending public identity can be injected only by the
  independently reviewed TV-owned gate; there is **no LAN intake route** yet,
  so this UI is exercised via synthetic candidates in emulator tests.
- No phone network discovery or protected challenge exchange in this slice.
- A verified proof alone does NOT authenticate TV replies, create a shared
  secure channel or authorize `SourceOnboarding`.
- An attacker controlling the phone process/UI still controls its signing key.
  Independent TV and phone UI confirmation remains essential.
- Transport engineering must use a reviewed protocol framing, directional
  key separation, bounded authenticated encrypted **responses**, monotonic
  counters, expiry/revocation and active MITM/replay test harness.
- No secret M3U/Xtream URL, username, token or password may be transmitted
  over the existing untrusted `http://private-IP/pair` routes.

Next: native phone-to-TV protected enrollment transport to deliver a real
pending identity to the TV-local consent UI, authenticated response, and a complete cross-device
MITM/replay/revocation matrix. Only after these pass can
`:local-control:onboarding` mint a session-scoped protected principal.
