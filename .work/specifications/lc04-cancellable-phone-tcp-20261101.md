# LC04-S3-E4C — cancellation and ownership of native phone TCP transport

Issue #401. Precondition for wiring the phone Activity to native TCP in a
later Android-specific PR; this slice has **no Android app permission change**.

## Actual failure mode

The accepted E4A phone state machine revoked keys on `close()`, and E4B
verified that a late authenticated reply cannot resurrect VERIFIED. The
actual Java `Socket` was not owned by that closure: an onStop during
`connect()`, a blocked read or a malicious peer withholding EOF could
leave an I/O operation running until the TCP deadline. The crypto state
closed, but network activity was not synchronously canceled.

The two lifetimes must share one owner. A close that happens before a
frame can leave the process must also prevent further frame writes.

## Decision

- `Lc04TcpPhoneExchange` now implements `AutoCloseable`. Only one
  active socket may belong to the exchange, enforced under a synchronized
  register/close boundary. `close()` revokes permanently and closes
  the active OS `Socket`. A subsequent `exchange()` returns `null`
  without connecting or sending bytes.
- A read/connection racing with close may still have caused bytes to
  reach the peer before cancellation. No claim of remote rollback is
  made: TV approval is separately D-pad gated, TV owns QR expiry, and
  phone never gains source authority by sending proof.
- The injected `Lc04PhoneFrameExchange` now has a lifecycle `close()`
  method. Offline scripted relay lambdas retain a no-op implementation;
  real TCP adapters MUST delegate `close()` to the owned socket.
  `Lc04PhoneProofFlow.close()` sets CLOSED first and closes the
  transport before revoking the ephemeral signer.
- Before sending `PHONE_PROOF` or `SIGNED_CHANNEL_PROBE`, the
  phone flow checks its state again. Terminal CLOSED still wins over
  concurrent network replies.
- A well-formed but unexpected TV response is zeroed and rejected.

## GitHub-hosted real socket evidence

`Lc04CancellablePhoneTcpTest` exercises three hostile JVM cases:

1. A real TCP peer accepts but never answers; a concurrent phone close
   must promptly unblock the read and deny all future attempts.
2. A second exchange may not race the one active socket or bypass
   the permanent close.
3. A real socket owned by a phone proof flow must be canceled by the
   flow's close while a public offer is blocked, without creating any
   authenticated receipt.

The native TCP hostile workflow already runs on `ubuntu-latest` with
exact source HEAD and the production Tink/wire tests. The build-logic
contract verifies the onStop cancellation and tests cannot be removed
without failing CI. No physical phone, workstation or self-hosted
runner is needed.

## Nonclaims and next gate

- No packet can be *unsent* after the kernel has transmitted it.
  Closing a phone does **not** necessarily revoke a TV identity that
  the TV independently approved before that close; TV QR/network
  revocation remains the receiver-side authority.
- These tests are native JVM TCP, not Android API26/API36 device tests.
- No `INTERNET`, `ACCESS_LOCAL_NETWORK`, provider URL, M3U,
  Xtream credentials, `SourceOnboarding` or HTTP fallback is added.
- Once Android TV #421 is accepted, wire an **explicit** Android phone
  opt-in action to the QR-scanned identity and numeric RFC1918 endpoint.
  The phone must own this closable exchange through `Activity.onStop`,
  verify the original signed TV receipt, fail on VPN/default-network
  changes, and qualify Android APIs 26/36 on hosted emulators before
  unblocking any source transfer.
