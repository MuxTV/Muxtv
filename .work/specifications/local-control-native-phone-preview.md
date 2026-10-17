---
status: PARTIAL_OFFLINE_PREVIEW_ONLY
last_reviewed: 2026-10-17
issue: 401
depends_on: [398, 399, 400, 402, 403, 404, 405, 406, 407]
module: :app:phone
---

# LC04-S3-A: standalone Android phone QR scanner (offline preview only)

## Problem and product boundary

The TV's existing **LC03 QR** links to `http://<private-IP>/pair#proof`. That page is **plaintext and untrusted against on-LAN active MITM**; it cannot safely accept M3U/Xtream credentials.

A *different* experimental **LC04 key QR** was specified by the isolated laboratory under `:local-control:crypto-lab`:

```
MXLC04:1:<32-char-TV-invitation-hex>:<32-char-pairing-hex>:<canonical-public-HPKE-keyset-base64url>
```

This slice produces the first native Android phone-only UI that can **scan that LC04 key QR** with CameraX/ZXing and **parse and show the fingerprint of the actual public key** with the already tested Tink QR validator.

**Critically, the TV application does NOT yet show this key QR in its regular LC03 screen.** The existing pairing QR is a DIFFERENT format and must be ignored by the new phone scanner. This is a standalone offline UX/build milestone, not end-to-end phone pairing.

## What is implemented

- Independent `:app:phone` Android application ID `app.muxtv.phone.preview` (not a TV launcher). No changes to the TV `:app:tv` app and no manifest merge with it.
- Only **CAMERA** permission. Manifest denies backup and cleartext traffic, declares no INTERNET, ACCESS_LOCAL_NETWORK, storage, notifications, service or content provider. No second credential store, no network client.
- CameraX stable 1.6.2 with lifecycle-managed preview and latest-frame-only image analysis; CPU-only ZXing decoding of camera Y-plane luminance with row/pixel stride and 0/90/180/270-degree rotations. Each ImageProxy is closed in `finally`; decoded QR text is not logged/persisted.
- Only LC04 `MXLC04:1:` QR is accepted; reuses current `LabTvQrKeyBinding.parse` public-only keyset validation. Scanner stops on a valid key. UI displays SHA-256 fingerprint and prominently states **TV authenticity NOT verified**.
- No insecure QR URL handling, no clipboard ingestion, no API routes, no `SourceOnboarding` dependency, no phone credential form, no pairing token transfer.
- Screenshots disabled via `FLAG_SECURE`. Activity CameraX lifecycle cleanup and explicit retry.
- JVM tests synthesize actual TV HPKE public key QR image, decode luminance+rotation, reject old HTTP pairing QR, oversized/noncanonical input and malformed frames.
- GitHub-hosted dedicated `.github/workflows/local-control-native-phone-qr-preview.yml` builds debug APK, runs unit tests, checks exact SHA, and inspects merged APK permissions for CAMERA and **absence of INTERNET/local network/storage**. Uploads bounded nonsecret evidence. No extra TV AVD identities, self-hosted CI or physical device gate.

## Security conclusions

**A syntactically valid key QR is not proof of an authentic television.** This UI only shows a fingerprint. An active attacker can display another QR or substitute a different TV page. There is no validation that the intended TV showed the key, no TV-side key display, no phone enrollment/transcript verification, no authenticated TV replies and no accepted internet or local transport.

The existing JVM-only `:local-control:crypto-lab` is included here **only for offline parsing**. Its `LAB_ONLY` disclaimers are still binding; this app is a demonstration, not a security-reviewed distributed cryptographic client.

Physical camera UX has not been verified: GitHub-hosted APK build and JVM QR tests are necessary but do not prove focus/camera behavior on a user's handset. A real phone test remains recommended, not a mandatory release blocker. **No provider credentials may enter the companion.**

## Next acceptance stages

1. TV displays an **ephemeral actual HPKE public-key QR** in a dedicated LC04 route without replacing or weakening LC03; provide a TV/phone human-verification ceremony that the real devices can check (not a bare unauthenticated hexadecimal string).
2. Bind a real phone enrollment public key to user confirmation and a **reviewed, bidirectional, authenticated encrypted transport**. TV and phone both verify the pairing transcript, expirations, revocations, request/reply nonces and sequence. Hostile-LAN MITM tests mandatory before accepting any credentials.
3. Connect that transport to accepted `:local-control:onboarding`, preserving the single `SourceOnboarding` API, existing permission/security decisions and credential storage. Only then add phone M3U/Xtream entry and live source activation. No fallback over `http://.../pair`.
4. Decide whether the native companion will be an installable production app after the channel is accepted; this preview has no user distribution and is not an alpha release.

## References

- Issue #401: https://github.com/MuxTV/Muxtv/issues/401
- Canonical QR key lab #405: https://github.com/MuxTV/Muxtv/pull/405
- Android API26/API36 crypto provider evidence #407: https://github.com/MuxTV/Muxtv/pull/407
- CameraX stable release 1.6.2: https://developer.android.com/jetpack/androidx/releases/camera
