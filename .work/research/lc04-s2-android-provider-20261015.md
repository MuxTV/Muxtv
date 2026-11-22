---
status: TESTS_CREATED_NOT_YET_QUALIFIED
last_reviewed: 2026-10-15
issue: 401
module: :local-control:crypto-android-compat
---

# LC04-S2-D — Android API26/API36 cryptographic provider qualification

## Reason

Previous #403–#405 LC04 HPKE/Ed25519/QR experiments use JDK 17 JVM unit tests. A successful Linux-JVM `KeyPairGenerator.getInstance("Ed25519")` / `Signature.getInstance("Ed25519")` does not imply the same JCA algorithms exist on **Android 26**. Android lists the platform Ed25519 `Signature` from **API 33**. Product TV support remains API26; quietly wiring the JVM-only scheme into TV would risk a runtime failure.

Sources:
- [Android Signature — supported APIs](https://developer.android.com/reference/java/security/Signature)
- [Android KeyPairGenerator — supported APIs](https://developer.android.com/reference/java/security/KeyPairGenerator)
- [Tink digital signature primitive](https://developers.google.com/tink/digital-signature)
- [Tink hybrid encryption](https://developers.google.com/tink/hybrid)

## Implemented test design

An isolated `:local-control:crypto-android-compat` Android library with **no production source code, no INTERNET permission, and no reference from TV or LAN server**. It exists solely to run instrumentation on a GitHub-hosted Android emulator. It includes the actual `:local-control:crypto-lab` as an *androidTest-only* dependency:

1. Tink RFC9180 X25519/HKDF-SHA256/AES-256-GCM: generate ephemeral receiver, encrypt a **nonsecret** constant and decrypt on target Android.
2. Serialize a real TV HPKE public key into the #405 canonical QR text, parse and encrypt using the parsed key.
3. Tink's Ed25519 `PublicKeySign` and `PublicKeyVerify` primitives: generate ephemeral Ed25519 keyset, validate valid signature and reject mutated data **on API26 and API36**, independent of platform JCA Ed25519 support.
4. Probe Android JCA Ed25519 availability. API33+ must support the platform signature; API26 cannot be assumed to support it. If a provider happens to supply it, check sign/verify rather than treating mere `getInstance` as proof.

The workflow `.github/workflows/local-control-android-crypto-compat.yml` runs a separate bounded Android emulator job for **API26** and **API36**, verifies exact source SHA, uses existing repository JDK/KVM/ADB retry setup and the existing fail-closed XML test result validator. The required aggregate gate accepts only success on *both* APIs. No self-hosted runner or real TV is required.

These tests validate Android cryptographic primitives and QR representation in isolation; they do not authenticate a real browser, establish a TV->phone session, test a phone camera, or protect a credential in transit. The current TV HTTP `/pair` page remains pairing-only.

## Outcome and decision rules

- Until exact-head CI is green, status is **NOT QUALIFIED**.
- If Tink Ed25519 or HPKE fails on API26, **do not** ship JVM JCA Ed25519 integration and do not weaken the minimum TV API promise. Investigate runtime/provider support or select a reviewed mutually authenticated TLS alternative.
- Even if everything passes, **do not** connect `:local-control:crypto-lab` to production `app:tv` or `:local-control:server` until the trusted client application, QR key identity, authenticated bidirectional response and active MITM gate from #401 pass.
- Do not use crypto matrix SUCCESS as proof of signed or encrypted phone M3U/Xtream provisioning.
- The next product-focused slice should use this evidence to decide a native companion transport versus browser frontend, without adding a second credentials repository.

No parental restrictions, no mandatory physical TV evidence, and no release-signing work are part of this slice.
