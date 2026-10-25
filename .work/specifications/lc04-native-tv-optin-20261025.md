---
status: ANDROID_TV_OPT_IN_CANDIDATE_NO_PHONE_NETWORK_CLIENT
last_reviewed: 2026-10-25
issue: 401
depends_on: [420]
---

# LC04-S3-E3B — foreground Android TV native TCP opt-in

## Scope and security decision

This is the **first Android TV composition-root** use of the isolated
`:local-control:native-transport` accepted in #420. It is not yet a
cross-device pairing product or source-credential channel.

Opening the phone pairing screen starts **neither** the legacy HTTP listener
nor the native TCP listener. Both require separate D-pad actions. A TV user
selects a **separate public-key QR mode**, and then explicitly invokes
**Enable experimental TCP**
with the D-pad. Creating or merely showing the `MXLC04:1` public-key QR
does not bind a network listener. The native port is an OS-assigned ephemeral
TCP port bound to one validated RFC1918 numeric IPv4 address, never wildcard,
localhost, DNS or a proxy.

### No plaintext HTTP dependency

Entering the public-key QR mode serially closes any previously user-enabled
legacy LC03 HTTP `/pair` server and revokes all LC03 bearer claims **before
creating the new TV-owned
HPKE QR**. The native endpoint independently selects the active Wi-Fi/Ethernet
interface after the Activity-owned Android local-network permission gate.
There is no transfer of legacy claim/session/capability into LC04.

The screen displays `<private IPv4>:<ephemeral port>` separately from the
unchanged canonical public-only QR. The locator is not itself a trust anchor,
and is not concatenated into, or implicitly authenticated by, the QR.
The independent TV HPKE public key, TV-local verified Ed25519 phone consent
and the authenticated one-use AES-GCM TV response are the only protocol
identity and integrity controls.

## Lifecycle guarantees

1. Neither legacy HTTP nor native TCP opens on navigation. No native TCP
   server in Application.onCreate, Activity.onCreate or QR preview creation.
2. The TV Activity requires explicit D-pad opt-in and the same Android
   `ACCESS_LOCAL_NETWORK` runtime gate used for its legacy private LAN path.
3. Native binding fails closed if TV is hidden, active network is not
   Wi-Fi/Ethernet, VPN or cellular is selected, the numeric IPv4 is not
   RFC1918, or the QR generation/lifecycle epoch changes.
4. TV stores exactly one listener for the current QR; repeated requests
   return its existing ephemeral endpoint without creating a second port.
5. A lost interface, changed link address, noneligible capabilities, or a
   replacement default network (including VPN over otherwise-live Wi-Fi)
   triggers Activity-level revocation. The dedicated default-network callback
   closes the otherwise missed VPN/default-route transition.
6. QR expiry, D-pad denial, QR regeneration, leaving the route, Activity
   onStop/onDestroy and application session cleanup close the listener and
   revoke TV private receiver and approved phone state. Async cleanup never
   acts as an authorization grant.
7. Switching back to legacy HTTP needs a fresh explicit start; the old
   HTTP listener cannot silently resume inside LC04 mode.

## Explicitly excluded

- Android phone `INTERNET` permission, native phone TCP workflow, local
  network discovery or real phone-to-TV interoperability.
- SourceOnboarding `prepare/activate/cancel`, M3U/Xtream credentials,
  provider URLs, login, tokens and any `SOURCE_*` opcode.
- HTTPS/web browser bootstrap or relabeling old LC03 HTTP bearer as secure.
- An authenticated *network endpoint* solely from host/port. A different
  target can still be entered; the phone will eventually need to verify a
  TV response against its actually scanned key.
- Production protocol freeze: `MXW1` is still experimental and must be
  reviewed before allowing provider data.
- Physical hardware, performance and release qualification.

## GitHub-only qualification

- Existing `LC04 isolated native TCP hostile network CI` must remain
  green on exact head: bounded framing, real TCP adversary, nonce/replay,
  explicit TV approval and authenticated one-shot receipt.
- Build-logic contract must prevent phone, legacy server or SourceOnboarding
  gaining a native-transport dependency; only `:app:tv` is wired here.
- App TV compile/lint, Full validation, existing canonical Android TV
  API26/API36 device matrix and the new D-pad opt-in UI test must succeed
  on GitHub-hosted runners.
- No self-hosted CI, third AVD or physical devices.

## Next (NOT implemented by this slice)

Add a real Android phone TCP client behind explicit user intent and Android
network permission. Test two Android app processes, honest enrollment with
TV-local approval, QR/endpoint mismatch, malicious proxy redirection,
replay, reconnect, QR expiry, listener/network change and phone cancellation
on hosted canonical TV emulators before discussing an authenticated
SourceOnboarding adapter.
