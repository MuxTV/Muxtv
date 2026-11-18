# LC04-S2J3D2A — first authenticated APPDATA request over separate TCP

Date: 2026-11-18. Issues #401 (security), #397 (product).
Stack parent Draft #447 at `cc3221c437dbc4abf31372d8666cef04b09c3b2c`,
verified 31/31 hosted workflows and 37/37 checks. Parent stack not merged.

## Precise missing link

S2J3D1 makes the original TV-approved QR/Ed25519/HPKE F1..F6 six-frame
TCP handshake produce one-use directional AES-GCM APPDATA key-owner
ports *after* actual authenticated F6, local TV output half-close,
phone-side exact TCP EOF and route checkpoints. This is a key handoff:
no APPDATA TCP connection yet exists.

The next smallest independent proof is to open a **separate** socket,
send one encrypted APPDATA request derived from the original F6-bound
key owner, verify its exact first sequence and an authenticated TV reply.
A successful source-data proof is NOT a source permission or a finished
phone onboarding product.

## New opt-in code

`:local-control:mxh1-lan/Lc04SourceFirstRequestTcp.kt` has two
separate one-use, lifecycle-owned facades:

- TV `Lc04SourceFirstRequestTv.startOnApprovedPrivateIpv4` requires
  original verified TV owner, its previously released F6/EOF TV APPDATA
  port and a live Wi-Fi/Ethernet selected-route predicate. It binds one
  disposable listener to a numeric RFC1918 interface, rejects a
  non-private peer, time-bounds its listener (30 sec), and processes
  exactly one peer.
- Phone `Lc04SourceFirstRequestPhone.forSelectedPrivateIpv4` requires
  the existing original-owner derived phone APPDATA port, numeric
  private IPv4 and port, same selected Android Network eligibility
  predicate (including API37 local-network permission) and
  `Network.bindSocket(unconnected)` callback before connect.
  No DNS, process binding, direct insecure HTTP or generic plaintext.
- A four-byte length prefix wraps a max 6,734-byte authenticated MXA1
  record; all application-header flags, per-direction keys, nonce,
  sequence, transcript+F6 binding and GCM tag are verified by the
  existing record engine. The server accepts *only* decrypted
  `Lc04SourceCommand.Cancel(1)` and requires client TCP half-close /
  exact EOF before sending an authenticated fixed
  `Lc04SourceReply(1, EMPTY)`. Phone requires authenticated reply and
  exact TV EOF; replay, extra bytes, malformed length or wrong source
  owner fail terminally.
- This is a **one-shot source-proof command**, NOT actual cancel:
  no `SourceOnboarding.cancel` is invoked. It creates no source
  principal or TV-local activation permission. The APPDATA port
  is closed and the one-shot socket retired after the proof, preventing
  credentials from ever crossing the unreviewed handler. On success,
  the one-shot TV original QR owner is revoked immediately after the
  authenticated reply and real TCP output close; the phone always
  revokes its original key owner in finally. No reusable session
  survives successful proof.

## Acceptance evidence

Host regression `Lc04SourceFirstRequestTcpTest` must run 9 scenarios
with authentic signed MXH1/TV-approved enrollment over one real TCP
connection followed by real APPDATA AES-GCM in a **second** TCP socket.
Evidence includes valid authenticated request/reply, no app listener
before F6, forged packet, valid authenticated request with extra
bytes, denial of `PrepareM3u`, route loss, oversize record, TV
onStop of a partially received first request, and wrong original QR.
Host-only numeric endpoint redirection is explicit and cannot be
compiled into the production constructor. These are JVM real socket
tests, **not** independent physical TV+Phone device evidence.
Android-hosted API26/API36 regression matrices must remain green.

`Lc04SourceFirstRequestTcpContractTest` enforces fixed safe
command, real socket/Network binding, full EOF checks, no HTTP or
catalog dependency, and that installed Android entry points remain
unchanged. Target both Kotlin app compiles and all previously accepted
source AEAD, TV QR and MXH1 hostile tests. Require all 31 GitHub-hosted
workflow checks success on exact PR HEAD, and independent security
review before integration.

## Explicit limitations / next product-ready slice

This does **not** yet send M3U/Xtream credentials or persist a source,
because the TV-side `ProtectedSourcePrincipal`, the authenticated
source-command dispatcher and D-pad ACTIVATE approval are not attached
to the live network owner. No UI starts this APPDATA listener in the
installed TV application. It cannot infer trust from a synthetic
Boolean: both real F6/EOF APPDATA ports must be handed in.

**Next:** S2J3D2B should attach the authentic APPDATA transport to the
existing TV-owned `ProtectedSourceStaging / SourceOnboarding` with
verified session principal, bounded commands, TV-confirmed activation
and onStop/route/permission cleanup, followed directly by phone M3U/
Xtream form and an end-to-end TV catalog/Media3 fixture. Do not
replace product work with additional synthetic handshake research.
