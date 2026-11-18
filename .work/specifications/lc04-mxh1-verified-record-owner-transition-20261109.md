# LC04-S2J2A — real approved original QR / verified MXH1 -> opaque synthetic record owner

Refs #401, #433, #434. Separate stacked Draft; GitHub-hosted CI only.

## Threat and ordering finding

S2J1's MXR1 synthetic AES-256-GCM protocol worked over a constructed
pair of identical HKDF transcript fixtures. That cannot establish that
a real TV-authorized Ed25519 signer, HPKE receiver, four-message signed
handshake and authenticated TV_FINISHED actually authorize transfer of
the exact same key owner. In particular the TV produces TV_FINISHED
BEFORE it knows whether the phone received and authenticated it.

An early TV application response would therefore be unjustified. The
fifth proof (first fixed-size authenticated PHONE->TV MXR1 record) gates
ANY TV synthetic MXR1 response in this lab. No first record, no reply.

## Contract

1. Use exact original TV QR receiver and TV-local-approved Ed25519 phone
   verifier, confirmed via enrolled proof; independent or claimed keys
   are NOT accepted by the TV receiver.
2. First four frames are unchanged signed HPKE / MXH1:
   SIGNED_SEED_OFFER -> TV_KEY_CONFIRM -> PHONE_KEY_CONFIRM ->
   TV_FINISHED. The TV authenticates the phone's confirm MAC before
   producing finished. The phone authenticates the exact finished HMAC
   before ANY synthetic record owner is installed.
3. The *new opt-in LAB APIs* on the TV QR owner and phone MXH1 lab
   transfer one live derived key owner to a public **opaque**
   `Lc04MxH1SyntheticProbePort`; only fixed-domain random 32B
   synthetic probes may cross the interface; the internal AES/HKDF
   implementation and nonce/counter state stay internal. A second
   transfer of the same owner MUST fail. No raw traffic keys are returned.
4. Distinct existing default methods continue immediately closing
   derived keys before returning their synthetic Boolean. The
   **installed Android TV/phone MXH1 clients, native TCP and loopback
   product code never call** the lab-only transfer methods.
5. The TV port may verify the first encrypted PHONE->TV record after
   computing TV_FINISHED, but the TV cannot emit a reply until that
   verified post-finished record proves the phone accepted the final
   frame. Bad first record, replay, reflection, clock expiration, QR
   revoke or owner close is terminal; original TV QR / enrollment /
   native key owner gets closed.
6. Phone closes its port on explicit revoke, failure, expired original
   handshake clock or owner stop. Both use same existing 120s MXH1
   and QR wall-clock-independent elapsed-time budgets. Do not persist
   a pairing grant, even after successful synthetic response.

## CI evidence gate

- True Tink HPKE, Ed25519 original approved phone identity, and four
  authenticated frame roundtrip tests on the GitHub-hosted JVM.
- Negative: TV cannot reply before first real post-FINISHED encrypted
  probe; forged TV_FINISHED cannot arm phone; old default API stays
  key-destructive; tampered/duplicate/invalid synthetic first probe
  revokes original TV QR; role reflection; TV onStop between fourth
  and fifth frame; expiry; second phone transfer terminal.
- Exact-HEAD :local-control:mxh1-tcp-lab:test, TV QR, phone lab,
  protected-channel cryptography, original real TCP, static architecture
  contract and TV/phone build. Existing GitHub-hosted Android TV
  API26/API36 matrices and Full validation must remain GREEN.
- Exact-HEAD artifact provenance, no skipped checks, no local test
  execution, self-hosted runners, extra permanent AVD or physical
  devices.

## STOP / next step

S2J2A is explicitly **in-process only**: not wired to installed
`Lc04MxH1PrivateLanTv`, `Lc04MxH1PrivateLanPhone` or Android UI.
The phone's real TCP client still uses its original key-erasing
`acceptTvFinished` and the TV listener still uses
`finishSyntheticMxH1PhoneConfirmation`.

Do not send or store IPTV M3U/Xtream credentials, URL, bearer or source
activation over MXW1/MXH1/old HTTP `/pair`. No arbitrary plaintext
record API is introduced. S2J2B must independently review/implement
bounded fifth-record wire I/O over selected private-network TCP with
hostile relay, early close, fragment, timeout and network-loss cases.
Only after that qualifies may a separate explicit TV source-consent
contract be proposed.
