# LC04-S2G — one-use MXH1 actual socket transport, hostile loopback lab

Refs #401 and accepted #430.

## Verified repository boundary

The deployed `Lc04TvTcpServer` exclusively handles one MXW1 request
per TCP connection, with an exact half-close before dispatch. Its
`Lc04LengthPrefix` rejects every MXH1 frame. Production Android TV
and phone bind their sockets only to selected Wi-Fi/Ethernet networks
and revoke their existing QR owner on Activity stop and LAN handover.
That reviewed one-request-per-connection path MUST NOT silently take
a new three-message meaning.

`TvHpkeQrSession` now owns the canonical public QR, original private
HPKE key, TV-local-approved Ed25519 verifier and synthetic MXH1
verification state (#430). The JVM S2E phone lab already verifies the
real TV HMAC and emits its independently authenticated phone MAC.

## This bounded S2G experiment

New `:local-control:mxh1-tcp-lab` module has no Android, HTTP,
provider/source or native transport dependency. Both its TV and phone
factory methods bind **ONLY the kernel loopback address 127.0.0.1**.
No RFC1918 product/private-LAN factory or arbitrary URL is exposed;
no Android APK imports or constructs this lab.

The TV lab receives the *original* `TvHpkeQrSession` instance,
including its already-existing TV-local consent gate and ephemeral
HPKE key. The lab never creates a second QR, keyset or approval gate.

An attempt is exactly one TCP connection, with a 4-byte big-endian
frame length (between 138 and 2258) for each canonical MXH1 frame:
1. Phone sends its QR-pinned, Ed25519-signed,
   HPKE-encrypted `SIGNED_SEED_OFFER`.
2. TV validates the approved identity and signature, decrypts the
   independent 32-byte seed using an MXH1-specific HPKE domain, and
   returns `TV_KEY_CONFIRM` with real key-confirmation MAC.
3. Phone verifies the real TV MAC, then sends `PHONE_KEY_CONFIRM`,
   half-closing its write side immediately.
4. TV requires EOF *immediately after* the third frame and verifies
   the actual phone MAC. Only then it writes a zero-length terminal
   **transport marker** and half-closes its output.

The transport marker is NOT a MAC, key confirmation, pairing grant,
provider token or application authorization. The phone returns only
a synthetic boolean after an HMAC-authenticated TV confirmation and
a canonical terminal marker/EOF. The original owner immediately
zeroes available derived key references after completing the synthetic
proof; the one-use listener stops accepting. The original QR session
lives until its Activity or enclosing lab calls close.

The TV lab accepts exactly one incoming connection; an invalid,
unapproved, malformed, timed-out or interrupted attempt causes
terminal close of the **original** QR/HPKE owner. The client allows
one active socket, and `close()` interrupts a blocked connect/read.
There is no background reconnect, proxy, DNS resolution, HTTP fallback,
IP-based identity or source read. Fixed 1.5-second connect and
2.5-second frame read deadlines bound uncooperative peers.

## GitHub-hosted acceptance

All evidence is from actual JVM loopback TCP, not substituted in-process
transport mocks. The new exact-HEAD workflow exercises both parties
using Tink Ed25519, X25519 HPKE, HKDF and real mutual HMAC:
- One successful three-message exchange under the original QR.
- Rejection before TV-local consent and exhaustion of its QR.
- Legacy MXW1 frame rejected without a downgrade.
- Zero, negative, undersized, oversized and truncated length frames.
- Forged TV HMAC with correct public transcript and matching digest:
  phone must not send its PHONE_CONFIRM.
- Forged phone HMAC after a legitimate TV_CONFIRM: no terminal marker.
- Extra fourth-byte framing after PHONE_CONFIRM rejected despite valid HMAC.
- TV lifecycle close interrupts a partial request and revokes QR.
- Phone lifecycle close interrupts a silent peer's blocked receive.
- Monotonic expiration, listener shutdown, second-use denial and
  no private key/endpoint material in public diagnostics.
- Existing MXW1 native TCP, TV QR, S2E synthetic owner and S2F KDF
  regressions plus build-logic isolation contract.

## Explicit STOP gates

This is an **experiment**, not a production MXH1 native TCP listener
or phone opt-in flow. In particular it does NOT:
- register anything in Android TV/phone Activities or use their network
  permissions; test loopback is not an Android LAN socket;
- connect to or change installed `Lc04TvTcpServer`/`Lc04TcpPhoneExchange`;
- export session keys or plaintext messages of any kind;
- authorize M3U/Xtream source URLs, user names, passwords or tokens;
- call `SourceOnboarding.prepare/activate/cancel` or store
  `SourcePreparationHandle` in any scope;
- introduce HTTP pairing, a cloud relay or persistent pairing grants.

Following accepted test evidence, the next reviewed slice must bind
an explicitly opted-in MXH1 route to the original Android Activity
and QR owner, and test API26/API36 plus API37 permission behaviour,
network transition, simultaneous stop and adversarial slow readers.
No source data-plane can be enabled until a new authenticated
data-bearing protocol, final TV completion evidence and in-memory
SourceOnboarding consent policy are independently accepted.
