# LC04-S2J3D2B1 — TV-owned source authorization from original F6 APPDATA

Date: 2026-11-19. Issues #401 (security), #397 (product).
Stack parent Draft #448 `9db9a02c68f0d4a579d92e4a707e9b160d519b5c`.
Parent #448 had 30/31 hosted workflows success with remaining
Hosted validation still in progress during this review. No merge claim.

## Product blocker now resolved inside the TV authorization domain

The preceding S2J3D2A proof authenticates only a fixed Cancel(1)
over a second real socket and deliberately DESTROYS the original
QR/HPKE key owner on success. It is unsuitable for actual M3U/Xtream
source creation: adding a callback to its one-shot proof would
circumvent the explicit TV-owned SourceOnboarding principal.

The real TV staging code already exists: `ProtectedSourceStaging`
delegates to the existing catalog `SourceOnboarding`, while
`Lc04TvSourceCommandSession` bounds the plaintext typed
PrepareM3u/PrepareXtream/Activate/Cancel vocabulary, counter and
commit ambiguity. The missing link is **original-QR-app-key port
identity + verified AEAD frame → TV-only principal → dispatcher**.

## New TV-only bridge (no new credential backend)

`Lc04TvOriginalAeadCommandOwner` inside `:local-control:onboarding`
enforces:
- Original TV QR/Ed25519/HPKE F6-derived `Lc04SourceAeadPort` must
  be exactly the SAME object retained by the original
  `TvHpkeQrSession` (reference identity), not a synthetic success bit,
  fake/randomized AEAD port or legacy HTTP pairing grant.
- The principal is ephemeral, TV-owned and given a fresh in-memory
  random 128-bit session ID. The existing legacy LC01
  `ProtectedSourcePrincipal(pairing, grant)` remains intact; the
  **new native principal does not mint PairingGrant, HTTP session
  credentials or remote capabilities**. Its `canWrite` predicate
  checks the live original QR owner, route, revocation and monotonic
  120-second lifetime on each source backend operation.
- Only a successfully authenticated MXA1 **request** can lazily
  construct that principal and invoke the existing TV dispatcher.
  The response is encrypted with the SAME authenticated record port;
  only fixed secret-free status codes cross the wire.
- Source preparation uses existing `SourceOnboarding`; caller cannot
  set `insecureHttpApproved`. Xtream passwords/URLs never appear in
  returned status, debug strings or logs.
- ACTIVATE is denied by default. The TV records the pending requested
  source name and exact sequence when it returns
  `TV_CONFIRMATION_REQUIRED`; a trusted, visible **TV D-pad**
  callback approves that exact source for exactly the next sequence
  within 15 seconds, single-use. Changed name, hidden TV, stale
  approval, wrong counter or other command cannot inherit approval.
- `close()` synchronously denies all further operations, revokes the
  original QR and zeroizes AEAD record keys. The TV lifecycle coroutine
  must also call `discardPreparedAfterTvStop()` after close to
  cancel any TV-owned pending SourcePreparationHandle. Activation
  outcome ambiguity is terminal; no automatic retry after a possible
  durable commit.

## Evidence and acceptance

Nine focused `:local-control:onboarding:test` scenarios use original
TV QR, TV-local Ed25519 signed phone approval, original HPKE F1..F6
and actual derived APPDATA keys in process; they prove M3U→TV D-pad
activation, Xtream secret-safe prepare/cancel, fake/wrong-owner
denial, changed-name/hidden/expired D-pad approval, revocation
cleanup, AEAD replay suppression, QR expiry and onStop race during
activation. Old LC01 authority and staging tests remain unchanged.
These tests DO NOT by themselves attest TCP F6/EOF; they must be
combined with previously verified S2J3D1/D2A real-socket tests.

`Lc04TvOriginalAeadOwnerContractTest` guards exact port identity,
ordering (AEAD verify BEFORE principal), no legacy bearer,
TV-only approval and release lifecycle. CI must execute full
`:local-control:onboarding:test`, source-aead, tv-qr, mxh1-lan,
Android app compiles and hosted build-logic contracts. All
31 exact-head workflows and independent security review required.

## Explicit remaining work before functional phone→TV UI

This bridge **does not start a TCP listener, send credentials or
alter the installed TV/Phone Activity**. It is the TV-authorized
consumer of a verified F6-bound APPDATA port, not a socket or UI.

Next S2J3D2B2 must connect the independently authenticated full
bounded APPDATA TCP request/reply exchange to THIS TV bridge and
match the phone's one-use original QR source port without
borrowing the terminal Cancel(1)-only proof endpoint. Add Android
selected-Network tests for live M3U/Xtream payloads and owner
revocation, then connect phone forms and TV catalog/Media3.
Do not create another onboarding backend or request provider secrets
over the old plaintext HTTP /pair page.
