# LC04-S2J2B — MXH1 + MXR1 six-frame real TCP loopback-only hostile laboratory

Refs #401, #433, #434, #436. Security-gated Draft. CI only via exact-HEAD GitHub-hosted Actions.

## Why a new TCP adapter is mandatory

The original S2I four-frame phone client calls `socket.shutdownOutput()`
immediately after PHONE_KEY_CONFIRM to force EOF before the TV returns
TV_FINISHED. That **cannot** be reused to send a fifth protected MXR1
record over the same connection. Silently switching its timing would
change deployed behavior before its own isolated CI and review.

The S2J2B implementation is separate and **internal to**
`:local-control:mxh1-tcp-lab`. It does not change deployed
`Lc04MxH1PrivateLanPhone`, `Lc04MxH1PrivateLanTv`, native
Android network selection, HTTP, SourceOnboarding, or any app module.

## Protocol and roles

- TV owner: original TV QR/HPKE private receiver; TV-local-approved
  phone Ed25519 identity; 120s owner expiry and one-use grant.
- Frame 1 phone->TV: length-prefixed signed HPKE SIGNED_SEED_OFFER.
- Frame 2 TV->phone: length-prefixed TV_KEY_CONFIRM.
- Frame 3 phone->TV: length-prefixed PHONE_KEY_CONFIRM.
- Frame 4 TV->phone: length-prefixed HMAC-authenticated TV_FINISHED.
- Frame 5 phone->TV: length-prefixed **exactly 104B** fixed-domain
  AES-256-GCM authenticated MXR1 synthetic record. Only after the phone
  verified frame 4. Immediately then phone `shutdownOutput()`.
- TV reads **actual EOF** immediately after frame 5 and verifies its
  AEAD tag and exact direction, counter, binding and length. A pipelined
  extra byte, replay, reflection, missing half-close or invalid record
  terminates the exact original TV QR owner.
- Frame 6 TV->phone: only after valid F5 **and** phone EOF, another
  length-prefixed exact 104B directionally authenticated synthetic
  AES-GCM MXR1. TV `shutdownOutput()`.
- Phone reads frame 6, requires **actual TV EOF**, then verifies
  AEAD tag/sequence and reports a **diagnostic Boolean only**.
- Every socket is accepted/connected to numeric 127.0.0.1 only.
  Strict read/connect timeouts, single peer/attempt, close race support.
  A failed attempt closes all source QR and derived traffic keys.

The F5 encrypted record proves possession of the agreed traffic key
and the sender's ability to generate a valid authenticated frame.
It is emitted by this honest client only after checking TV_FINISHED.
Because the phone already knows the shared MXH1 seed and MAC keys,
**F5 cannot cryptographically prove that the phone received F4**
against a malicious enrolled phone. It is not an authorization or
nonrepudiable receipt. Server and client state machines enforce the
protocol order for conforming peers.

## Hostile acceptance on hosted real TCP

1. Success: six actual TCP frames + bilateral half-close/EOF, real
   original TV QR and locally approved phone identity.
2. Reject: TV not approved, corrupted F5 GCM tag, pipelined bytes after
   fifth valid record, duplicate fifth record, unsupported size,
   truncated fifth payload, TV onStop during partial fifth record,
   original QR expiry and malicious forwarding TCP relay corrupting
   F5 after the four valid authenticated handshake frames.
3. Existing four-frame TCP/Android TV/phone code and default
   key-destruction guarantees stay unchanged. No source credentials.
4. Exact-head hosted JVM/Android CI PASS, verify artifact test XML
   and new build-logic contract; keep canonical API26/API36 evidence.
5. Never alter native VPN/Wi-Fi/Ethernet route behavior as an
   undocumented side effect, and never add a third persistent AVD.

## STOP

This is **loopback-only**. It does not validate selected private
Wi-Fi/Ethernet, two independent Android devices, Android API37
`ACCESS_LOCAL_NETWORK`, hidden background networking, source import,
or production readiness. It does not expose any arbitrary plaintext
encryption function or keys, and does not connect to legacy HTTP `/pair`.

Next S2J2C: separate security-reviewed same-Network selected-address
native adapter using the verified six-frame sequencing, explicit
TV consent for each *future* source command, route invalidation and
failure/cancellation tests. Only after that can credential handling
be designed and independently reviewed. No merge out of order:
#433 -> #434 -> #436 -> S2J2B.
