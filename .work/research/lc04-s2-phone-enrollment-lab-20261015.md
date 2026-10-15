---
status: LAB_ONLY_NOT_PRODUCT
last_reviewed: 2026-10-15
issues: [397, 401]
depends_on: [403]
owner: local-control
---

# LC04-S2-B — phone proof-of-possession and signed HPKE requests

## Starting observation

LC04-S2-A (#403) demonstrated that *base-mode HPKE encrypts to a TV public key but does not authenticate the sender*. A network attacker who obtains an already public TV key and binding identifiers can construct valid ciphertexts. Adding a **signature only to the enrollment handshake** does not fix later command impersonation. A deterministic negative test preserves this counterexample.

## What this isolated lab implements

All work remains in `:local-control:crypto-lab`, using the **JDK 17 Ed25519 Signature/KeyPairGenerator/KeyFactory** reference APIs and Google Tink HPKE from #403. No custom elliptic curve implementation or TLS replacement.

1. An ephemeral phone Ed25519 keypair; the TV gets an X.509-encoded phone public key in the fixture.
2. TV emits a SecureRandom 256-bit challenge and records the exact requested phone key fingerprint (SHA-256); only a **TV-local** `approveOnTv` can authorize that fingerprint.
3. Phone signs a domain-separated enrollment transcript: version, TV invitation ID, pairing ID, declared TV HPKE key fingerprint, phone Ed25519 key fingerprint and challenge. TTL 120s; max three invalid proofs; replay rejected, revocation and clock rollback fail closed.
4. A verified enrollment returns the **approved public phone key**, not a provider authorization token. A second, distinct fixture signs **each** HPKE command with Ed25519 over the domain-separated binding, direction, operation, counter, TV fingerprint, phone fingerprint and SHA-256 ciphertext digest. The receiver checks the approved sender key before attempting HPKE decryption.
5. The existing Tink HPKE context independently checks operation and counter; the HPKE receiver enforces replay monotonicity. Bounds apply before signature verification. The signed-message verifier additionally expires after five minutes by default, fails closed on clock rollback and supports TV-initiated revocation that clears the approved public key.

## Security claims and specific nonclaims

| Property | Laboratory observation | Product state |
|---|---|---|
| Reject a public-key-only attacker who cannot sign as the **approved phone** | Signature verification rejects forged signed envelope | LAB only |
| Reject signer substitution, pairing/TV-key context substitution, altered ciphertext and operation | Negative host tests | LAB only |
| Reject replay/old counters, revoked/expired enrollment | Negative host tests | LAB only |
| Verify real TV identity based on QR rather than a free string | NOT IMPLEMENTED; `declaredTvHpkeFingerprint` is supplied by test fixture | OPEN |
| Verify **phone owner's intended TV** and TV owner's intended phone via trusted UI | NOT IMPLEMENTED | OPEN |
| Authenticated secure page/JS served to actual phone browser | NOT IMPLEMENTED; plaintext `/pair.js` is replaceable by LAN MITM | OPEN |
| Protect **responses** or build a full duplex transcript | NOT IMPLEMENTED | OPEN |
| Persist approved sender identity, prevent relay or bind to real LC01 consent | NOT IMPLEMENTED | OPEN |
| Tested Ed25519 provider on Android API26 / Chrome / Firefox / Safari | NOT IMPLEMENTED; uses JDK 17 host provider | OPEN |
| Production route to `SourceOnboarding` | Intentionally absent | BLOCKED |

**Crucial:** The sender-authenticated ciphertext experiment is NOT a complete authenticated channel. An active MITM replacing plaintext browser JS could generate its own phone key and trick the TV user into approving it unless the UI confirms a trusted, independent fingerprint. Even after enrollment, a compromised browser can sign commands. An unsigned plaintext HTTP acknowledgement would allow response substitution. The current LC01 bearer (exchanged via LAN HTTP) must not authorize provider API access.

Because the public TV key is not serialized into a stable wire QR format and the test's `declaredTvHpkeFingerprint` is caller-supplied, this lab does **not** prove that the signature authenticates the actual HPKE receiver key. That requires a real, canonical QR-key representation and pinning test.

## Negative test/evidence matrix

- Wrong TV key, wrong invitation/pairing, wrong phone key or signature, altered challenge, replay and expiry -> reject.
- Phone possessing an old LC01 pairing bearer but **not** the TV-approved Ed25519 private key -> reject.
- A malicious sender with the **public** TV HPKE key but without the approved Ed25519 key -> reject when signed-envelope verification is applied.
- Enrollment signature without per-message signature -> explicit attacker-counterexample, NOT ACCEPTABLE.
- Modified ciphertext/operation/sequence and cross-binding envelope -> reject before/at HPKE.
- Sender signatures authenticate requests only, not responses, and do not prove browser page integrity.

## Next slice (LC04-S2-C) acceptance

1. Choose frontend trust root and verify supported phone browsers, including Android Chrome/Firefox and mobile Safari with Local Network Access behavior; offline capability explicitly evaluated.
2. Generate ephemeral TV HPKE+identity keys, serialize canonical fingerprint/raw public key into a **real QR wire format** with exact parser and size limits, not an unchecked descriptive string.
3. Implement a reviewed authenticated bidirectional session protocol where an approved phone key is cryptographically bound to an actual pinned TV key, every request and response have integrity/confidentiality, and session revocation/expiry are enforced at transport boundary. Do not claim that Ed25519 + HPKE lab automatically provides such a protocol.
4. Test active MITM substituting QR and JavaScript, cross-origins, poisoned DNS/private interfaces, copied proof, lost/revoked sessions and replay. No silent HTTP or self-signed TLS downgrade.
5. Only then allow protected transport to create the guarded `ProtectedSourcePrincipal` and invoke the existing `SourceOnboarding` stages from #402.

This is research evidence, **not** a prerequisite for release-signing or mandatory physical-TV testing. No parental, PIN or age restriction features.
