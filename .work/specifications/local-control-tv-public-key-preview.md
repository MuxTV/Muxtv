---
status: implementation_proposed
date: 2026-10-17
issues: [397, 401]
baseline: fb18f7ccdc259c243669348bc1ae4e90df6189cf
---

# LC04-S3-B — TV-side canonical public HPKE QR preview

## Scope and product behavior

After accepted #407 Android API26/API36 provider qualification and while the
native phone scanner PR #410 is independent/open, introduce an **explicitly
opt-in TV-screen QR preview** that emits the precise `MXLC04:1` payload
frozen by #405. The preview is not substituted for the existing legacy
`http://<LAN-IP>/pair#proof` invitation; users must select a separate
TV button and receive repeated, visible "preview only" warnings.

The new `:local-control:tv-qr` module generates a **fresh Tink HPKE private
receiver in memory** (X25519/HKDF-SHA256/AES-256-GCM). It derives the actual
public keyset from that same receiver with
`TinkProtoKeysetFormat.serializeKeysetWithoutSecret` and emits:

```text
MXLC04:1:<32 lowercase hex invitation context>:<32 lowercase hex pairing context>:<canonical unpadded base64url public Tink keyset>
```

The two IDs are independent random 128-bit **unapproved context markers**,
not the old pairing bearer and not evidence that a phone identity was
enrolled, consented to, or authorized. The public keyset must be ≤512 bytes;
the entire QR ≤900 characters. The TV shows SHA-256 of the serialized
public keyset and the operator can compare it with the phone-scanned preview.
Neither key nor QR is written to Room/DataStore/NavKey/Bundle/logcat.

The TV owns the session, not Compose. It expires after 120 seconds on
`SystemClock.elapsedRealtime`, rejects clock rollback, and is revoked
when the display closes, the Activity stops, a new legacy pairing is
started, or a network change invalidates the existing controller.
KeysetHandle is not a zeroizable secret container: we drop all TV-owned
references upon revocation but **do not claim heap zeroization**.

## Explicit security nonclaims

- **No phone-to-TV network connection, HTTP endpoint or source command.**
- No authenticity for a phone; a key scanned from a screen is only a
  key pin until a sender identity, explicit on-TV phone fingerprint consent
  and signed proof are bound to that exact ephemeral context.
- No encrypted/authenticated TV replies yet, no AEAD counters/replay gate
  and no active MITM test against real client/server code.
- No SourceOnboarding, M3U, Xtream, URL, username or password via HTTP.
- Existing `/pair` JavaScript remains untrusted on an active hostile LAN.
- No claim that the current native app PR #410 has merged or is compatible
  until exact-head CI and an actual debug interop smoke test.
- No child-content/PIN system, no signing/release change, no mandatory physical TV test.

## Tests and merge gate

1. JVM: generated QR must parse through the **existing #405 lab parser**
   without production dependency on `crypto-lab`; pin the exact Tink public
   suite, canonical format, fingerprint, fresh IDs, expiry and revocation.
2. Android instrumentation in the **existing canonical API26/API36 TV AVD
   names**, no third AVD: actual TV runtime key generation, strict #405
   scanner parsing, and revocation.
3. App TV compile/lint + full hosted CI at exact PR head, with explicit
   scope statement: crypto/QR tests are not protected-channel acceptance.
4. UI/Back/D-pad focus review. Focus on "Back" rather than a privileged
   approval button. Only fresh visible key material is scannable.
5. PR stays separate from parallel phone PR #408–#410. Resolve overlapping
   Gradle/module-map changes in their integration PR; never merge three
   incompatible standalone phone implementations.

## Required next work before source transfer

- Select/merge one canonical phone-app PR (#410 candidate), without three
  mutually conflicting versions.
- Verify TV QR scan on a physical phone or an Android camera fixture.
- Add TV-confirmed Tink Ed25519 phone identity proof bound to this
  **exact** receiver/session (LC03 bearer alone MUST NOT authorize).
- Build an authenticated **bidirectional** channel, including encrypted TV
  response and replay/counter/domain binding in both directions.
- Test on-path tampering, wrong TV key, revoked/expired/duplicated request,
  reflected response, session change, secret-redaction and network change.
- Only then grant a protected principal to the already implemented
  `:local-control:onboarding` adapter for M3U/Xtream staging/activation.

Acceptance state: TV PUBLIC QR PREVIEW ONLY. CREDENTIAL TRANSPORT: **NO-GO**.
