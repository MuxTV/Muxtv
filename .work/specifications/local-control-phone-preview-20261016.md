---
status: INTEGRATION_DRAFT_UNQUALIFIED
last_reviewed: 2026-10-16
issue: 401
modules: [:app:phone, :local-control:qr-contract]
---

# LC04 native Android phone QR preview — product slice 1

## What is actually implemented
This creates an **independent installable Android phone APK** alongside the existing TV APK. It is a first-party native, offline **image-import** interface for inspecting the public HPKE key QR defined by LC04-S2-C1/#405.

- Android system document picker opens a locally selected `image/*` without storage/camera permissions. The current preview does NOT have a live-camera scanner: the user needs an already captured image/screenshot of the TV key QR.
- Image input is limited to 8 MiB encoded, 12k original dimension and downsampled to <=2048 pixels on the longer side before bounded ZXing QR decoding. No filename, URI, QR payload or parsing exception is displayed or logged.
- `:local-control:qr-contract` is a separate **strict public-key-only QR parser**: `MXLC04:1:<invitation>:<pairing>:<base64url-Tink-PUBLIC-keyset>`. It rejects extra fields, malformed or oversized payloads, noncanonical base64, other key types, keysets with multiple/disabled keys, wrong algorithms and asymmetric private key material.
- The UI displays the computed SHA-256 fingerprint as an **untrusted identity comparison value** and explicitly directs the user to compare it against the TV's **independently displayed** fingerprint. A QR does not by itself authenticate a TV.
- `:app:phone` Android manifest declares **no INTERNET, no CAMERA and no storage permission**. It cannot connect to a TV, send credentials or activate `SourceOnboarding` yet.
- Production TV code, Ktor server, Media3, Room and credential store are unchanged. The new phone app does not depend on the experimental `:local-control:crypto-lab`; only the **qr-contract unit tests** use the lab to generate a genuine valid public keyset.

## Nonclaims and blockers
- **Not real phone-to-TV pairing.** The current TV QR in LC03/#400 points to a plaintext `/pair` web bootstrap and does not encode the `MXLC04:1` public key payload. The phone app therefore cannot complete a real interaction with the currently released TV UI. This is an incremental preparatory preview, not a usable end-to-end feature.
- **Not a production crypto channel.** There is no provider credential entry, no networking, no securely authenticated response or sender identity. Neither the QR parser nor a SHA fingerprint proves that an active LAN attacker was unable to replace the image.
- **Not proof of TV identity.** A malicious TV/image could display another key; human comparison requires TV-origin evidence independent of an untrusted QR.
- The image-import UI is deliberately a limited first iteration, not a full camera-based live scanner or mature mobile design. It does not request unnecessary permissions.
- No immediate alpha/release-signing work and no parental/PIN restrictions.

## Acceptance

1. Exact-head GitHub-hosted host validation, including `:local-control:qr-contract:test`, `:app:phone:assembleDebug`, `:app:phone:testDebugUnitTest` and `:app:phone:lintDebug`.
2. Source isolation: phone application has no INTERNET/CAMERA permission, does not depend on `:local-control:crypto-lab`, Ktor, `SourceOnboarding`, `:core:credentials` or the TV app.
3. QR tests use a real Tink public HPKE keyset from #405; demonstrate different real keys yield different fingerprints; reject malformed inputs. Test no provider URL/cookie/token appears in any UI/log.
4. A later separate implementation will add live camera QR, the matching authenticated TV QR screen, TV-confirmed Ed25519 phone enrollment, validated protected **bidirectional** channel and malicious MITM/replay tests before M3U/Xtream operations are enabled.
5. Device samples recommended, not mandatory. Only GitHub-hosted runners.

## Recommendation after accepting this slice

**Stop adding standalone crypto labs.** Choose the Android-native phone companion as a primary supported path and directly implement the TV key display/phone scan and secure handshake using a high-level reviewed protocol. Maintain the browser panel as pairing-only until independent HTTPS origin/mobile-LNA support can be qualified. Connect `:local-control:onboarding` only after secure replies and session binding are verified.
