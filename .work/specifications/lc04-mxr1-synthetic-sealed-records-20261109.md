# LC04-S2J1 — MXR1 synthetic-only sealed record contract (not source-data transport)

Refs #401; stacked on #433 `de886388f004079841308f768b9f594d046a9ae9`.
All acceptance evidence must come from exact-HEAD GitHub-hosted CI only.

## Decision and trust boundary

Accepted MXH1 S2I proves four authenticated frames over a selected native LAN:
SIGNED_SEED_OFFER, TV_KEY_CONFIRM, PHONE_KEY_CONFIRM and TV_FINISHED.
Both actual TV/phone implementations **close all derived keys** before
returning the synthetic Boolean; thus no reusable post-handshake AEAD
channel currently exists. That is intentional and MUST remain true
until a separately reviewed owner-transfer state machine is tested.

MXR1 adds an isolated Kotlin/JCA AES-256-GCM *synthetic record laboratory*
in `:local-control:protected-channel` to qualify a potential next
phase. It derives no new credentials, creates no sockets and changes
none of the existing TV, phone, QR, native transport, SourceOnboarding
or app flows. Its factory accepts **already-derived** HKDF keys but
does not claim to authenticate MXH1, TV consent, network paths or TV_FINISHED.
A test fixture derives equivalent copies of the same exact transcript
for PHONE and TV; production must supply this only from a fully
authenticated, same-original-QR handshake.

## Wire and crypto contract

- Exact `MXR1` wire length: 104 bytes, no extensions.
- Header (56 bytes): 4-byte `MXR1` magic, 1-byte version 1, 1-byte
  direction (1 phone→TV; 2 TV→phone), 32-byte SHA-256 public transcript
  binding, 4-byte BE strictly sequential 1–4 counter, 2-byte BE
  fixed 48-byte ciphertext length, 12-byte nonce.
- Ciphertext: AES-256-GCM, 32-byte fresh random synthetic probe with
  fixed `MuxTV-S2J-PROBE` prefix, plus 16-byte authentication tag.
- AAD = distinct `MuxTV-LC04-MXR1-SYNTHETIC-AAD-v1\0` domain +
  the **whole** 56-byte header, including binding and nonce.
- One direction-specific HKDF traffic key per verified MXH1 transcript.
  Nonce = 8 zero bytes + BE counter; **never reuse the same derived
  key owner**. Claim is single-use and irrevocable. Different traffic
  directions have independent HKDF keys.
- Exactly four records per direction; strict in-order sequence,
  no resync, replay cache, counter wraparound or payload negotiation.
- 120s elapsed clock TTL since creation, monotonic rollback detection,
  explicit terminal close/revoke; keys and binding bytes are cleared
  on close on a best-effort JVM basis (GC/heap zeroization not promised).
- No caller-provided plaintext, serialized arbitrary records, external
  key material getters, source operation, HTTP endpoint, pairing bearer
  or credential-bearing method.

## Adversarial CI acceptance

Dedicated JVM tests must assert:
1. Independent HKDF owners can exchange exactly four synthetic
   sealed records per direction; fifth request fails closed.
2. Replay/out-of-order, reflection, different transcript, header
   magic/version/direction/binding/counter/length/nonce and ciphertext
   tag mutation are rejected and revoke receiver.
3. Invalid length/truncation, TTL expiry, clock rollback and explicit
   close deny re-entry; derived-key owner cannot be claimed twice.
4. Exact-head product-hosted API26/API36, TV+phone build, existing
   MXW1/MXH1 hostile TCP + crypto regressions remain GREEN.
   No third canonical AVD, physical-device tests or local test runs.

## STOP / non-goals

This S2J1 change **does not** attach MXR1 to the product's MXH1 TCP
listener, transfer keys after TV_FINISHED, encrypt an IPTV URL, or
authorize SourceOnboarding. Neither `MXH1_VERIFIED` Boolean nor an
isolated sealed probe is a source capability.

Follow-up S2J2 must first review/implement the authenticated
post-TV_FINISHED **key ownership transition** on the original
TV/phone QR owners, ensure no key/nonce reset or double-consumption,
then qualify real hostile-on-path TCP and Android lifecycle evidence.
Only after that may a *separately reviewed* bounded source command
protocol and explicit per-source TV consent be considered.
