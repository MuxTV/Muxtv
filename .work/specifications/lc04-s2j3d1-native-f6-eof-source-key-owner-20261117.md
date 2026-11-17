# LC04-S2J3D1 — native F6/EOF original-key-owner transition (no source transport)

Date: 2026-11-17. Issues #401, #397. Stacked on #446 exact HEAD
`392922ae880d3229e8e144022b1b48b8946dd363` (31/31 hosted
workflows, 37/37 checks SUCCESS). Parent stack remains unmerged.

## Problem / security invariant

The existing six-frame selected-Android-Network transport is a diagnostic
only: `Lc04MxH1LeasedSealedPhone.completeSyntheticExchange()` always closes
the original phone owner in `finally` even after genuine authenticated F6
and exact TV TCP EOF. The TV kernel similarly creates F6 but does not
handoff the one-use QR/HPKE-derived APPDATA port to its lifecycle caller.
Thus S2J3C APPDATA exports existed only in in-process handoff tests.

App-data keys must never be released before the REAL socket F6/EOF
boundary. Source command permissions and TV-local activation approval
are separate from cryptographic key possession.

## Change

- Add explicit **opt-in** `completeSourceKeyHandoffAfterTvEof()`
  to the six-frame selected-network phone facade and its internal lease
  worker. Retain the existing `completeSyntheticExchange()` behavior,
  which STILL closes all phone keys at the end.
- The phone requires genuine F6 authentication, exact `input.read() == -1`
  TV TCP EOF, selected-route checkpoints and the original sealed phone
  identity. Only then retire the handshake socket, move the same lease
  to `SOURCE_HANDOFF`, and perform the **one-use** original-owner
  `claimSourceRecordsAfterVerifiedTvEof`. A second call is terminal.
- The lease has NO second network connect, arbitrary payload operations,
  or retained socket. Every APPDATA operation calls the SAME selected
  Wi-Fi/Ethernet/VPN/API37 permission callback and the original lease
  monotonic deadline. `close()` or route loss revokes key owner.
- Add explicit TV source-key listener factory with a required
  selected-route checker. It invokes the original TV owner
  `claimSourceRecordsAfterSixthWritten` only after authentic F5,
  F6 `write` and **actual socket.shutdownOutput()**. The port is
  handed off at most once after worker completion. Original
  `TvHpkeQrSession` remains sole owner and onStop revokes all ports.
- The existing TV and phone production apps CONTINUE to invoke only
  synthetic-only paths. This slice adds a reviewed reusable kernel API;
  it intentionally does not activate credentials or SourceOnboarding.

## Hostile evidence

`Lc04MxH1SealedPrivateLanTvTest` uses genuine original TV QR,
local Ed25519 approval, signed Tink HPKE, all six actual TCP frames,
real kernel half-close/EOF and a strict selected-endpoint socket seam.
A matching typed encrypted Cancel request/reply is accepted after the
handoff without issuing any source capability. A second claim fails
and revokes. Independent tests reject app-key extraction from the
diagnostic mode, route loss, onStop, and original QR expiry after
successful TCP EOF.

These are real JVM TCP sockets with an observable host-only endpoint
redirection; they are NOT proof of two separate Android devices or the
API37 runtime permission behavior. Existing GitHub-hosted Android
API26/API36 device matrix is preserved.

## Acceptance and next limits

Must pass exact-head `:local-control:mxh1-lan:test`, source-aead/tv-qr/
mxh1-session-lab tests, both Android app compile, build-logic
`Lc04NativeSourceKeyHandoffContractTest` and all relevant GitHub-hosted
workflows. Keep PR Draft until security review, final exact-head CI
and parent stack integration.

**STOP:** This is only a key owner, NOT a credential channel.
No APPDATA TCP connection, network source M3U/Xtream, TV-owned
`ProtectedSourcePrincipal`, TV D-pad ACTIVATE approval, or catalog
import has been wired. Do not send credentials over old /pair,
over MXR1 synthetic records or over any plaintext socket.
The next independent slice must add a bounded, authenticated APPDATA
connection with a first-request proof bound to original QR/F6/EOF,
TV source staging/authorization and device lifecycle gating.
