---
status: implementation_in_draft_not_merged
last_reviewed: 2026-10-17
issues: [397, 401]
component: :app:phone
acceptance: exact_head_ci_required
---

# LC04 — Native Android phone public-key QR scanner (first product-facing slice)

## Decision and scope

Research #406 established Linux desktop browser differences: Chromium 156 (automation-granted Local Network Access) could fetch a nonsecret private-LAN fixture from test HTTPS; Linux Firefox 157 and WebKit 27.2 blocked or did not support that request. These observations **do not** establish phone browser interoperability, and a plaintext private-LAN web page remains modifiable by active MITM. A **native Android** companion is a more controllable first client lane. Do not infer iOS support.

This PR delivers the **offline optical trust-input layer**, not phone provisioning or final authentication.

## Implemented

- `:app:phone`: separately installable Android app (`app.muxtv.phone`), Android API26+, native CameraX **1.6.2** and offline ZXing **3.5.4**. Has `CAMERA` permission only, no `INTERNET`, `ACCESS_LOCAL_NETWORK`, source-store or provider APIs. Network cleartext traffic explicitly disabled and app backups disabled. No launch into browser or external QR decoder.
- Phone screen with runtime permission request, rear-camera preview, resilient lifecycle binding, local QR detection, generic rejected-code message, retry, SHA-256 public-key fingerprint display, invitation/pairing identifiers and explicit warning that actual TV identity is **not yet verified**. Volatile in-memory only; no QR strings in intents, shared preferences, logs or saved navigation.
- `:local-control:qr-public`: pure Kotlin/Java public-only QR parser, strict versioned `MXLC04:1` contract identical to the **lab #405 QR emitter**. Max 900 characters / 512 public keyset bytes, canonical unpadded base64url, exactly one primary enabled Tink HPKE X25519/HKDF-SHA256/AES-256-GCM public key, then public-key primitive verification. Rejects old `http://LAN/pair#secret` browser QR; never follows it.
- `QrLumaDecoder`: CameraX YUV_420_888 Y-plane reader with bounded image dimensions, row/pixel stride checks and 0/90/180/270 degree rotation. ZXing QRCodeReader is entirely local. Camera frames are closed in `finally`; intermediary arrays zeroed when possible.
- Host-JVM tests: valid cross-compatible public QR from the existing lab exporter, invalid HTTP, wrong/oversized/malformed QR, changed key material/HPKE suite and defensive copying; image QR decode with padded stride, rotation and invalid frame.
- New exact-head Github-hosted CI `.github/workflows/local-control-phone-qr.yml` compiles the phone debug APK and executes parser/decoder tests. Build-logic negative contract forbids product source/secret endpoints and unreviewed network integration.

## Exact trust boundary

```text
Actual physical TV displaying canonical HPKE PUBLIC QR
    ↓ camera, offline
Android phone CameraX / ZXing
    ↓ QR bytes are decoded locally
:local-control:qr-public
    ↓ parsed public Tink HPKE key, SHA-256 fingerprint, invitation/pair ID
Phone public-key screen (review only, no network)
```

The source screen on the shipping TV **currently displays a separate LC03 HTTP pairing QR**, not this new public-key QR. Therefore the two apps do **not** yet complete a pairing handshake. The new phone app is a deliberately limited early product vertical: native scanning, strict local parser and fingerprint presentation. A future TV-side opt-in flow must emit the canonical key QR from a fresh TV keypair, without mixing the old bearer proof into this new protocol.

A valid public key in a scanned QR is *not* proof that the code was displayed by the intended TV. An attacker may display a different correctly formatted QR. The user/TV must confirm the same fingerprint over a genuine TV screen before a protected channel is trusted. This PR has **no pairing authorization**, no TV approval, no encrypted response, no source creation and no credential entry.

Current `http://private-IP/pair` page remains vulnerable to on-path HTML/JS replacement. Neither this scanner nor labs #403–#405 make that page safe for IPTV provider URLs, usernames or passwords. Never silently downgrade to legacy HTTP.

## Acceptance gates and next functional slices

1. Pass exact-head `:local-control:qr-public:test`, `:app:phone:testDebugUnitTest`, `:app:phone:assembleDebug`, build-logic isolation and normal hosted checks. Browser matrix and prior HPKE tests do **not** substitute for building this new app.
2. TV-side secure QR screen: add separate explicit user gesture that creates a fresh Tink HPKE recipient key, renders `MXLC04:1` locally, shows fingerprint, revokes everything when route closes or network changes. Keep this isolated from old HTTP pairing paths.
3. Native phone approval: persist **nothing** without the user's explicit acceptance. Use independently verified TV fingerprint and TV-confirmed ephemeral phone key to establish an authenticated two-way session with nonce/replay/lifecycle restrictions. Reuse reviewed Tink primitives, not custom cryptographic math.
4. Active LAN MITM / wrong-TV / replay / revocation tests and Android API26/API36 crypto compatibility, then enable phone-to-TV commands via existing protected `:local-control:onboarding` adapter. Real M3U/Xtream credentials only after this gate.
5. Physical Android phone camera smoke evidence is useful, but not a formal release blocker. Do not invent new TV AVD identities: only `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36` exist.
6. iOS companion remains a later platform decision; Android app does not establish Safari compatibility.

No parental/PIN/content filtering or signed release-candidate activity is part of this slice.
