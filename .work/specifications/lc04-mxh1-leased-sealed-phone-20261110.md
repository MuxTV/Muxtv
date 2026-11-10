# LC04-S2J2C2A — selected-Network-leased six-frame synthetic phone TCP candidate

Refs #401; logical parent chain #433 -> #434 -> #436 -> #437 -> #438.
Research-only, stacked Draft. GitHub-hosted exact-SHA CI ONLY.

## Verified baseline

All 31 GitHub Actions workflows and all 37 checks PASS on the exact
original parent PR #438 SHA
`958d4f85a862856aba2f04130387ede5f0dbeea5`. The S2J2B parent #437
also reached 31/31 workflows and 37/37 checks after independent GitHub-hosted
single-job reruns restored the two previously absent Android device gate
jobs. Main remains unmodified and none of these parents is merged. No
independent security review has yet qualified this chain.

## Problem and decision

The installed Android phone MXH1 client half-closes immediately after
PHONE_KEY_CONFIRM (frame 3) and destroys its key owner after verified
TV_FINISHED (frame 4). A six-frame synthetic record transport requires a
*separate* adapter: it MUST not silently modify the installed four-frame
app, nor claim that the S2J2B 127.0.0.1 laboratory is Android LAN evidence.

This slice adds **only the phone side**, internally in
`:local-control:mxh1-lan`, named
`Lc04MxH1LeasedSealedPhone`. It can later be driven by a reviewed
Android selected-Network adapter, but is not imported by either APK.

## Wire / lifecycle contract

1. The original S2J2C1 `Lc04MxH1SelectedNetworkLease` must accept the
   new raw unbound/unconnected socket exactly once; its injected binder
   must bind the unconnected socket to the chosen Android
   `Network.bindSocket`. The lease validates a numeric RFC1918 TV address
   and port, monotonic TTL, and selected-route eligibility before/after
   bind.
2. After binding, connect only to `lease.endpointIfBoundAndEligible()`
   with a 1.5s connect budget and 2.5s IO budget. Reject any route,
   permission, network, TTL or QR owner invalidation; no generic
   fallback, hostname resolution, process-wide network binding or
   reconnection.
3. Four signed HPKE/MXH1 frames use the ORIGINAL QR-pinned
   `Lc04MxH1PhoneLab`:
   SIGNED_SEED_OFFER -> TV_KEY_CONFIRM -> PHONE_KEY_CONFIRM ->
   TV_FINISHED. Phone MUST authenticate TV_CONFIRM before sending
   PHONE_CONFIRM, and authenticate TV_FINISHED before generating ANY
   sealed record.
4. Send exactly ONE 4-byte-length-prefixed, 104-byte MXR1 fixed-domain
   synthetic AES-256-GCM probe F5, then `shutdownOutput()`.
5. Read exactly ONE 4-byte-length-prefixed 104-byte TV->phone MXR1
   synthetic F6; require **actual TV TCP EOF**, live selected route,
   correct direction/nonce/counter/transcript binding and valid AEAD.
6. Always destroy phone key owner and lease on success AND any failure.
   The return value is a Boolean test diagnostic only, not a paired
   bearer, service authority or ability to submit source URLs.

## Test strategy

The JVM test uses **actual 127.0.0.1 TCP** and the original approved
TV QR + Ed25519/HPKE/MXH1 state machine, but a **test-only injectable
socket** records the requested validated RFC1918 destination and
redirects the *underlying test connection* to a localhost mock TV
listener. The binder and network eligibility callbacks are **fake**;
this cannot qualify real Android `Network.bindSocket`, local-network
permission grant, physical two-device Wi-Fi/Ethernet, VPN route safety,
or API37 denial UI. The main factory does not accept a socket override.

Hostile cases (in addition to parent's tests):
- Authentic six-frame exchange, exact selected destination, one-use
  closing and no reconnect.
- Forged TV_FINISHED MAC -> no F5.
- Tampered F6 GCM tag -> no authenticated success.
- Pipelined extra response byte -> EOF rejection.
- F6 malformed size, truncated body, missing frame.
- Mid-handshake selected-route loss -> terminal.
- Initial LAN permission denial -> no bind or outgoing offer.
- Binder returning closed socket -> no fallback.
- Activity onStop while blocked on TV confirmation -> bounded abort.
- 120s TTL before connect -> no offer.
- Route revoked during bind -> no connect.
These are exposed via existing GitHub-hosted
`lc04-mxh1-android-private-lan-ci.yml` plus source-isolation checks.

## STOP / next slice

This is NOT an Android UI bridge and NOT an actual selected-network
two-device proof. A hostile injected binder callback is **not**
platform network attestation; production must supply the actual
`ConnectivityManager` selected `Network`, runtime API37
`ACCESS_LOCAL_NETWORK` permission state, no VPN/cellular, unchanged
link addresses and synchronous onStop/onLost closing.

No TV selected-private-LAN six-frame listener exists in this slice;
the TV side is currently only an authentic one-shot loopback test
server. Next S2J2C2B: separate TV-native private address listener with
actual fifth/sixth frame, hostile CI and platform routing evidence.
Then S2J2C2C: platform adapter tests for Android API37 deny/revoke
and selected Network; no unsupported inference from API26/API36.

No M3U/Xtream username/password/token, source activation, arbitrary
plaintext, legacy HTTP `/pair` downgrade, persistent pairing bearer,
or APK/source credentials in this phase.
