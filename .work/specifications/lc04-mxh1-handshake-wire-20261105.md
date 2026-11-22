# LC04-S2D: distinct canonical MXH1 handshake wire, research only

Issue #401; base main cedf66476a926730d1ab0a02e27a630f1cff2e4c
(accepted synthetic HKDF/HPKE/Ed25519 handoff #427).

## Decision and nonnegotiable boundary

Current production native TCP dispatch only understands MXW1: public phone
offer, TV challenge, Ed25519 proof, a signed one-time HPKE probe and sealed
TV receipt. Its receipt key expires after exactly one verification; it
must never be reinterpreted as a reusable data-plane key.

The separate in-process S2C key schedule can derive four HKDF-SHA256 keys
from an independently generated HPKE seed and an authenticated transcript.
However, it does NOT establish a live phone/TV handshake. Adding a new
payload meaning to the existing signed MXW1 probe would violate the
signed transcript, HPKE context and authority separation.

Therefore MXH1 is a NEW, **lab-only**, versioned binary grammar, which
does not change or extend MXW1. The Android phone/TV APK and TCP decoder
continue to reject MXH1.

## Canonical frame (all sizes in bytes)

138-byte header:
- 0..3 magic 0x4d584831 (MXH1)
- 4 version = 1, 5 kind
- 6..21 TV invitation (16), 22..37 TV pairing (16)
- 38..69 SHA-256 TV HPKE QR key fingerprint (32)
- 70..101 SHA-256 TV-approved Ed25519 phone fingerprint (32)
- 102..133 exact SHA-256 **signed seed offer** digest (32)
- 134..135 big-endian request counter, 1..128
- 136..137 big-endian body length (bounded by kind)
- 138..exact end: body; no suffix, padding, JSON or length ambiguity.

Kind 0x11 SIGNED_SEED_OFFER has offer digest **exactly zero** (its own
hash would be circular). Its body is
ciphertextLength u16 (17..2048), HPKE ciphertext bytes, signatureLength
u8 (64 RAW or 69 TINK), signature bytes. Max body 2120 and complete
frame 2258 bytes. The detached signature statement is a domain-separated
literal `MuxTV-LC04-MXH1-SIGNED-SEED-OFFER-v1|`, the *complete canonical
header including zero digest and final body length*, u16 ciphertext
length, ciphertext bytes, then one signature-width byte. Signature
BYTES are omitted to avoid a signing cycle. This signs the version,
direction/operation, identities, exact HPKE bytes and sequence, and
cannot be replayed as an MXW1 signed probe.

Kind 0x12 TV_KEY_CONFIRM: nonzero exact signed-offer digest, same QR
identities and counter, 32-byte newly chosen TV nonce + 32-byte HMAC.
Kind 0x13 PHONE_KEY_CONFIRM: same binding and 32-byte HMAC.

The parser validates only grammar, integer bounds and exact public
identity/digest relationships. In particular a forged confirmation with
any 32-byte tag can pass `structurallyMatchesOffer`: there is **NO**
implicit TV authentication or grant. Signer verification, HPKE domain
separation, freshness, source authority and mutual key-confirmation MAC
verification MUST be owned by an additional TV/phone state machine.

## Evidence / hostile corpus

- Byte-for-byte encode/decode and min/max sizes, including both RAW and
  TINK Ed25519 signatures, counter 1/128, last-byte and suffix rejection.
- Real Tink Ed25519 detached signing and verification; mutated QR IDs,
  counter, signed ciphertext, signature and width cannot silently verify.
- Untrusted random bytes, mutated magic/version/kind, outer and inner
  lengths, forged sig widths, zero/missing offer digest, wrong counter,
  wrong invitation/pairing, wrong TV/phone fingerprints and wrong kind.
- Digest binds the exact **signed** offer, including signature bytes.
- Explicit proof that structurally matching forged HMAC tag passes
  structural codec so that callers never treat it as identity evidence.
- The existing MXW1 parser and installed native TCP reject MXH1, and
  the MXH1 parser rejects legacy MXW1 frames.
- A GitHub-hosted exact-HEAD JVM workflow includes the MXW1, enrollment,
  phone-flow, native TCP and architecture guard suites.

## Not implemented here

No production TV or phone handshake dispatch; no TV-local stateful
key-confirmation, KDF invocation, MXH1 socket connection, responder
nonce authentication, network permissions, lifecycle integration,
SourceOnboarding, HTTP /pair fallback, M3U/Xtream, URLs, user names,
passwords or pairing grants. Successful structural decoding does not
prove knowledge of any key.

## Next independently reviewable step

Build a QR-owner TV-owned one-time MXH1 state machine that proves the
actual TV receiver approved an Ed25519 phone, validates the precise
signed HPKE seed offer using an **independent MXH1 HPKE context**, then
computes Tink/HKDF keys, issues a fresh TV nonce and authenticates
TV and phone confirmations under the two independent confirmation keys.
Bind all stages to the same one-time signed-offer digest, enforce
monotonic expiry and close on replay/late responses. Hostile JVM tests
must be complete before real Android TCP/API26/API36/API37 integration.
Only after cross-device authentication/revocation acceptance can an
owner-scoped adapter to the existing SourceOnboarding be considered.
