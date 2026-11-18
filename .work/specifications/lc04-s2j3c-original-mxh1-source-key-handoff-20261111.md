# LC04-S2J3C — export app-data records from the ORIGINAL MXH1 owners

Date: 2026-11-11. Issues #401 (security) and #397 (product).
Stacked parent Draft #445 exact HEAD
`54cd709f0ce46feadcf66819ef5f901efc433510`
(31/31 hosted workflows SUCCESS). No parent merge, no `main` change.

## Why this slice is necessary

The six-frame installed LAN handshake F1..F6 is real TCP on Android
API26/API36; MXR1 F5/F6 uses SYNTHETIC-only direction keys and terminal
EOF. PR #444 adds typed source commands and TV staging; PR #445 adds
independent AES-256-GCM records, but its only creator was an isolated
randomized host fixture, not a verified QR-owned session.

Passing the synthetic `hasSyntheticMxH1Proof()` Boolean is NOT a
credential transport grant; the original signed HPKE seed and exact
TV/phone-approved transcript must be the root for application keys.

## Concrete authenticated owner changes

- Both **original** `Lc04MxH1PhoneLab` and `TvHpkeQrSession` now
  create one `Lc04SourceAppKeyCandidate` from the SAME original
  HPKE seed and SHA256 digest of the canonical MXH1 transcript:
  invitation ID, pairing ID, TV QR public HPKE fingerprint,
  TV-approved Ed25519 phone fingerprint, exact signed offer digest,
  and fresh TV confirmation nonce.
- TV candidate is created only AFTER original TV's local phone
  enrollment gate and Ed25519 signature verification and successful
  HPKE decryption. Phone candidate is created only after independently
  verifying the TV's genuine HMAC confirmation. The original seed
  remains zeroized after derivation, as before.
- Dedicated RFC5869 HKDF-SHA256 *application* extract/expand domains
  `MuxTV-LC04-SOURCE-EXPORT-*` are distinct from the old
  `MuxTV-LC04-SYNTHETIC-HANDOFF-*` key schedule. APPDATA traffic
  keys are separately derived by the existing source-aead module.
  **Never use MXR1 traffic or key-confirmation keys for credentials.**
- Both original owners keep the pending secret and close/zero it on
  failure, expiry or QR/phone revocation. No app-data port may be
  claimed before the authenticated F5/F6; F6 itself (all 104 bytes,
  including the AES-GCM tag) is hashed into an additional
  `SOURCE-F6-DIGEST-v1` commitment and final transcript binding.
- TV owner only permits a one-use claim after F5 was authentically
  accepted and F6 was created; Phone owner only permits one after
  independently authenticating the F6 MXR1 record. A second F6
  attempt is now terminal in both original owners. Both source ports
  stay owned/revocable by their original QR/phone sessions.
- The candidate never returns raw keys or a generic arbitrary
  plaintext method; its result is the typed `Lc04SourceAeadPort`
  interface exposing only encrypted M3U/Xtream/Activate/Cancel and
  secret-free reply operations. The existing `Lc04SourceAeadRecords`
  enforces counters, direction, route/expiry, single outstanding
  request and fail-closed on any authentication failure.
- The candidate factory **does not authenticate a handshake itself**.
  Only the two original owner call sites gate it. It creates NO
  `ProtectedSourcePrincipal` or SourceOnboarding grant.

## Evidence and tests

New `TvOriginalMxH1SourceKeyHandoffTest` executes the ACTUAL
TV-owned QR, real Ed25519 phone enrollment/TV-local approval, signed
Tink HPKE offer, both HMAC key confirmations, real symmetric sealed
F5/F6 (in-process), and then encrypts/decrypts real typed M3U and
Xtream source fields over the derived APPDATA keys. It also verifies
pre-approval/pre-F6 denial, single-use claims, forged F6 denial,
independently paired TV/phone isolation, route loss, TV expiry and
owner close revocation. These are **original-handshake tests**, not a
caller-supplied key fixture.

GitHub-hosted LC04 exact-HEAD workflow must run existing
`:local-control:source-aead:test`, `:local-control:tv-qr:test`,
`:local-control:mxh1-session-lab:test`, Android compilation, 3
build-logic architecture tests and all historical MXH1/MXR1 suites.
All 31 workflows must complete SUCCESS on exact PR HEAD.

## STOP: what is not proven and what is not enabled

1. **No actual Android source traffic**. These new one-use original
   owner claims are not yet called by production `mxh1-lan` adapters.
   The standalone handoff test simulates kernel F6-send and TV-EOF
   assertions; it does not prove the network adapter saw TCP EOF.
   The native adapter MUST acquire the port only **after** a successful
   local F6 write/TV TCP half-close and authenticated receipt F6/EOF
   on the phone, while route eligibility and API37 permission remain
   valid. Do not claim a two-device E2E.
2. **No source grant from a key.** The application AEAD port is not a
   TV-owned `ProtectedSourcePrincipal`; all actual stage operations
   still need the original approved principal, TV D-pad activation
   approval and proper revocation lifecycle.
3. Existing installed `Lc04MxH1SealedPrivateLanPhone` currently
   calls `close()` after its one-use synthetic exchange, revoking
   original phone key owner. Until native finalization/lifetime is
   redesigned, the derived source port cannot safely survive this
   installed path. Do not bypass close() or retrofit plaintext TCP.
4. No phone M3U/Xtream form or catalog/Media3 E2E in this slice.
   Browser `/pair` remains plaintext and MUST NOT carry credentials.
5. Physical two-device TV tests are recommended per issue #401,
   not a formal release prerequisite; qualification claims must
   distinguish same-emulator versus separate-device evidence.

## Next allowed step

**S2J3D**: wire the verified one-use key-owner transition into the
actual selected-Network private LAN TV and Phone transport only
after native sixth-frame completion/EOF, with independent app-data
connection and authenticated transcript-bound first request,
TV-owned SourceOnboarding principal + explicit local activation
approval, and fail-close on onStop/route/API37 permission loss.
Then implement phone M3U/Xtream UI and an exact app-to-catalog E2E.
Never grant authorization from F6 alone or retain spent synthetic
AES keys.
