---
status: LAB_ONLY_NO_NETWORK_NO_SOURCE_CAPABILITY
last_reviewed: 2026-10-18
issues: [401]
depends_on: [412, 413, 414]
---

# LC04-S3-D1B — typed probe/reply wire payloads and cryptographic transcript binding

## Decision and explicit scope

The accepted #413 signed HPKE/AEAD fixture demonstrated that the TV's response
must be authenticated. The draft #414 bounded `MXW1` frame validates only
outer byte lengths and message classes; the app cannot accept opaque bytes as
a completed protocol. In this stacked work, the 4th and 5th message kinds
receive *typed*, canonical internal structures and cryptographic domain
separation. Implementation is a **laboratory** and not a network listener,
Android device enrollment, source authorization or production protocol freeze.

No new `INTERNET` permission, source operation, provider URL, username, token,
password or call to `SourceOnboarding`. Parsed framing data is untrusted and
has no source capability even when syntactically well-formed.

## Probe framing (PHONE -> TV, still opaque HPKE bytes)

The whole `SIGNED_CHANNEL_PROBE` frame uses the #414 fixed 104-byte
outer header: 16-byte invitation ID, 16-byte pairing marker, 32-byte
TV HPKE public-key SHA256, 32-byte phone public-keyset SHA256 and
strict u16 payload length.

Its canonical payload contains:

```text
counter          u16 BE, range 1..128
hpke_cipher_len  u16 BE, range 17..4022
hpke_cipher      byte[hpke_cipher_len]
signature_len    u8, precisely 64 (RAW) or 69 (Tink-prefixed)
signature        byte[signature_len]
```

The Ed25519 signing input is the domain-separated raw binary concat of:
`MuxTV-LC04-PHONE-TO-TV-PROBE-v1\0`, protocol magic/version, fixed
message kind `SIGNED_CHANNEL_PROBE`, all four raw context fields, counter,
HPKE ciphertext length and *complete* ciphertext. The signature cannot sign
itself; it is intentionally omitted from the signed input. To sign, the
caller can construct the frame with a bounded dummy signature of the intended
variant length, compute the input, sign it, and replace the placeholder.
Signatures and HPKE ciphertext must be independently **verified** before
any state transition. This codec does not verify Tink ED25519 keysets.

## TV receipt framing (TV -> PHONE, still opaque AEAD ciphertext)

```text
counter          u16 BE, range 1..128
request_digest   byte[32], SHA256 of the *original signed* probe
iv               byte[12], AES-GCM nonce
cipher_len       u16 BE, range 17..4048
ciphertext       byte[cipher_len], ciphertext INCLUDING GCM tag
```

The AES-GCM AAD input uses a distinct reverse-direction
`MuxTV-LC04-TV-TO-PHONE-RECEIPT-v1\0` domain plus protocol
magic/version, kind, all 4 context fields, counter, and request digest.
The ciphertext is not in AAD because the GCM tag already authenticates it.
The concrete `aadForOutstandingRequest(originalProbe, receipt)` gate now
compares request_digest in constant time with SHA-256 of the caller's **exact
canonical original signed probe**, including the Ed25519 signature, prefixed
with a separate request-ID domain. It also compares both context IDs, both
key fingerprints and the counter before releasing AAD for GCM verification.
Changing the original probe signature, HPKE bytes or any context marker
invalidates the gate. An attacker-supplied digest is NEVER an authorization
proof; the phone must keep its own outstanding request bytes.

The AES-GCM key and nonce derivation, unique-key use, IV uniqueness,
expiry and replay guards are external requirements of the accepted #413
lab and must be independently tested on Android before transport rollout.
This payload codec does not generate or retain keys.

## Acceptance and negative testing

- Canonical encode/decode round trips across two allowed signature variants,
  extreme HPKE and AEAD payload lengths and full outer frame.
- Reject every shortened payload, unknown counter, altered length, invalid
  signature variant, extra bytes, and wrong outer message type.
- Copy ownership protects caller from later mutation; debug strings redact
  payloads.
- Real JVM Ed25519 check accepts the exact context, but rejects a mutated
  TV fingerprint, phone fingerprint, invitation, pairing, counter, ciphertext
  and signature bytes.
- Real JVM AES-256-GCM check accepts one original AAD context, but fails for
  any modified context, request digest, counter, nonce or ciphertext.
- Receipt correlation rejects a forged reply referencing the wrong original
  signed request even when the attacker controls all external frame fields.
- Hostile fuzz corpus (2500 random opaque inputs) never crashes parsing
  or accepts a noncanonical encoding.
- Only exact-HEAD GitHub-hosted JVM CI. No hardware requirement.

## Blocking next actions

Do not treat this laboratory transcript as a deployed protocol. An independent
review must freeze exact key variants, challenge/offer proof encoding,
correlation of the response digest to the **original signed request**, signed
version negotiation, expiration and directional replay state. #412 TV-local
consent still needs exact-head API26/API36 UI regression acceptance.

After these security gates, the actual Android native phone and TV modules
can exchange *public enrollment and channel-probe* messages over a bounded,
untrusted LAN endpoint while cryptographically pinning the TV QR. Only after
the full hostile on-path MITM/replay/revocation matrix may a production
principal reach protected `SourceOnboarding`.
