---
status: LAB_ONLY_NOT_PRODUCT
last_reviewed: 2026-10-15
issues: [397, 401]
depends_on: [403, 404]
module: :local-control:crypto-lab
---

# LC04-S2-C1 — canonical TV HPKE key in a QR scan payload

## Why this exists

LC04-S2-A (#403) encrypted to a real Tink HPKE public key. LC04-S2-B (#404) signed every HPKE command, but its `declaredTvHpkeFingerprint` was passed as an arbitrary 64-character string. Correctly validating a signature on such a string **does not prove that the actual receiver private key corresponds to the key shown on the TV**.

This slice removes that *laboratory* gap by serializing the **actual ephemeral receiver public keyset** into a bounded QR record and refusing to create the TV-side command verifier unless the current receiver public keyset matches the scanned one byte-for-byte.

## Canonical LAB wire contract

```
MXLC04:1:<32 lowercase hex TV invitation ID>:<32 lowercase hex approved pairing ID>:<unpadded base64url Tink PUBLIC proto keyset>
```

- Prefix/version and exact four colon-delimited fields; no HTTP URL, query string, browser redirect, provider token, or JavaScript origin.
- Binary public-keyset serialization uses `TinkProtoKeysetFormat.serializeKeysetWithoutSecret`, and parsing uses `parseKeysetWithoutSecret`. Private/secret material is never requested for QR export. <https://github.com/tink-crypto/tink/blob/master/docs/JAVA-HOWTO.md>
- At most 900 QR characters and 512 serialized public keyset bytes. Base64url must be unpadded, with canonical re-encoding and no whitespace.
- The parsed protobuf must contain **exactly one enabled primary public HPKE key**, X25519/HKDF-SHA256/AES-256-GCM, HPKE version 0, raw 32-byte X25519 public key; no other suite/key type/secondary key is accepted. Tink's HybridEncrypt primitive validates the parsed public key.
- Identity/fingerprint = SHA-256 over the **entire canonical public Tink protobuf keyset**, not a caller-provided test constant. The key ID and suite are included in that hash; equal raw X25519 keys with different Tink keyset IDs are distinct lab keyset identities.
- A phone sender obtains the public encryption key and fingerprint **from the same parsed record**.
- The TV verifier recomputes the *actual receiver's* public-keyset serialization and compares it with the scanned bytes before accepting the TV-approved Ed25519 phone enrollment.
- Enrollment proof includes the scanned fingerprint and canonical invitation/pair ID; per-command Ed25519 signature includes the same transcript fields. Incorrect pairing, swapped TV, replaced keyset or changed suite fails closed. No production use is authorized by the presence of a parseable record.

## What this proves and what it does not

| Question | Lab conclusion |
|---|---|
| Can the receiver's real HPKE public key be serialized without exporting its private key? | Verified through Tink public-only serialization |
| Can a phone-side parser reject malformed QR, padding/extra fields, wrong HPKE suite/type, public/private confusion? | Host negative tests |
| Can a different TV HPKE receiver silently use the scanned identity? | Rejected by public-keyset byte comparison |
| Can a phone with the pinned QR key, TV-approved identity, and per-message signature exchange a command? | Tested in JVM fixture |
| Does a malicious QR held up in front of the phone prove it came from the intended television? | **NO** — human/physical UI and key-origin authenticity remain external |
| Does this work from a real Android/iOS browser or Android API26? | **UNKNOWN** — this is JVM-only Tink protobuf, not a browser WebCrypto/HPKE wire specification |
| Is the HTTP `/pair.js` page authenticated or protected? | **NO** — unchanged and vulnerable to active same-LAN JavaScript replacement |
| Are encrypted/authenticated TV responses, transcript acknowledgments, secure origin and full channel implemented? | **NO** |
| Is `:local-control:onboarding` now callable by a phone? | **NO** — no Ktor route/dependency or production adapter wiring |

The Tink protobuf keyset is chosen for a *reproducible JVM laboratory*. It is not a product-proposed mobile-browser interoperability format. Before public use, evaluate a documented browser-readable representation for the raw X25519 key plus suite, fixed IDs, a pinned and authenticated secure frontend, and actual supported-browser key/crypto capabilities.

## Negative test matrix

- Alternative actual TV receiver key != QR key: reject.
- QR substituted from another TV: different fingerprint, cannot reuse the original approved sender binding.
- Wrong format/version, extra fields, whitespace, padded base64url, oversized or malformed keyset: reject.
- Wrong key material type, non-HPKE public key or downgraded suite: reject.
- All canonical fields source from a parsed scan and not from an independent mutable string.
- Existing signed-command/HPKE tests continue to deny replay, context mismatch, wrong signer, expired/revoked sessions.

## Next LC04-S2-C2

1. Verify secure, immutable phone frontend delivery and realistic Chrome/Firefox/Samsung Internet/iOS browser behavior for a supported origin/permission model. Native companion is an explicit fallback if trusted cross-browser local-first cannot be achieved.
2. Establish an authenticated **bidirectional** transport with verification of TV identity and phone identity, every response, nonce/sequence/counter, expiry/revoke. A QR key and sender signature are insufficient by themselves.
3. Enforce active LAN MITM tests (substituted JS, swapped QR, reflected/replayed request, replaced response, cross-origin), TV API26/36 crypto compatibility and source permission gates.
4. Only when these hold can the protected runtime use `:local-control:onboarding` to send provider-specific source data. The plaintext QR page remains pairing-only.

Production restrictions (no parental control system, no requirement for physical-device testing as a release blocker) remain unchanged.
