# LC04-S2J2C2D — selected Android Network six-frame TCP device evidence

Refs #401. Parent #442 `6fb344627d3e711d51c4171108cd136e5f453bee`
passed exact-HEAD hosted 31/31 GitHub Actions workflows before this
separate stacked Draft. Parent code connects the **installed** Android TV
and Phone six-frame MXH1/MXR1 synthetic-only paths but the earlier suite
did not demonstrate a real Android `Network.bindSocket` socket completing
all six frames on an Android OS.

## Technical gap

Host-side S2J2C2B loopback tests redirected the requested RFC1918
destination to `127.0.0.1`, with *fake* route/binder callbacks.
Kotlin compilation and a phone-UI emulator smoke test cannot prove
Android's chosen `Network.bindSocket` routing, private-address listener
binding, Ed25519/HPKE provider interoperability on-device, or real TCP
EOF timing.

## Implemented device test

The existing hosted phone API26 and API36 emulator matrix now runs six
**non-skipped** AndroidJUnit4 methods, retaining the previous three UI
lifecycle tests and adding a new `PhoneSealedAndroidNetworkDeviceTest`
with three methods:

1. **Mandatory TV consent**: the exact original short-lived QR and
   ephemeral public Ed25519 identity cannot open an MXH1/MXR1 private
   listener before a TV-local-approved, verified enrollment.
2. **Full six-frame Android network exchange**: derive the eligible
   current default Wi-Fi/Ethernet `Network` and an actual numeric
   RFC1918 address from its `LinkProperties`. The real original QR
   session creates the real TV listener *bound to that address*,
   not loopback; a real raw phone socket calls
   `selectedNetwork.bindSocket(unconnected)` before connecting to
   that selected-network address. It completes F1–F6 using original
   Ed25519, HPKE/HMAC, genuine two-direction AES-GCM and required EOF.
   The TV worker completion is checked after a bounded wait; same QR
   and single-use terminal close are asserted.
3. **Selected-route denied before bind**: caller eligibility returns
   false. Neither binder callback nor outgoing socket connection may
   proceed, TV authenticated proof is false, and the original phone
   key owner is revoked.

These tests use the **production** `Lc04MxH1SealedPrivateLanPhone` and
`Lc04MxH1SealedPrivateLanTv` constructors; there is no
`withTestSocketForHostileJVM`, redirected socket subclass or
localhost fallback. The one extra `:local-control:tv-qr`
Gradle dependency is **androidTestImplementation only**, not a
production phone dependency.

`Run-HostedPhoneNativeProofTests.sh` retains exact HEAD, one allowed
phone AVD per job, API26/API36, uploaded XML and fail-closed total,
now demanding **exactly six passed, zero skipped, zero failed** tests.

## Scope and unproven claims

- Both roles run inside ONE Android emulator process. This tests the
  actual Android-selected local network and real kernel TCP
  stack, **not** distinct TV and phone devices, Wi-Fi AP isolation,
  firewall, OEM routing or process-to-process IPC.
- API37 `ACCESS_LOCAL_NETWORK` permission grant, denial, revocation
  and platform-specific routing remain unqualified, since the
  currently accepted GitHub-hosted AVD matrix is only API26/API36.
- Physical two-device original QR / TV approval / concurrent
  LAN handshake must be explicitly tested before shipping.
- No arbitrary application-data frames, provider URL, credentials,
  source permissions, persistent grants or HTTP fallback are added.
  The six-frame return value is only a synthetic diagnostic.

## Acceptance / next actions

- Exact-HEAD GitHub-hosted 31/31 workflows and all check-runs green;
  API26 and API36 each must emit six non-skipped Android test methods
  with XML evidence (any route inability is a **real failing test**,
  not a skip).
- Independent crypto/platform security review and parent merge order:
  #433 → #434 → #436 → #437 → #438 → #440 → #441 → #442 → this PR.
- Next after accepted device routing: API37 runtime permission
  qualification and real TV+Phone two-device test, then move from
  synthetic-only keys to a separately reviewed, bounded, replay-safe
  authenticated source-command channel integrated with existing
  `ProtectedSourceStaging` / `SourceOnboarding.prepare/activate/cancel`.
  No authorization inferred from the diagnostic Boolean.
