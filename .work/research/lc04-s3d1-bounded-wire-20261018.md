---
status: LAB_ONLY_NO_NETWORK_OR_CREDENTIALS
last_reviewed: 2026-10-18
issues: [401]
depends_on: [410, 411, 412, 413]
---

# LC04-S3-D1A — bounded canonical wire parsing research

## Scope

Introduce a pure Kotlin/JVM, fully deterministic binary parser for **untrusted
framing only**, independent of real TV/phone traffic. The QR key pinning,
TV-local Ed25519 key enrollment, signed HPKE command validation and AEAD
response validation remain independently mandatory. A frame's readability is
NOT evidence of authenticity or authorization.

The protocol prefix `MXW1` is deliberately **lab-only** and must not become
a production public protocol version without an explicit freeze and review.

## Wire frame

```text
byte[0..3]    MXW1
byte[4]       version 1
byte[5]       message kind 1..5
byte[6..21]   invitation marker 16 bytes, NOT a bearer
byte[22..37]  pairing marker 16 bytes, NOT a bearer
byte[38..69]  TV HPKE SHA-256 fingerprint (32 raw bytes)
byte[70..101] phone Ed25519 keyset SHA-256 fingerprint (32 raw bytes)
byte[102..103] payload size unsigned big-endian 16-bit
byte[104..]   exactly that number of bytes
```

Five allowed frame kinds: PHONE_PUBLIC_KEY (1..256 public-keyset bytes),
TV_CHALLENGE (exactly 32 bytes), PHONE_PROOF (64..69 signature bytes),
SIGNED_CHANNEL_PROBE (40..4096 opaque bytes), SEALED_TV_RECEIPT
(29..4096 opaque bytes). No SOURCE operations, JSON, URLs or provider secrets.

Maximum complete frame: **4200 bytes**. Every kind, length, prefix and
end-of-input is checked before constructing copy-owned objects. Invalid
version, unknown operation, excess bytes, truncation and oversized frames
are rejected. Valid binary headers with attacker-modified fingerprints
CAN parse — authentication belongs to an independently verified signature/
HPKE/AAD transcript, not this codec.

## Verification

Canonical roundtrip both boundary sizes of all five kinds; every truncated
prefix of an opaque probe; extra byte and large append; changed magic/version/
kind; incorrect endian/length; mutated context; malformed public context
size, oversized body, ownership isolation and random hostile input. Tests use
only GitHub-hosted JDK, no Android hardware or new emulator images.

## Open security dependencies

1. **#412**: product Android TV-local consent and Ed25519 proof gate still
   requires acceptance of exact-head emulator UI tests.
2. **#413**: accepted lab-only reverse authenticated TV receipt does not grant
   a production transport or source capability.
3. A production transport requires authenticated endpoint selection from
   the physically scanned HPKE key rather than trust in a LAN IP/URL.
4. Define type-specific strict payload codecs and an authenticated transcript
   *before* accepting any message from another process. Do not deserialize
   arbitrary Tink/private keysets or echo back free-form attacker data.
5. End-to-end hostile LAN MITM/replay/revoke and per-direction key separation
   have not been production-qualified.

This slice must never add `INTERNET` to the phone application, a TV listener,
a new legacy HTTP /pair route, or a path to `SourceOnboarding`.
