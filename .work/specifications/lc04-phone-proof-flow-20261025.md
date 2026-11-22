# LC04-S3-E4A — one-shot phone-side proof orchestration (JVM, no network privilege)

Status: **candidate, experimental MXW1, not a paired product**.
Issue: #401. Prerequisites: #418-#420. Independent of Android TV lifecycle #421.

## Decision and threat boundary

The phone must distinguish four completely different events:
1. An actual physical TV QR was scanned, yielding a public-only Tink HPKE key.
2. A syntactically valid `TV_CHALLENGE` is received over **untrusted** transport. This is **not** TV authentication.
3. A user is shown the phone fingerprint and may independently approve it using the **TV-local D-pad**. Phone UI cannot assert that approval happened.
4. A valid `SEALED_TV_RECEIPT` authenticates the exact original signed `CHANNEL_PROBE`, its nonce, scanned HPKE TV key and enrolled phone identity. Only this is a cryptographic success signal.

`:local-control:phone-flow` owns the state machine with NEW → OFFERING →
WAITING_FOR_TV → VERIFYING → VERIFIED, or irreversibly CLOSED. One offer,
one proof and one signed probe. Errors, tampering, expiration and close-vs-
reply races fail closed. A missing reply to `PHONE_PROOF` is **not** counted as
a positive acknowledgement; only the original AES-GCM receipt proves success.

Network bytes are carried through an injected `Lc04PhoneFrameExchange`
interface. The client in this slice has **no TCP socket**, IPv4 address,
runtime permission, Android Activity, HTTP fallback, credentials or source
operations; no `:app:phone` dependency or manifest change is permitted.

## Security nonclaims

- Receipt verification is per-attempt evidence, **not** a reusable capability,
  paired account, owner-independent verification or source-permission token.
- No IPTV provider URL, M3U/Xtream password, URL scheme, source import or
  `SourceOnboarding` operation is accepted here.
- The QR's public-key context prevents transcript substitution; an attacker
  can still induce timeouts or malicious failures. Physical TV ownership
  comparison remains a UI/user responsibility.
- JVM-managed Tink private key material cannot be guaranteed to zeroize.
  `close()` revokes the signer and protocol state.

## GitHub-hosted exit criteria

1. Nine JVM hostile tests: real in-process TV Tink crypto, explicit TV
   approval, no-consent denial, forged receipt, dropped proof, wrong TV,
   QR expiry, concurrent revocation and transport exception, plus diagnostics.
2. Build-logic static isolation: production module excludes socket/Android/
   provider dependencies; `:app:phone` remains scan-only, without Internet.
3. Exact-HEAD workflow on `ubuntu-latest`: phone-flow tests, existing
   enrollment/QR/native TCP suites and phone Android assemble.
4. Prior to granting `INTERNET` to the Android phone app, a separate
   reviewed cross-app transport and Android 26/36 malicious-relay harness is
   required. No self-hosted runner or physical device.

## Next

Implement an Android phone adapter that, only after explicit user intent
and approved runtime permissions, supplies an RFC1918 numeric IPv4 endpoint
and `Lc04TcpPhoneExchange` to the offline state machine. Prove fresh phone
signer generation, phone lifecycle cancellation, LAN changes, malicious
relay, no proof-without-TV-approval success, replay and true TV receipt on
the canonical GitHub-hosted Android device matrix. Only then evaluate source
provisioning as a separate capability and security review.
