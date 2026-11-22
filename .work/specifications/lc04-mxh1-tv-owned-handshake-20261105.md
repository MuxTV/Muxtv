# LC04-S2E — TV-owned single-use MXH1 handshake, JVM research gate

Refs #401; accepted #425 native phone proof, #426 synthetic AEAD,
#427 synthetic transcript-bound KDF, #428 canonical isolated MXH1 wire.

## Repository-derived gap

The deployed native TCP accepts only MXW1 frame kinds; there is no
production application data plane. TV's existing QR owner can authenticate
a TV-local-approved Ed25519 identity and a one-shot receipt, but its
48-byte signed CHANNEL_PROBE is NOT a reusable session key.
#428 defined a separately versioned MXH1 grammar without authentication
or owner state. Structurally correct confirmations can contain forged MACs.

## Implemented, JVM-only

`:local-control:mxh1-session-lab` is a separate module and has no Android,
network, HTTP, source, credential or provider dependencies.

The research TV owner generates and retains its **own** ephemeral X25519
HPKE keyset, a fresh MXLC04:1 public QR and an in-memory
`TvPhoneEnrollment` gate bound to that exact QR, monotonic TTL and
TV-local `approveOnTv` proof. Only a real `VerifiedPhoneIdentity`
returned by this gate can install an Ed25519 verifier. A claimed
fingerprint, incoming IP address or attacker-controlled QR is never
sufficient to activate it.

TV acceptance sequence:
1. Require unexpired, unrecalled QR and exactly `NEW` state.
2. Decode strict `MXH1 SIGNED_SEED_OFFER`; compare all four
   public identity fields with QR and TV-approved phone.
3. Verify the canonical Ed25519 signed statement against the actual
   TV-approved public key **before attempting HPKE decryption**.
4. Decrypt using TV's own QR-private HPKE receiver and the separately
   domain-separated `MXH1-HPKE-SEED-HANDOFF-v1` info. A 32-byte fresh
   dedicated seed is required; no reuse of MXW1 one-shot receipt material.
5. Create fresh random TV nonce and derive four domain-separated
   HKDF-SHA256 keys (phone→TV, TV→phone, confirm-TV, confirm-phone),
   with exact signed offer digest, QR identities, approved phone and
   TV nonce in the transcript.
6. Respond with `TV_KEY_CONFIRM` and a real 32-byte key-confirmation
   HMAC; transition to WAIT_PHONE. The entire signed offer and its digest
   are held in memory for one use only.
7. Accept only an exact `PHONE_KEY_CONFIRM` bound to that signed offer,
   counter, QR and phone, then compare the **real** HMAC in constant
   time and transition to synthetic VERIFIED. Replay, invalid state or
   expiry revokes the entire owner.

The synthetic phone independently derives the same keys from its own
HPKE seed after verifying TV's real HMAC; only then emits the phone
HMAC. Phone signing reuses the ephemeral existing
`PhoneSigningIdentity`, with a new explicitly bounded MXH1-specific
signer method, never arbitrary bytes, old MXW1 probe or provider data.

The only exposed success is `hasMutualSyntheticProof()` /
`hasAuthenticatedTvSyntheticProof()` (ephemeral boolean, **no source
authority**). Keys remain owned in process and are discarded on
`close()`; the JVM/Tink heap does not guarantee total zeroization.

## Tests and negative security guarantees

Hosted JVM tests use actual Tink HPKE and Ed25519, real
`TvPhoneEnrollment` TV consent gate and synthetic HMAC verification.
Cases: full mutual handshake, no TV approval, wrong phone,
forged TV/phone MAC even when structurally correct, signed offer
identity/header/ciphertext/signature tampering, wrong HPKE
info/domain, wrong counter, exact replay before/after success,
duplicate confirmations, timeout and clock rollback, explicit
revocation, malformed frames and redacted diagnostics.

CI must check the exact GitHub PR HEAD, run real hostile tests,
MXH1/MXW1 regression tests and separate build-logic isolation
guards. The new module is intentionally absent from app:phone,
app:tv, native TCP, LAN HTTP server and source onboarding.

## Explicit limitations / STOP gates

This is a **research owner**, not installed Android pairing. Its
`tvConsentGate()` is callable from JVM; the test calls
`approveOnTv()` explicitly, but a trusted D-pad-origin guarantee
is only provided by the separate Android UI integration.
The lab can never provision an IPTV source or mint a pairing bearer.

Before permitting credentials, additional slices MUST:
- Bind this owner to the existing Android TV QR-owner lifecycle, D-pad
  button origin and private network listener. Do not create a second
  independent production QR owner/key store or bypass the reviewed
  Android 17 local-network permission.
- Implement a distinct bounded MXH1 TCP path with lifecycle stop
  and network route cancellation in BOTH Android processes.
- Run real cross-process API26/API36 and API37 permission/device
  tests with hostile MITM, duplicates, delay, missing confirm,
  network handover, process death, rollback and source-leak checks.
- Review session-owned AEAD data framing and authorize only
  in-memory `SourceOnboarding.prepare/activate/cancel` handles
  after both confirmations and explicit UI consent.

No M3U/Xtream credentials, source URLs, source handles, playback
commands, arbitrary plaintext API, persistent grant or HTTP fallback.
