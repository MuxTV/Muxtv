---
status: FEASIBILITY_ONLY_NOT_ACCEPTED_FOR_CREDENTIALS
last_reviewed: 2026-10-15
issues: [397, 401]
owner: local-control
workflow: .github/workflows/local-control-browser-origin-lab.yml
probe: tools/local-control/browser-probe/run.mjs
---

# LC04-S2-C2A — HTTPS browser origin / LAN reachability feasibility

## Decision at this checkpoint

**DO NOT make a phone M3U/Xtream credential form accessible yet.**

The source code in `:local-control:crypto-lab` (#403–#405) only establishes a JVM-only HPKE/Ed25519/QR experiment. None of those components authenticates the HTML/JavaScript actually running in the phone browser. The currently shipped TV `http://<RFC1918>/pair` page remains plaintext and can be replaced by an active LAN MITM. *Encrypted payloads inside an untrusted web page do not solve page integrity or sender identity.*

This new experiment measures a prerequisite browser property rather than adding another crypto primitive. It does not require or introduce a public domain, production certificate, WebView, credentials, remote proxy, server API or production dependency.

## Fixture implemented

`tools/local-control/browser-probe/run.mjs` launches three desktop Playwright engines on **GitHub-hosted Linux**, one per independent job:

- An ephemeral `https://127.0.0.1:<random>` page generated locally with a **self-signed, TEST-ONLY certificate**. The automated browser uses `ignoreHTTPSErrors=true` *only inside this test*. This is **NOT a deployable phone trust root**.
- A separate HTTP server bound to an actual private RFC1918 IPv4 address of the hosted runner. It serves only a constant nonsecret response, a plain-HTTP context test page and a bounded JSON capability endpoint.
- The fixture enforces exact `Host`, exact HTTPS `Origin`, explicit `X-MuxTV-Lab-Client` marker, `POST` only, JSON schema, max 128 bytes and selective CORS preflight response. Independent direct-request negative tests reject malicious origin, wrong Host and missing marker.
- Each browser MUST show `isSecureContext=true` and `crypto.subtle` present on test HTTPS. The private-LAN HTTP navigation MUST show `isSecureContext=false`. These are **hard, isolated test assertions**.
- For each engine, try an HTTPS-to-LAN HTTP nonsecret `fetch` using `targetAddressSpace:'local'` and automated LNA permission where supported. Record `readable` / `blocked-or-unsupported` / `bad-response`, whether the Request property is exposed, version, and whether Playwright supported the permission API.
- **Do not assert that a readable response means production compatibility.** On Linux the source HTTPS origin is loopback and the certificate is accepted with a test-only exception; normal public HTTPS, real permission prompts, mobile Android/iOS behavior and trust of TV keys are NOT covered.

Each job emits a JSON evidence report under `.work/evidence/local-control-browser-origin/<engine>.json` and uploads it with the repository's bounded immutable artifact action. Failed browser startups or secure-context assertions fail the job. Reachability failures remain an **observed compatibility result**, not a fabricated PASS.

The workflow tests desktop Chromium, Firefox and WebKit engines with pinned Playwright **1.64.1**. WebKit on GitHub-hosted Linux is **not** iOS Safari; Firefox Linux is **not** Firefox Android.

## First GitHub-hosted result — 2026-10-15

Evidence from [exact-head browser matrix #38772109796](https://github.com/MuxTV/Muxtv/actions/runs/38772109796), source `8b27be60c1b0bff95a44654a24590b6297c110c3`. All three desktop jobs finished **SUCCESS** after the test harness was corrected to send an explicitly spoofed `Host` through the raw HTTP client rather than fetch (which normalizes that header). A WebKit-specific unsupported `local-network-access` permission invocation was also removed rather than bypassing origin checks.

| Playwright desktop engine | Version | HTTPS secure context / Web Crypto | Private HTTP secure context | LNA automation | HTTPS-to-LAN HTTP |
|---|---|---|---|---|---|
| Chromium | 156.0.8078.4 | yes / yes | false | explicitly granted by automation | **readable** nonsecret response |
| Firefox | 157.0 | yes / yes | false | not emulated | blocked or unsupported |
| WebKit | 27.2 | yes / yes | false | not emulated | blocked or unsupported |

**Interpretation:** There is no demonstrated secure cross-browser phone flow. The HTTPS page was on loopback with a temporary self-signed certificate accepted by automation. The target was a private address on the same Linux runner, not an actual TV on a user's LAN. Playwright WebKit is not mobile Safari; Firefox Linux is not Android Firefox. No source credentials, passwords, provider URLs or authentication tokens were transferred. The application remains BLOCKED for provider entry from phones.

The run exports one JSON artifact per desktop engine with `productionPhoneCompatibility=NOT_QUALIFIED` and `productionCredentialTransport=NOT_IMPLEMENTED`. A future Chrome desktop result changing to `readable` is **not** a green light to add a phone provider form.

## Browser research evidence

- Chrome's Local Network Access guidance: HTTPS source is required, local-network and loopback permissions differ, and `targetAddressSpace:'local'` can indicate an HTTP private-network target; it does not establish response integrity or server authentication. <https://github.com/GoogleChrome/modern-web-guidance/blob/main/skills/modern-web-guidance/guides/security/local-network-access.md>
- MDN: Local Network Access permission and mixed-content behavior; browser support and feature detection matter. <https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Local_network_access>
- Playwright grants `local-network-access` only on browsers with a supported implementation; automation permission grant cannot prove a human permission prompt. <https://playwright.dev/docs/api/class-browsercontext#browser-context-grant-permissions>
- A relevant Firefox issue describes differences from Chrome in LNA mixed-content relaxation; do not generalize one browser's success. <https://bugzilla.mozilla.org/show_bug.cgi?id=2081728>

## Threat/acceptance matrix

| Requirement | This fixture | Product gate |
|---|---|---|
| HTTPS-origin API surface & WebCrypto | Desktop test-only HTTPS | Real trusted deployed HTTPS origin; no ignored certificate failures |
| Private-address HTTP secure-context denial | Asserted in headless fixture | Browser-specific real phone checks |
| Chrome LNA permission and fetch | Observed only | Android Chrome version + real permission UI required |
| Firefox Android / Safari iOS / Samsung Internet | Not covered | Qualify versions on target phones or specify supported fallback |
| HTML and JS authenticity under active LAN MITM | **Not covered** | Production HTTPS origin immutable asset/version + substitution test |
| QR authenticity from *physical intended TV* | **Not covered** | Canonical key pin, human TV confirmation and substitution tests |
| Sender key/TV key binding | JVM-only #404–#405 | Product flow, physical QR scan, browser-compatible implementation |
| TV-to-phone encrypted/authenticated responses | **Not implemented** | Mandatory before SourceOnboarding connection |
| Secret-free browser evidence | Fixtures contain no IPTV data | Prod privacy, no tokens in URL/query/logs |
| CORS/Host/Origin and request boundaries | Independent nonsecret server fixture tests | Reaudit app server with protected channel; no wildcard CORS |
| M3U/Xtream credential entry | **Prohibited** | Must meet ALL of the above plus authZ and active MITM tests |

## Next decision

One of two paths must be demonstrably supportable without unsafe downgrade:

**A. Browser-first:** control an audited trusted HTTPS frontend origin and static build with pinned integrity, test Android Chrome public HTTPS -> TV LAN permission and ciphertext-only cross-origin requests, confirm whether Firefox Android and Safari iOS can meet equivalent requirements. No provider secrets sent until bidirectional authenticated transport + QR verified TV key + phone sender proof bind together.

**B. Native companion:** if cross-browser HTTPS-to-LAN or offline use cannot be made reliable, prefer a signed Android/iOS phone app with explicit QR key pinning and a reviewed encrypted transport. Installation is a user cost, but avoids pretending a plaintext LAN JS page is trustworthy.

Until a choice passes these gates, keep the shipped local `/pair` **pairing-only**. The browser probe neither grants `ProtectedSourcePrincipal` nor adds `:local-control:onboarding` to `:local-control:server` or `:app:tv`.

Only GitHub-hosted runners; physical TV tests recommended, not a formal blocker. No parental, content-age, PIN or screen-time restrictions.
