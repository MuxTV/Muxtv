# LC04-S2-A — Android phone QR-pinned native proof-only opt-in

Issue #401. Exact base `main@b500a9fe52c10fb7650b2bc511d80b1f20e26abc`, which accepted TV native listener #421 with 16/16 hosted checks.

## Actual production gap

TV now independently owns a one-use HPKE QR session, explicit private-interface
TCP listener, D-pad approval, and immediate revoke on stop/network change.
The Android companion still only scanned public QR data. A JVM proof flow and
cancelable TCP connector passed hostile relay tests but were not wired to an
Android Activity or selected Android `Network`.

## Narrow implementation

1. `PhoneQrActivity` never connects on launch, camera permission or QR scan.
   Only after QR scan does it show inputs for the separately displayed TV
   RFC1918 **numeric** IPv4 address and ephemeral listener TCP port.
2. User taps **Запросить проверку ТВ по LAN**. Hosts, URLs, DNS, public, loopback,
   leading-zero IPv4 and noncanonical ports are rejected before any socket.
3. Android API37+ `ACCESS_LOCAL_NETWORK` must be granted by the platform
   runtime permission prompt; API26..36 never request this runtime permission.
   `INTERNET` and `ACCESS_NETWORK_STATE` are declared in the phone APK
   solely to support this reviewed, unprivileged native TCP exchange.
4. `PhoneNativeProofSession` requires a default Wi-Fi/Ethernet Network,
   rejects VPN/cellular, records the selected local private IPv4 link properties,
   and invokes `Network.bindSocket` on each unconnected TCP Socket.
   A network/default-route/address change or onStop revokes the entire proof
   and closes any in-flight socket immediately.
5. Only three `MXW1` request types can leave the phone: public
   `PHONE_PUBLIC_KEY`, Ed25519 `PHONE_PROOF`, and `SIGNED_CHANNEL_PROBE`.
   The `PHONE_PUBLIC_KEY` challenge remains untrusted review text. The user
   must compare the phone fingerprint on the TV and use the **TV-local D-pad**.
   The phone's separate confirm button only advances to receipt verification:
   it cannot approve on TV.
6. Verified means **only** the QR-pinned, one-shot HPKE/AES-GCM
   `SEALED_TV_RECEIPT` validated. The resulting boolean is not a durable
   pairing grant and does not authorize any IPTV source operation.
7. The phone UI and pair credentials never persist in `Bundle`,
   `SharedPreferences`, database or cloud, and the app's Android backup
   and cleartext HTTP policies remain disabled.

## CI evidence required before merging

- Real socket network-binding callback invoked **before connect**; throwing
  `IOException` fails closed, never silently retries with an unbound socket.
- Phone native endpoint parser negative tests for injection, DNS and canonical ports.
- Accepted JVM hostile TCP, enrollment and phone-flow suites.
- Exact-head Hosted validation, scan/proof CI and Android TV native matrix.
- NEW hosted Android phone API26 and API36 instrumented device matrix.
  On launch or after lifecycle restart, network opt-in controls remain hidden
  until scanning an actual QR. The API37 permission branch is statically
  guarded and needs separate API37 runtime device evidence when that emulator
  is supported by hosted CI.

## Boundary and limitations

The final receipt authenticates the scanned TV key for this **one attempt**,
not provider-source authority, persistent pairing, a new HTTPS origin, or a
reusable private-channel key. The current `MXW1` codec has no provider data
frames and does not protect arbitrary source fields; **M3U/Xtream
URLs, usernames, passwords, tokens, forms, preview, prepare/activate/cancel
remain disabled**. No legacy `http://<LAN>/pair` or JavaScript fallback.
The on-path adversary can suppress/relay frames or deny service but cannot
satisfy QR-pinned final receipt by merely controlling address/port.

API26/API36 emulator app-smoke tests do not prove cross-device Wi-Fi,
Android 17 runtime behavior, background battery restrictions, physical TV
performance or a bidirectional source credential channel. The next milestone
must review and implement mutually authenticated *data*-bearing framing,
session ownership/rekey/replay/AEAD counters and `SourceOnboarding` memory
handles before opening source forms.
