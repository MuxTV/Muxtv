---
status: LAB_ONLY_DO_NOT_ENABLE_NETWORK
last_reviewed: 2026-10-18
issues: [401, 397]
depends_on: [403, 407, 410, 411]
---

# LC04-S3-D0 — authenticated television acknowledgement

## Verified gap from the actual codebase

The existing `LabSignedHpkeEnvelope` verifies that an **approved phone**
signed a ciphertext to the pinned TV HPKE receiver. It protects the
**phone-to-TV** direction only. A plaintext HTTP reply, even after successful
sender enrollment, could be forged/replaced by an attacker on the same LAN.
No production `SourceOnboarding` access follows from the signed request.

This slice builds an **isolated JVM-only** negative/positive feasibility fixture,
not a new Android transport implementation or claim of complete mutual pairing.

## Construction

1. The phone generates a new 32-byte reply secret and a 16-byte challenge
   using SecureRandom. It retains them in a one-use, memory-only
   `LabPendingTvReply`; they are never placed in HTTP headers or URL fields.
2. Phone packages the 48 bytes **inside** its signed and HPKE-encrypted
   `CHANNEL_PROBE`. The command is not SOURCE_PREPARE, SOURCE_ACTIVATE or
   SOURCE_CANCEL, and nothing is routed to `SourceOnboarding`.
3. TV **first** verifies the approved phone's per-command Ed25519 signature,
   exact pairing/TV key fingerprint, HPKE-associated data and monotonic
   counter by the existing `LabSignedHpkeTv`. Failure produces no response.
4. TV **only after decryption** obtains the 32-byte secret and constructs
   a single AES-256-GCM receipt. The authenticated additional data (AAD)
   binds domain/version, the two pairing IDs, pinned TV HPKE fingerprint,
   approved phone fingerprint, reverse direction (`tv-to-phone`),
   `CHANNEL_PROBE` operation, command counter and SHA-256 request ID of the
   signed encrypted request (operation, counter, ciphertext, signature).
5. Phone checks response size/nonce/counter/request ID before AES-GCM
   verification. It checks exact acknowledgement and its 128-bit phone
   challenge in constant time. Acceptance consumes/erases the local key.
   Three failed response attempts destroy the pending key.

The AES-GCM IV is randomly sampled for each **unique per-request reply key**.
The receiver side's previously established monotonic request gate prevents
duplicate requests creating a second acknowledged command. The fixture
performs no key persistence or real LAN I/O.

## Attack evidence to require from exact-head CI

- Valid previously TV-approved Ed25519 signer + actual HPKE receiver -> one
  accepted authenticated response; second verification fails.
- Attacker with only public QR, fake ciphertext or signed but unapproved key
  cannot forge a reply that the phone accepts.
- Changed response ciphertext, IV, request digest/counter or wrong direction/
  pairing/TV context -> rejection.
- Replay of original signed HPKE request -> no second TV reply.
- Wrong TV HPKE private key, revoked signer or malformed 48-byte secret ->
  no TV receipt.
- Closing the phone's pending reply state -> subsequent genuine reply rejected.
- Test output and logs may expose **only fixture metadata**, never the
  random reply key, raw secret payload or provider credentials.

CI: GitHub-hosted JDK17 `local-control:crypto-lab:test`, plus normal repo
hosted validation. No physical Android devices, signing keys, extra AVD,
server listeners or app permissions.

## Important limits and next production step

This establishes **receipt authenticity** under the stated assumptions,
not secrecy/authentication of every future application response and not
trusted phone enrollment in the face of a compromised phone UI. It uses
JDK17's Ed25519 and AES/GCM providers in the lab; production must use
Android API26/36-qualified Tink/Android providers and must design a
bounded wire codec, TV-owned private receiver API and explicit trusted
TV on-screen phone fingerprint approval.

It is not yet a verified production protocol, a real network exchange,
a completed handshake, or a source capability. The next production
slice is a **transport plus session state machine** that validates all
messages in both directions, prevents replay/rollback/reflection after
restart or network change, handles timeout/revoke and never sends any
provider URL/password/token over legacy unauthenticated `/pair`.

No M3U/Xtream source operation is enabled by this research.
