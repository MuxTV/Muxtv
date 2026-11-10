# LC04-S2J2C1 — one-use selected Android Network eligibility lease (NOT an Android transport)

Refs #401, #433, #434, #436, #437. Research-only stacked Draft; GitHub-hosted exact-HEAD CI.

## Verified platform baseline

- Android application convention plugin `AndroidApplicationConventionPlugin.kt` sets
  **compileSdk = 37, targetSdk = 37, minSdk = 26** for both installed apps.
- Both current app manifests already declare
  `android.permission.ACCESS_LOCAL_NETWORK`; this slice changes **neither**
  manifest, targetSdk nor the installed Android UI.
- PhoneNativeProofSession already inspects original selected active
  `Network`, private IPv4 LinkProperties, Wi-Fi/Ethernet, excludes VPN
  and cellular, checks API37 permission, registers default-network
  callback and binds an UNCONNECTED raw socket with
  `selected.bindSocket(unconnected)`.
- The existing four-frame S2I MXH1 uses that binder, but must stay
  key-destructive and cannot append MXR1 after its own early half-close.
- Official Android 17 LAN behavior:
  https://developer.android.com/privacy-and-security/local-network-permission
  API37/target37+ requires `ACCESS_LOCAL_NETWORK` or privacy-preserving
  picker. Do not infer a permission grant from a successful socket bind.
- Google Play policy requires core-functionality justification if
  using broad `ACCESS_LOCAL_NETWORK` instead of an adequate picker:
  https://support.google.com/googleplay/android-developer/answer/16935362

## This slice: socket lease safety only

Adds INTERNAL `Lc04MxH1SelectedNetworkLease` under
`:local-control:mxh1-lan`. It is **not** a new Android entry point,
connection factory, six-frame adapter, QR/TV consent proof, or source
capability.

A future S2J2C2 adapter may call it only after supplying an actual
selected-Network eligibility predicate which independently checks:
original user opt-in + TV-visible QR, exact selected active/default
Network equality, RFC1918 address, Wi-Fi/Ethernet (no VPN/cellular),
unchanged interface IP, granted API37 LAN runtime permission, elapsed
TTL and lifecycle epoch.

Properties:
1. Numeric RFC1918 destination and bounded port; no DNS or other
   interface/private path fallback.
2. Only a fresh, unconnected, unbound raw `Socket` can be claimed.
3. Exactly one bind attempt to a caller-supplied Android selected
   `Network.bindSocket` callback. Eligible checked before **and after**
   binder. No external callback runs under the ownership mutex, so
   Activity.onStop can close even a currently blocked bind.
4. Each `checkpoint` revalidates route/permission and monotonic
   elapsed time. A 120s lease TTL is a maximum; the original QR owner
   may impose a shorter lifetime.
5. Explicit `revokeSelectedNetwork()`, bind exception, concurrent
   close, route loss, permission denial, clock rollback or TTL
   expiration closes active socket and calls original-owner revocation
   **at most once**. No reconnect or second lease on same owner.
6. Endpoint is provided only after valid binding and checkpoint,
   no arbitrary caller-supplied `SocketAddress` and no payload API.

## GitHub-hosted acceptance

New JVM tests exercise unbound happy path, forbidden address/port,
unapproved route, post-bind permission/route loss, binder exceptions,
prebound raw socket, route change DURING bind, concurrent onStop while
binder callback remains blocked, 120s timeout/clock rollback,
second attempted bind consuming original key/QR owner.

Build-logic contract proves no imports/references from installed
Android TV/phone or the existing MXH1 product classes, no source,
credentials, arbitrary plaintext, process-wide network binding or
manifest/targetSdk mutation. Run in existing GitHub-hosted
`lc04-mxh1-android-private-lan-ci.yml` including
`:local-control:mxh1-lan:test` and source-isolation contract.

## STOP / future S2J2C2

This slice **does not connect to any TV**, including a physical pair:
its network callback is fake in JVM tests; thus it cannot prove an
Android `Network.bindSocket` invocation, permission dialog,
API37 denial on device, or selected private Wi-Fi/Ethernet transport.
No SourceOnboarding, M3U/Xtream URL/user/pass/token, HTTP `/pair`,
persistent pairing bearer or arbitrary encrypted command is allowed.

Next separately reviewed S2J2C2: compose the accepted six-frame
MXH1+MXR1 engine with this lease in an opt-in TV/phone selected-network
adapter. Require host API26/API36 regression evidence and API37
permission-denial test before enabling UX. No third canonical AVD
or invented physical-device evidence.
