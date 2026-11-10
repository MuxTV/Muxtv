# LC04-S2J3B: bounded authenticated source records — isolated AEAD core

Parent stacked Draft #444, issue #401 and product umbrella #397.

## Problem

The Android-tested six-frame MXH1/MXR1 exchange proves the phone and TV
using original QR/approved Ed25519/HPKE and **synthetic fixed-domain
record keys**. MXR1's keys are intentionally spent after its F5/F6
half-close and must not be reused for provider data. A raw TCP
`SourceCommandCodec` or plaintext `/pair` would expose IPTV credentials.

Source-command-wire (PR #444) implements a strict bounded plaintext
schema and TV-only dispatcher; it does not authenticate the network.

## This implementation

- Independent pure Kotlin/JCA module `:local-control:source-aead`,
  strictly depending only on `:local-control:source-command-wire`.
  It neither imports TV consent, app/phone, HTTP, MXR1 key ownership
  nor calls `ProtectedSourceStaging` directly.
- Real **AES-256-GCM** with 256-bit separate Phone→TV and TV→Phone
  keys. The isolated host-only fixture produces fresh unpredictable
  app seed and unique 32-byte public binding. HKDF-Extract and
  HKDF-Expand (RFC 5869 SHA-256, one 32-byte block) use independent
  `MuxTV-LC04-APPDATA-*` domains and the binding digest. Never
  derive an app key by reusing a synthetic MXR1 traffic key.
- Each `MXA1` frame consists of 62-byte header (magic, v1, direction,
  zero flags, binding, u64 sequence, u16 ciphertext length, 12-byte
  nonce) and bounded ciphertext+16-byte tag.
- AES-GCM AAD authenticates the complete header and protocol domain;
  deterministic nonce `00000000 || big-endian-u64 sequence` under
  independent directional keys and one-use key ownership. 16
  request/reply pairs maximum and 120 seconds monotonic TTL.
- **Phone** can seal only typed `Lc04SourceCommand` and decode only
  secret-free `Lc04SourceReply`; **TV** can decode only requests and
  seal only replies. No generic arbitrary plaintext port and no
  application/bearer grant.
- Exactly one command may be in-flight. Strict next counter,
  cross-session binding, direction, malformed length, reserved flags,
  tag, nonce, expiry and lost route cause terminal close and key
  invalidation. Receiver snapshots attacker-controlled bytes before
  inspecting them, decrypts only after header checks and returns the
  typed source operation **only after successful GCM authentication**.
- Key arrays and request/reply temporary byte arrays are overwritten
  when possible; JVM-managed strings/provider buffers cannot promise
  full heap zeroization, so source DTOs and owner toString are redacted.

## Security STOP / explicit limits

**No production factory is exposed.** The only current way to create
paired endpoints is the internal isolated fixture for on-host tests.
This does NOT assert a real authenticated MXH1 handshake. Installing
the module or passing AEAD tests confers zero source authority.

Next reviewed slice must create an **exclusive separate application-key
handoff** from the original QR-verified/TV-approved phone with an
app-specific unpredictable HPKE-protected secret (or independently
reviewed, domain-separated MXH1 extract), explicit F5/F6 completion,
TV and phone Activity/onStop route+permission revocation, and a
rollback-safe mapping to the existing TV SourceOnboarding principal.
No generic `/pair`, no public bearer, no new credential store.

Only after that owner transition is verified may the Phone M3U/Xtream
form use the production AEAD record engine and the TV dispatcher.
Physical cross-device testing is recommended per #401 but cannot be
claimed from same-emulator testing. API37 local-network runtime
permission testing remains separate.

## Acceptance

Exact-head hosted CI on the new stacked Draft: run the 14 JVM
`Lc04SourceAeadRecordsTest` cases (real encrypted M3U/Xtream
round-trips; replay, reflection, tamper, GCM AAD/nonce corruption,
cross-session, counter order, invalid frame sizes, denied network
route, expired/reversed monotonic clock, close, 16-command limit).
Require existing full hosted matrix, application builds, source
boundary tests and static no-runtime-wiring guard unchanged and green.
This PR is **NOT** phone-to-TV source ingestion or product acceptance.
