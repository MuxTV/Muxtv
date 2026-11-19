# LC04-S2J3E2 — installed Android phone HTTPS M3U over original F6/EOF APPDATA

## Dependency and scope
Implemented in the existing stacked Draft #451, based on #450 and the
#433..#449 chain. Exact GitHub-hosted HEAD required before acceptance.

## Product sequence
1. Phone scans ORIGINAL TV HPKE QR; native Ed25519 proof and TV-local
   fingerprint approval authenticate the initial phone identity.
2. TV owner explicitly chooses the protected-source six-frame F1–F6
   mode (not the historical synthetic-only diagnostic listener).
   Phone explicitly chooses the matching source handshake mode.
3. Phone authenticates the TV's F6, exact kernel TCP EOF and selected
   Network, then retains its one-use source key port privately WITHOUT
   yet opening a second connection. No provider data in handshake.
4. TV explicitly starts APPDATA AFTER F6 output shutdown. Phone operator
   types the displayed APPDATA port. Its IP MUST equal the exact original
   numeric private TV IPv4 of the authenticated handshake; port is a
   locator, NOT cryptographic authorization.
5. Phone user enters an HTTPS M3U URL and a bounded source name, then
   taps a separate explicit button. Bind an UNCONNECTED socket to the
   original selected Android Wi-Fi/Ethernet Network; check route and
   API37 permission before/after bind and all operations.
6. Send only AES-GCM original-F6-bound PrepareM3u and require authenticated
   PREPARED response. TV delegates through existing ProtectedSourceStaging
   and SourceOnboarding, never the old plaintext HTTP /pair.
7. Phone requests Activate, TV displays only the source name and
   independently approves with D-pad for the EXACT next sequence within
   15 seconds. A SECOND phone action sends that final command.
8. Null/unknown activation result is TERMINAL and never retried.
   onStop/route loss closes source socket, APPDATA records, F6 owner and
   clears UI secrets. Source staging cleanup is TV owned.

## Limitations
- Initial M3U form accepts HTTPS only. Plain HTTP requires an explicit
  later TV-local insecure-network approval, not a hidden phone flag.
- Xtream remains bounded at the protocol level; its phone input is NOT
  wired in this slice.
- TV APPDATA port is manually entered, not yet advertised in a signed
  F6 extension. Cryptography authenticates the original TV F6/AEAD key
  owner, not the plaintext endpoint string.
- Existing real two-TCP hostile host tests are retained, and phone/TV
  API26/API36 hosted device matrices must pass. On-emulator no-QR source
  forms stay hidden. This does not claim a cross-device physical E2E or
  Media3 playback after source activation.

## Acceptance
Exact-head 31/31 hosted workflows + independent security review of
unmerged #433..#451 stack. Never merge before prerequisite ordering and
review. No new AVD and no local-PC testing.
