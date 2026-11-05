# LC04-S2F — original TV QR owner in-process synthetic MXH1

Refs #401, accepted #429 (23/23 exact-HEAD CI). The accepted S2E lab
`Lc04MxH1TvOwnerLab` creates a separate ephemeral TV QR/HPKE keyset and
approval gate; it is NOT connected to the existing Android TV QR owner.
That duplicate would be unsafe to instantiate inside the installed app.

## Actual repository owner and lifecycle

`TvPairingController` already owns one volatile
`TvHpkeQrSession` per user-visible screen. The latter owns the exact
public MXLC04:1 QR, ephemeral Tink X25519 private receiver,
`TvPhoneEnrollment` gate and approved phone Ed25519 verifier. The
controller calls `revokePublicKeyQrPreview()` on Activity stop, navigation,
TV QR reset and LAN/default-route invalidation; this closes the same
receiver and native proof listener. Its LC03 HTTP listener cannot coexist
with the native QR mode.

Creating a second production QR owner would bypass these lifecycle
guarantees. This PR adds a **synthetic, in-process MXH1 state machine to
the existing `TvHpkeQrSession`** and never instantiates the separate
research TV-owner class from the Android application.

## Implemented in original owner

The public TV QR, HPKE private receiver, TV-local gate and approved
Ed25519 verifier are the existing fields; no new credentials, key store,
session bearer, network endpoint or second TV keyset are created.

`acceptSyntheticMxH1SeedOffer`:
- Validates the original QR's monotonic 120-second expiry, NEW state and
  the existing gate's `verifiedFingerprintForTv` against the approved
  verifier fingerprint.
- Parses a canonical separately versioned MXH1 SIGNED_SEED_OFFER.
- Checks invitation, pairing, TV HPKE fingerprint and actually approved
  Ed25519 phone fingerprint against the one currently displayed QR.
- Verifies the exact signed statement using the existing enrolled
  Ed25519 public-key verifier **before** invoking Tink HPKE decrypt.
- Uses the distinct MXH1-only HPKE domain, never the older MXW1 receipt
  context; accepts precisely one independent 32-byte seed.
- Derives direction-separated synthetic HKDF keys bound to the exact
  signed offer digest and a fresh 32-byte TV nonce; sends real
  TV_KEY_CONFIRM HMAC and holds only one pending offer in memory.

`acceptSyntheticMxH1PhoneConfirmation`:
- Requires WAIT_PHONE; checks canonical counter, exact signed-offer
  digest and all TV/phone fingerprints.
- Verifies the **real** PHONE_KEY_CONFIRM HMAC, not just public framing.
- On success immediately closes and discards the derived keys, retains
  only a diagnostic, session-lifetime VERIFIED bit. This is explicitly
  NOT an encrypted source data transport or pairing permission.
- On invalid input, replay, timeout, cancellation or clock rollback,
  closes the entire original QR owner, revoking MXW1 and MXH1 together.

`acceptProtectedProbe` (legacy MXW1) is still supported while NEW,
but cannot run once the MXH1 handshake is pending or has completed.
The original owner's `close()` now revokes pending synthetic MXH1
state/key material as well as its existing enrollment/HPKE private key.

## Evidence and acceptance

Real original-owner JVM tests with the existing TV gate, actual phone
Tink Ed25519 signer and MXH1 HPKE sender cover full mutual confirmation,
one QR/gate identity, pre-consent rejection, forged HMAC despite valid
public digest, repeated signed offer, duplicated confirmation, changed
header/ciphertext/signature, wrong HPKE domain, old MXW1 coexistence,
expiry/clock rollback and controller-style close. Exact-HEAD GitHub
hosted CI runs original TV-QR tests plus S2E owner lab, enrollment,
wire-protocol and KDF regressions and structural isolation contract.

## Explicit STOP gates

This PR does NOT connect MXH1 to the running Android TCP dispatcher,
phone UI, D-pad actions beyond the existing gate, source credentials,
`SourceOnboarding`, HTTP /pair or any arbitrarily encrypted data. The
synthetic diagnostic status does not grant application source rights;
derived data-plane keys are discarded on proof.

Before data-plane production:
1. Review whether to unify the S2E lab and TV QR owner implementation
   further to avoid duplicated security logic; ensure both fail closed.
2. Add a distinct, bounded, cancellation-safe MXH1 native TCP exchange
   on the already opted-in TV QR with exact route/network ownership.
3. Add Android phone-side lifecycle-bound MXH1 flow, with no auto-start,
   no VPN/cellular route, and no plaintext HTTP fallback.
4. Verify API26/API36 and permission-qualified API37 cross-process
   hostile tests, replay, MITM, network handover and close on process death.
5. Only then add an authenticated data-bearing AEAD channel and an
   in-memory adapter to existing SourceOnboarding, subject to TV-only
   unsafe HTTP consent and no persistence of source passwords in logs.

No M3U/Xtream URL, password, token, source request or new LAN permission
is introduced by this slice.
