# LC04-S2H — four-frame authenticated TV_FINISHED, source-free JVM/TCP lab

Issue #401; base main `59a61a9ba73a7e7a9c397ee0bbc550bb36ddeba6`;
accepted S2G #431 (23/23 exact-head hosted checks).

## Protocol error found in accepted S2G

The previous real loopback MXH1 TCP exchange authenticated TV_KEY_CONFIRM
and PHONE_KEY_CONFIRM, but its final zero-length TCP marker could be sent
by an untrusted network intermediary. The phone could therefore claim
transport completion without cryptographic evidence that the QR-owning TV
had accepted the exact phone confirmation. A stream EOF is transport
termination, NOT authenticated final key confirmation.

There was no data-plane authority at risk because all accepted S2G
exchanges are synthetic-only, keys are not exported, and Android never
links this module. Nevertheless the missing proof must be fixed BEFORE
adding any real cross-process Android network integration.

## New canonical fourth frame

`TV_FINISHED` has MXH1 kind 0x14, existing version 1 and 138-byte
canonical header plus **exactly 32 bytes** of HMAC-SHA256. All header
identity fields, request counter and signed-offer SHA-256 digest match
the same original QR, TV and approved Ed25519 phone; body length is
fixed and no suffix/JSON is accepted.

This is a lab-only protocol extension, not a new production legacy
MXW1 type or an approved application protocol revision. Its
`Lc04HandoffWireLab.structurallyMatchesOffer` test checks only public
fields and **does not authenticate the MAC**.

The TV_FINISHED tag is
`HMAC(confirm-tv-key, domain || transcriptDigest || sha256(exactPhoneConfirmWire))`.
The domain is the distinct constant
`MuxTV-LC04-MXH1-TV-FINISHED-v1\0`; the TV confirmation HMAC uses a
different domain. The existing independent HKDF-confirm-tv key is scoped
to the exact signed HPKE seed offer, both QR IDs, TV HPKE and TV-approved
Ed25519 phone fingerprints, and fresh 32-byte TV nonce. The final
HMAC is also bound to the **whole canonical PHONE_KEY_CONFIRM frame**,
including its authenticated 32-byte phone MAC, header, and counter.

TV's **original** `TvHpkeQrSession` verifies the actual phone MAC
before generating the fourth frame, closes its derived keys immediately
after emitting it, and retains only a volatile synthetic proof bit.
It does not export session keys or source capabilities.

The phone's loopback TCP path uses
`acceptTvConfirmationAwaitingFinished` which transitions only to
`WAIT_FINISH`; `hasAuthenticatedTvSyntheticProof` remains false
until the fourth frame has valid kind/length/exact offer binding, the
real HMAC is checked in constant time and TCP EOF is observed without
extra bytes. On success it closes the key material and enters VERIFIED;
on malformed/missing/misbound/forged/duplicate final, expiry, owner
stop or network cancellation, the phone is terminally revoked.

The old three-message in-process test API still exists for older
S2E/S2F research regressions; it is NOT used by this new TCP path.
The old transport zero marker is **never** accepted in S2H.

## Hosted hostile evidence

- RFC 5869 and HMAC fixtures: exact phone frame digest, wrong digest,
  different TV nonce/session, reflection into TV_KEY_CONFIRM, missing
  key material and post-close rejection.
- Strict TV_FINISHED frame format and attacker-forged canonical
  MAC that still passes *only structural* matching; malformed lengths,
  identity/digest/counter/role mutation denied.
- Original TV QR-owner TV-local approval and actual Tink Ed25519/HPKE;
  pending WAIT_FINISH cannot claim success, real finished succeeds,
  forged finished or replay fails, lifecycle stop revokes.
- Real TCP listener/phone on kernel loopback 127.0.0.1 with legitimate
  initial three messages; malicious fourth frame scenarios:
  wrong MAC, wrong kind/counter/identity/digest, trailing smuggling,
  truncated ciphertext/HMAC, old zero marker, EOF without finished.
- Existing MXW1 native TCP regressions, TV QR KDF, Android provider
  compatibility and exact-HEAD build-logic guards are mandatory.

All tests must pass on the same PR HEAD under GitHub-hosted runners.
Source data-plane, internet/LAN addressing and Android app code are
not part of this slice.

## Security limitations / subsequent work

Even authenticated TV_FINISHED cannot guarantee simultaneous knowledge
on both sides if the network drops the final TV frame: the TV may have
processed PHONE_KEY_CONFIRM while the phone correctly reports failure.
No source permission or persistent grant is issued on either side,
so this lab remains fail-closed.

Next reviewed work: opt-in Android TV + phone MXH1 private LAN route
reusing the existing QR-owner and selected Wi-Fi/Ethernet binding, with
explicit Android 17 LAN permission, API26/API36/API37 device tests,
route handover, process death, cancellation and hostile MITM/slow peer
conditions. Any privileged data plane needs a separately specified
AEAD protocol, explicit consent and session-scoped SourceOnboarding
prepare/activate/cancel ownership, without new plaintext HTTP or
persistent password storage.

Neither this PR nor its success signal authorizes source URLs,
M3U/Xtream credentials, provider logins or arbitrary plaintext.
