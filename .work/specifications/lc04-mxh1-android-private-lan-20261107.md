# LC04-S2I — explicit Android private-LAN MXH1, original TV QR, no sources

Refs #401; accepted #432 (17/17 GitHub-hosted exact-head GREEN).

## Why a second explicit opt-in matters

The installed MXW1 TCP listener is a one-request-per-connection QR-pinned
enrollment and signed-probe transport. That public proof remains the ONLY
way the TV's own `TvPhoneEnrollment` gate can mark an Ed25519 phone key
as verified after a trusted TV-local D-pad approval.

The accepted JVM/loopback MXH1 is a four-message handshake ending in an
HMAC-authenticated TV_FINISHED. It previously had no *product* private
network listener or Android socket binding. Instantiating a second TV QR
owner would break key and lifecycle provenance. Retrofitting MXH1 as a
new MXW1 kind would break the old reviewed one-request-only decoder.

Therefore this slice introduces a *separate*, explicitly opted-in
MXH1 TCP port bound to the already selected private IPv4 interface,
using the existing `TvHpkeQrSession`, after the original phone key has
been independently approved by the TV and proven with Ed25519.

## New bounded LAN kernel

`:local-control:mxh1-lan` is a standalone Kotlin library, using only
the accepted existing QR owner, phone lab, wire codec and RFC1918-only
IP literal parser; it has no Android APIs, providers, source onboarding,
HTTP, DNS, unrestricted sockets, accounts, URLs, secrets or storage.

TV factory `startOnApprovedPrivateIpv4` rejects wildcard, loopback,
hostnames, public/IPv6 addresses and any receiver lacking the actual
`TvPhoneEnrollment.verifiedFingerprintForTv` + QR + in-memory Ed25519
verifier and `MXH1.NEW`. It binds one disposable ServerSocket to exactly
the selected numeric RFC1918 address. Peer IPs must also be RFC1918.
One TCP attempt carries four canonical versioned, size-bounded MXH1
frames: signed seed offer → TV key confirm → phone key confirm →
TV_FINISHED. The TV requires EOF after the third frame and generates
TV_FINISHED only after authenticating the phone HMAC with the original
HPKE-owned session. The listener is exhausted after one peer; any
failure revokes the *entire original QR owner*. No arbitrary encrypted
data-frame methods are exposed.

Phone factory `forPrivateIpv4OnNetwork` requires a callback that binds
the UNCONNECTED raw socket to its selected Android Network, before
`Socket.connect`. No unbound production constructor or DNS resolution
exists. A caller must revalidate the same Wi-Fi/Ethernet, the exact
initial private IP addresses, non-VPN/non-cellular/default route and
API37+ `ACCESS_LOCAL_NETWORK` permission before binding. On success
the phone waits for TV_FINISHED, validates its real session-bound HMAC
and final EOF, then discards key material and reports an ephemeral
synthetic verification bit only. Any socket failure/timeout/close
discards the phone state. No credentials or bearer are issued.

## Android TV ownership

`TvPairingController` does NOT create a new QR. It offers a second
`enableMxH1ForApprovedVisibleQr()` under its existing lifecycle mutex
and QR-generation fence, only when the original native MXW1 listener
is active and the **same** QR's TV-approved Ed25519 phone has actually
been verified. It requires the same active default Wi-Fi/Ethernet
Network and exactly matching private IPv4 address. The separate MXH1
listener gets a different ephemeral port on the SAME interface.
`revokePublicKeyQrPreview` closes both listeners, their active sockets,
the enrollment gate and the original ephemeral keyset. Existing
default-network and link-properties callbacks revoke both on VPN,
cellular, route loss or address change.

The Compose TV screen shows a distinct D-pad button only *after* the
user approved the phone fingerprint and enabled the old native
listener. Pressing early fails closed: server creation still requires
the gate's **actual verified** fingerprint, not the UI's approval flag.
The TV Activity applies its already-reviewed API37 LAN runtime
permission check to this second action. Neither scanning the QR nor
opening the screen starts a listener.

## Android phone ownership

`PhoneNativeProofSession` retains the original scanned QR and ephemeral
`PhoneSigningIdentity`, and preserves the actual originally selected
Android Network and private addresses until onStop/revocation. A
separate button and separately entered **second MXH1 port** become
visible ONLY after the existing MXW1 proof has verified the TV's
QR-pinned encrypted receipt. `beginMxH1` requires the exact original
proof's authenticated state; it cannot start from a QR scan, fake
address, MXW1 challenge or unauthenticated TV screen alone.

After revalidating `ACCESS_LOCAL_NETWORK` on API37+, Wi-Fi/Ethernet
transport and selected Network/link addresses, the new MXH1 socket
is bound before connect, and the actual Tink/HPKE/Ed25519 four-frame
exchange is run in the same cancellable worker. `onStop`,
`onDestroy`, reset and route callback synchronously close the
socket and discard the phone signer and keys. No background retry.

The UI exposes only synthetic completion; not a source-edit button,
a pairing token, credential import, encrypted arbitrary payload or
persistent grant.

## Hostile evidence and acceptance

The GitHub-hosted exact-head JVM suite requires real loopback TCP with
four authentic frames and the original TV-local-approved QR, a required
pre-connect network-binder callback, denial before approval, numeric
private-only factory parsing, invalid frame-length denial, cancellation
of a stalled TV reader and a silent phone peer, and owner expiry.
Existing S2G/S2H adversarial tests remain mandatory (forged HMAC,
missing/misbound final frame, smuggling, replay, timeout, QR mixing).
Both actual Android APK Kotlin source sets must compile on the same
commit; existing API26 and API36 Android device matrices are triggered
by app changes and must be green.

A strict build-logic contract verifies original TV owner, onStop/
route revocation, API37 permission gates, two separate user gestures,
old MXW1 dispatcher isolation and a complete prohibition on source
or HTTP operations in the new transport.

## Security limitations / further work

This is now an opt-in Android LAN SYNTHETIC handshake rather than merely
a loopback experiment. But it is NOT yet a production IPTV source
enrollment channel. Cross-process real phone↔TV adversarial device
tests over two actual Wi-Fi/Ethernet devices and API37 permission
behavior have NOT been proven solely by JVM and emulator tests.
The old MXW1 and new MXH1 listen on separate ports sharing one
original TV QR; denial and concurrent-stop cases must receive
extended host/device testing before sensitive data can flow.

Successful MXH1 still returns only a Boolean. The derived keys are
closed, so there is NO reusable authenticated AEAD source data plane.
The next separately reviewed slice requires dual-device hostile
network/cancellation tests, then a bounded encrypted payload protocol,
explicit per-source TV consent, and integration solely with the
existing in-memory `SourceOnboarding.prepare/activate/cancel` path.

No M3U/Xtream URL, password, token, HTTP pairing fallback, external
relay, or persistent pairing grant is introduced here.
