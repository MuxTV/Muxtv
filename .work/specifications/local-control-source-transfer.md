---
status: implementation_partial
last_reviewed: 2026-10-15
owners: [local-control, catalog-onboarding, security]
issues: [397, 401]
---

# LC04 — Phone-to-TV source transfer

## Acceptance state

| Layer | State | Authority |
|---|---|---|
| LC01 ephemeral TV pairing | In main (#398) | `:local-control:core` |
| LC02 bounded LAN pairing transport | In main (#399) | `:local-control:server` |
| LC03 TV QR + unprivileged phone pairing page | In main (#400) | `app:tv` + server bootstrap |
| LC04 TV-side guarded source operations | **Implemented in current draft only** | `:local-control:onboarding` |
| LC04 verified protected channel | **NOT IMPLEMENTED** | #401 |
| LC04 phone M3U/Xtream form and endpoints | **NOT IMPLEMENTED** | #401 |

The currently served `http://<private-ip>:<port>/pair` page is **not** a protected source-transfer channel. A QR fragment prevents the proof from being sent in the initial HTTP GET, but **does not** authenticate the page or protect subsequent POSTs. A hostile LAN node can replace its JavaScript and observe exchanged values. RFC1918-only binding, Host/Origin validation, rate limits and TV consent mitigate other attacks; none adds TLS.

MDN: [Secure Contexts](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Secure_Contexts), [SubtleCrypto](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto). Secure contexts include trusted HTTPS and loopback, not ordinary `http://192.168.x.y` on another device.

## Boundary implemented in this slice

```text
(FUTURE independently authenticated protected phone channel)
    |
    | grants a module-internal ProtectedSourcePrincipal, never from /pair
    v
:local-control:onboarding
    | AuthN for every operation: PairingAuthority SOURCE_CREATE
    | one opaque SourcePreparationHandle per paired session, in-memory only
    | strict byte bounds / no phone-set insecureHttpApproved
    | secret-free SourceStagingResult
    v
:catalog:api SourceOnboarding
    prepare(SourcePreparationRequest.M3u / Xtream)
    activate(handle, sourceName)
    cancel(handle)
    v
Existing catalog onboarding, provider networking, durable preparation and credential store
```

**No new API routes, no SessionPrincipal factory available to Ktor, and no connection from `:local-control:server` or TV Activity to `:local-control:onboarding` are permitted in this PR.** This is intentionally not a remotely accessible feature.

Input bounds: M3U locator or Xtream endpoint ≤4096 UTF-8 bytes; username ≤256, password ≤2048; source name ≤120 UTF-8 bytes with no controls. The stable `SourceOnboarding` port remains the final authority for provider validation, permissions and network behavior.

Phone cannot set `insecureHttpApproved=true`: if existing onboarding requests insecure-provider approval or Android Local Network permission, the TV-owned consent/permission workflow must handle it separately. The current adapter reports `TV_HTTP_APPROVAL_REQUIRED` and `LOCAL_NETWORK_PERMISSION_REQUIRED`, but **does not** authorize or retry on the phone's behalf.

Results contain neither raw provider credentials, normalized URLs, SourcePreparationHandle nor TV bearer. Prepared handles are kept only in memory; revocation checks occur before/after prepare and activate, and the TV lifecycle can cancel handles after a revoked session. No claim that revocation instantaneously rolls back an already committing activation is supported: source activation remains subject to existing durable transactional semantics. A cleanup-pending result is reported, not silently called success. If a paired session is revoked while activation is committing, `ACTIVATION_OUTCOME_UNKNOWN` is returned rather than suggesting a rollback or safe automatic retry.

## Threat model and fail-closed cases

| Attacker or failure | Required control | Current status |
|---|---|---|
| Passive same-LAN observer | Authenticated encrypted transport; no provider secrets on HTTP | **Open #401** |
| Active LAN MITM replacing `/pair.js` | Independent secure client-origin identity + channel binding | **Open #401** |
| Stolen pairing bearer | Short life, TV revoke, independent protected-channel authentication | Partial; **not enough for provider API** |
| DNS rebinding / hostile Origin | Numeric private binding, exact host/origin, protected channel server identity | Existing LAN guard; protected identity open |
| CSRF / cross-site connection | Exact Origin, custom marker, no token in ambient cookies, guarded cryptographic context | Existing LAN guard; crypto binding open |
| Replay, duplication, request mixing | One pending handle per authorized session, eventual AEAD nonce/counter rules | Staging done; nonce/counter open |
| Unauthorized insecure provider HTTP | Explicit TV consent required by existing SourceOnboarding | Adapter refuses phone approval |
| Android LAN permission missing | Existing Activity TV-owned permission gate | Adapter reports permission requirement |
| Session revoked during provider I/O | Recheck authority, cancel nonactivated prepared handle | Partial; in-flight activation rollback not guaranteed |
| Process death with prepared transaction | Existing onboarding durable pending registry/cleanup, not a duplicate persistence layer | Handle remains opaque / in-memory |
| Oversized source/credentials | UTF-8 byte limits before onboarding | Implemented + JVM tests |
| Provider error messages containing tokens | Secret-free result enum; no echo to phone | Implemented |

## Protected transport acceptance criteria for #401

A future solution is eligible for credentials only when all conditions hold:

1. The phone validates the **actual TV identity** out of band; the server validates the phone and binds the negotiated channel to a specific approved TV pairing. No silent acceptance of self-signed certificates or trust-on-first-use after untrusted HTML.
2. Page and JavaScript authenticity is protected, not merely credential payloads. The method works in supported browsers according to real evidence, or explicitly selects a native companion when browser constraints cannot be met.
3. Peer, channel identity, session, operation, sequence/counter, nonce and authenticated ciphertext are tied together; no useful plaintext appears in URL path/query, origin logs or crash reports.
4. Exact request-size/deadline/CSRF/replay/MITM tests run on GitHub-hosted. The threat matrix includes passive and active LAN adversaries, hotspot and network changes, expired/revoked and cloned sessions.
5. Only after passing (1–4) can the protected transport issue `ProtectedSourcePrincipal` and dispatch existing adapter operations. A security review must additionally verify that the prior bearer from plaintext pairing cannot alone authorize protected source commands.

Physical-TV tests are recommended, never a formal release blocker. No parental, age, PIN or watch-time restrictions.
