# LC04-S2J3D2B2 — authenticated multi-command APPDATA TCP source transport

Date: 2026-11-19. Product #397, security #401.
Parent Draft PR #449 HEAD `d4ca1791e363d17054272772c9f15dc4407b4281`.
At this review 30/31 hosted workflows passed on parent; API26
Android TV device job failed to download Kotlin DSL dependencies
before tests. Failed jobs rerun; no gate disabled, no merge.

## Gap and resolution

S2J3D2A's one-shot Cancel(1) proof closes its original TV QR owner
after the first reply, which makes it unsuitable for source operations.
S2J3D2B1 already authenticates original-QR F6-bound MXA1 records and
dispatches typed source commands ONLY via TV-owned
`ProtectedSourceStaging → SourceOnboarding` with D-pad consent.

This slice adds an **independent full source APPDATA TCP session**.
It does NOT relax the deliberately terminal fixed Cancel-only proof.

## Production entry points

- TV `Lc04SourceCommandTcpTv.startAfterCompletedF6OnApprovedPrivateIpv4`
  can acquire a key owner ONLY through
  `Lc04MxH1SealedPrivateLanTv.takeSourceKeyPortAfterRealF6OutputClose()`.
  It then constructs the existing TV-only source authorization bridge
  which verifies exact original QR object identity. A plain MXH1
  Boolean cannot authorize catalog operations.
- Phone `Lc04SourceCommandTcpPhone.afterVerifiedTvEofOnSelectedNetwork`
  can acquire its source AEAD port ONLY through
  `Lc04MxH1LeasedSealedPhone.completeSourceKeyHandoffAfterTvEof()`,
  which verifies real original-QR F6 and exact TV TCP EOF. The
  source socket is bound to the selected Android Network while
  UNCONNECTED, before connecting to numeric RFC1918 TV IPv4.
- One 4-byte bounded length + MXA1 GCM record per request/reply,
  16 strictly ordered commands maximum, single TCP peer,
  no reconnection/fallback/relay, no arbitrary plaintext handler or
  old /pair source route. The TV runs the existing suspending source
  dispatcher off UI thread; every request is authenticated by the
  original TV-owned source record engine and every reply encrypted.
- Explicit TV-only `pendingActivationNameForTv()` and
  `approvePendingActivationOnTv()` call sites preserve prior
  single-use 15s D-pad approval for a specific source/sequence.
- Phone API exposes exactly `prepareM3u`, `prepareXtream`,
  `requestActivate`, `cancel`. A null response indicates terminal
  or unknown outcome; it must NOT automatically retry Activate or
  replay provider secrets.
- A connection is bounded to 120s and 16 requests, private address
  on TV with no loopback in production, selected route and TV
  visibility on each request, 30s pre-connect accept window, and
  bounded I/O. TV/phone `close()` synchronously close sockets,
  revoke original QR/key-owner; TV worker cleanup cancels
  unactivated SourcePreparationHandle via existing staging.

## Evidence

8 hostile JVM tests must exercise REAL two distinct TCP sockets:
the TV-approved Ed25519 phone, signed HPKE original F1–F6, phone
verification of F6 + real kernel TV EOF, then a second APPDATA
TCP with M3U prepare → TV-confirmed activate, Xtream prepare →
cancel, forged AEAD frame, replay, selected-route loss, invisible
TV, oversized frame, and 17th-command denial. The fake catalog
backend must show exact bounded operations and no leaked secrets.
These are host-only loopback routing fixtures, not physical Android
UI/device coverage.

The new build-logic `Lc04SourceCommandTcpContractTest` guarantees
the exact real F6/EOF owner is the sole source of records, the
TV-owning dispatcher is used, installed apps remain unchanged,
legacy /pair cannot use source transport, and hosted CI includes
the new tests. The historical MXH1 Android contract is updated
narrowly: the legacy handshake file remains prohibited from catalog
and source imports, the APPDATA bridge may depend on the TV-owned
onboarding module, and `:catalog:api` is permitted only for the JVM
fake-backend test fixture, never as an MXH1 runtime dependency.
Require all 31 workflows on exact HEAD, both Android API26/API36
matrices and independent security review.

## Remaining PRODUCT work — prioritize UI

**APPDATA endpoint discovery is not yet integrated into the signed TV
pairing flow**: after the six-frame handshake, this prototype starts a
new ephemeral source listener and callers must supply its
authenticated address/port via a trusted TV-owned handoff. A numeric
LAN IP/port is a locator, never authorization; wrong endpoints cannot
decrypt but may cause denial of service. Never send credentials to a
source TCP endpoint until the pinned QR/F6 exchange has completed.

Neither installed Android Activity starts this transport or exposes
M3U/Xtream fields. The next product slice is Android TV/phone
composition root + secure APPDATA endpoint advertisement from the
TV-owned handshake and a phone M3U/Xtream form with a real D-pad
confirmation screen; then original catalog import and Media3 E2E.
Do not build any more synthetic handshake experiments.
