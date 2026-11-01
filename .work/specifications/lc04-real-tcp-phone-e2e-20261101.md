# LC04-S3-E4B — accepted phone proof flow over real TCP (JVM hostile E2E)

Issue #401. This is a transport-level integration *test-only* milestone, based on
main after accepted offline phone proof PR #422. Android TV runtime composition
continues separately in draft #421.

## Gap found

The previously accepted suites validated two distinct but individually correct
paths: (a) native TCP packets with hand-written phone proof requests; and
(b) phone proof state machine over an injected mock/byte-relay function.
They did not execute the exact production `Lc04PhoneProofFlow` over the real
`Lc04TcpPhoneExchange` and `Lc04TvTcpServer` sockets as one unit.

## Implemented

- A new nine-scenario real-loopback TCP suite in
  `local-control/native-transport/src/test/kotlin/app/muxtv/localcontrol/nativetransport/Lc04NativePhoneProofEndToEndTest.kt`.
- Test-only dependency from native transport onto the accepted phone-flow
  module. No production dependency inversion, Android app wiring, or permission.
- Both the native hostile TCP and phone-flow CI runners cover this suite
  whenever native transport or phone-flow changes.
- Build-logic security gate enforces both the test-only boundary and the
  exact-head GitHub-hosted gate.

## Attack matrix

| ID | Scenario | Required result |
| --- | --- | --- |
| E4B-01 | Genuine QR + public phone offer + TV-local D-pad + signed probe + AES-GCM receipt on real sockets | Exactly one authenticated proof flow |
| E4B-02 | Correct public challenge without TV-local approval | No authenticated receipt |
| E4B-03 | Malicious relay suppresses the phone proof | No activated phone or authenticated receipt |
| E4B-04 | MITM mutates the genuine challenge in flight | Reject review; no signed proof |
| E4B-05 | MITM mutates the genuine TV encrypted receipt | Reject authentication |
| E4B-06 | TV socket + QR are revoked after offer | No stale receipt |
| E4B-07 | 120-second TV QR expires before proof | Reject, no retry grant |
| E4B-08 | Untrusted endpoint routes traffic to a different TV key | Wrong-QR challenge rejected |
| E4B-09 | Phone Activity closes during a real valid in-flight receipt | No resurrection of the CLOSED state |

All tests use a real JVM `ServerSocket`, bounded `MXW1` framing,
Tink Ed25519/HPKE and one-shot AES-GCM. They do **not** use a physical phone or
the Android permission system. The loopback constructors are internal test
helpers and cannot be used as a production private-LAN endpoint.

## Exit gates

Exact PR-HEAD `LC04 isolated native TCP hostile network CI` must pass
(including `:local-control:native-transport:test`,
`:local-control:phone-flow:test`, original QR/enrollment tests and Android app
compilation). Hosted validation, CI contract and relevant Android TV matrix
must also pass. A green JVM result is *not* evidence of successful Android
phone↔TV cross-process networking.

No credentials, provider URLs, IPTV source operations, source forms, HTTP
downgrade, Android manifest permissions, arbitrary hostnames or cloud service
are added. Proceed to Android actual network opt-in only after #421 accepted,
and perform separate API26/API36 app+LAN tests before considering source data.
