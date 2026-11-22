# LC04-S2C — QR/Ed25519/HPKE-bound synthetic key handoff schedule

Refs: #401; accepted #425 (one-shot Android proof) and #426 (synthetic
bidirectional AEAD lab).

## Source-derived problem

The accepted TV receipt is one-shot AES-GCM evidence; its 32-byte key
is consumed by Lc04OneShotReplyGuard and is not a session key.
TvHpkeQrSession can independently verify a TV-approved Ed25519 phone
and open an HPKE signed probe, but the production probe accepts only
a 48-byte receipt-key-plus-challenge payload. It cannot be silently
upgraded to a provider-credential data-plane.

The existing 113-byte MXE1 synthetic AEAD envelope uses independent
random in-process Tink keys. It has no authenticated cross-process
way to instantiate those keys.

## Implemented, only in unreferenced JVM research module

New internal Lc04SyntheticHandoffKeySchedule uses RFC 5869
HKDF-SHA256 over a **new 32-byte independent high-entropy seed**
that a separate HPKE exchange would deliver. It does NOT reuse the
consumed receipt key. The caller must authenticate that seed independently.

The bounded canonical transcript covers:
- TV invitation and pairing IDs (16 bytes each);
- the scanned TV HPKE and TV-approved Ed25519 phone fingerprints
  (32 bytes each);
- SHA-256 digest of the exact signed HPKE handoff frame (32 bytes);
- independently random TV key-confirmation nonce (32 bytes).

A SHA-256 hash of the domain-separated canonical transcript forms
a context, then HKDF-Extract uses a distinct domain-separated salt.
HKDF-Expand produces **four independent 32-byte keys**: phone→TV,
TV→phone, phone key-confirmation, TV key-confirmation.
The confirmation HMACs use independent labels and bind to the full
transcript. No external caller can use this class to authorize source
operations, and no public API accepts arbitrary payloads. Session
key material is not returned from the production layer; tests compare
SHA-256 key digests and verify fixed synthetic confirmation MACs.
Close zeros reachable arrays and permanently rejects confirmation.

## Real-primitive test fixture and limitations

The isolated JVM test generates an independent Tink X25519/HPKE TV
private/public keyset and parses the actual canonical MXLC04:1 QR;
generates an Ed25519 phone signer; runs the real TvPhoneEnrollment
gate, confirming that a proof **before** TV approval is rejected;
approves the exact phone fingerprint via TV-local method; and verifies
the signed public enrollment proof. The approved public signer
then verifies a new signed synthetic HPKE ciphertext containing a
**dedicated, non-receipt seed**. The TV test fixture decrypts using
the paired private HPKE key. Both independently derive the same
directional keys and exchange two synthetic HMAC confirmations.

Negative tests cover:
- RFC 5869 standard test vector including the second HKDF block;
- all six transcript components changed independently;
- different seed and confusion between directions;
- zero/short/long seed, malformed fields and excessive HKDF lengths;
- close/re-use denial and redacted diagnostics;
- real TV-local Ed25519 consent, exact signed HPKE transcript and
  TV/phone key confirmations on the same synthetic seed.

The signed handoff test uses the existing signed-probe framing only
**inside one JVM**. The production TV dispatcher still accepts only
the original 48-byte probe; no new network frame, handshake extension,
secret response, phone Android request, background service, HTTP
endpoint or source feature is implemented. There is no claim that the
HMAC tag authenticates the network independently of the test fixture.

## STOP gates and next work

1. Review and allocate a **distinct versioned application-handshake
   wire kind and HPKE domain**; never overload the deployed
   SIGNED_CHANNEL_PROBE with a different secret payload.
2. Build TV-owned in-memory acceptance with verified Ed25519,
   fresh signed transcript, one-time replay ledger, confirmation MAC,
   phone confirmation and both Android lifecycle cancellation paths.
3. Wire through real phone/TV native TCP with API26/API36 (and
   API37 runtime permission) CI and adversarial route-change tests.
4. Only after authenticated, replay-safe key establishment is
   accepted, integrate the existing SourceOnboarding port with
   session-owned SourcePreparationHandle, strict cancellation,
   safe HTTP approval and no secret persistence.

No M3U/Xtream URL, password, source payload or persistent pairing
grant enters this slice. All tests run in GitHub-hosted CI.
