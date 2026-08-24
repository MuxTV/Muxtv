# Secret-bearing data boundary audit — 2026-08-24

Status: runner-free static audit. This document classifies data exposure risk; it does not claim a runtime leak unless executable evidence exists.

Reviewed accepted baseline: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Related owners:

- existing credential/access-ref architecture;
- core network exact-origin/header/redaction policy;
- #30 Doctor diagnostics;
- #31 release evidence;
- #191 WorkManager typed diagnostics;
- #192 tracing;
- #193 OkHttp phase timing;
- #186 compatibility-corpus redaction fixtures.

## Core rule

MuxTV needs to distinguish three different things that are often incorrectly called “secret” as one category:

1. **credential secret** — password/token/key/header value that must remain behind a dedicated secret boundary;
2. **sensitive locator/metadata** — a playlist/stream/catch-up URL, query, User-Agent/referrer or provider metadata that may be required for correct operation and may legitimately exist at runtime/persistence, but must not leak into diagnostics/UI/evidence;
3. **opaque safe reference** — an identifier such as `credentialRef` whose value grants no secret by itself and is safe only if it remains non-credential-bearing.

Redaction policy must be based on the data class, not on string shape alone.

---

## S1 — credential material

### Examples

- provider username/password;
- bearer/API/session token stored as credential content;
- Authorization header value;
- Cookie value;
- release-signing private key/keystore passwords.

### Allowed locations

Only the accepted credential/signing secret store boundaries and short-lived request construction where required.

### Forbidden locations

- Room ordinary source/provider metadata rows;
- logs;
- exceptions copied verbatim into durable diagnostics;
- trace attributes;
- WorkManager output/progress;
- UI semantics;
- screenshots;
- release evidence/SBOM;
- cache identity;
- stable channel/source identity.

### Existing evidence

Credential unit tests cover AEAD, envelope codec, credential primitives and store contract. Network tests separately cover sensitive header policy.

### Future regression rule

Every new integration/provider adapter must prove that secrets are represented by access refs/capability-scoped retrieval rather than copied into provider domain DTOs for convenience.

---

## S2 — `credentialRef`

### Current repository fact

`SourceEntity` persists `credentialRef`, while its `toString()` exposes only `credentialRefPresent` and `activeRevision`.

### Classification

`OPAQUE_REFERENCE`, conditional on the invariant that the reference itself does not embed credential content.

### Required invariant

- stable opaque identifier;
- no username/password/token/header/query embedded in it;
- not usable as an HTTP credential by itself;
- diagnostics may expose only presence or a deliberately non-sensitive stable classification, not the raw ref unless a concrete debugging need is approved.

This is safer than persisting credentials directly in `sources` and should remain the architecture.

---

## S3 — source/playlist URL

### Classification

`SENSITIVE_LOCATOR`.

Even when a URL has no explicit `token=` parameter, path/query/host can reveal provider/account/source information. A signed URL is additionally credential-like for its lifetime.

### Runtime use

May be required by source acquisition/onboarding.

### Diagnostics/evidence rule

Never persist or display the raw full locator. Use:

- stable source ID;
- exact-origin policy result where safe;
- scheme/transport category;
- redacted host/origin only if the accepted product policy considers it non-sensitive;
- typed failure code.

Do not hash a full secret URL and assume the hash is automatically safe identity. Stable hashes can still correlate user/provider secrets and can preserve credential-derived identity indefinitely.

---

## S4 — stream variant `locator`

### Current repository fact

`StreamVariantEntity` persists `locator`, `userAgent` and `referrer` because playback needs request-level source information.

### Classification

- `locator`: `SENSITIVE_LOCATOR`, possibly `CREDENTIAL_EQUIVALENT` when signed/tokenized;
- `userAgent`: `SENSITIVE_REQUEST_METADATA` because provider/user-specific values can occur;
- `referrer`: `SENSITIVE_LOCATOR/REQUEST_METADATA`.

Persistence is not automatically a defect: the playback product may require durable variant information. The security requirement is that these fields do not escape their intended storage/request boundary.

### Mandatory rules

- `toString()`/diagnostics never dump the entity raw if that reveals fields;
- no raw locator used as cache/health/trace identity;
- no raw query/path in Doctor;
- headers/metadata from variant A cannot leak into variant B;
- expiry/refresh semantics for signed locators remain provider/source-owned rather than hidden cache behavior.

### Future review question

If provider adapters introduce short-lived signed variants, assess whether durable raw locator persistence is still appropriate for that adapter or whether an opaque resolver/access ref is required. Do not redesign current M3U persistence without a concrete need.

---

## S5 — M3U `M3uEntry`

### Current repository fact

The parser model carries operational values including locator, tvg/group metadata, catch-up fields, User-Agent/referrer and arbitrary attributes. Its `toString()` deliberately reports presence/counts and redacts display name/locator and provider metadata.

### Classification

The object is a `SENSITIVE_TRANSIENT_PARSE_MODEL`.

### Rules

- parser errors/warnings remain structural and line-number based rather than echoing raw lines;
- generic logging must not stringify attributes/maps separately around the safe model `toString()`;
- test fixtures use synthetic `TEST_*_SECRET` values to prove redaction;
- compatibility provenance notes must never include a private playlist sample.

---

## S6 — catch-up source/template

### Current repository fact

`ProviderChannelEntity` persists:

- `catchupMode`;
- `catchupSource`;
- `catchupDays`;
- `catchupCorrection`.

### Classification

`catchupMode/days/correction` are generally provider metadata.

`catchupSource` is `SENSITIVE_TEMPLATE`, because a dialect may include locator structure, account identifiers or token material.

### Rules

- do not expose raw template in Doctor/UI/trace;
- future `PlaybackIntent`/catch-up resolver must keep provider-specific template expansion behind provider/catalog resolution rather than Compose/Media3;
- generated catch-up URL receives the same handling as a sensitive stream locator;
- diagnostics report typed catch-up capability/resolution failure only.

This is a key reason not to model catch-up support as simple UI string substitution.

---

## S7 — EPG URLs and image/logo references

### Classification

Provider-supplied EPG/source URLs are `SENSITIVE_LOCATOR` by default.

Artwork/logo URLs are lower sensitivity only when they are demonstrably public and credential-free. The application should not assume every `logoUrl` is public merely because it is image-shaped.

### Rules

- no automatic logging of full URLs;
- credential-bearing logo requests must not be introduced into generic image loading without an explicit security owner;
- UI semantics should describe image content/state, not its locator;
- release screenshots/evidence must contain no provider-private location data.

---

## S8 — channel/programme/user-facing metadata

Examples:

- channel display name;
- group title;
- programme title/description;
- custom user channel name;
- favorites/recent history.

### Classification

`PRIVATE_USER/PROVIDER_CONTENT`, not authentication secret.

### Consequence

It may legitimately appear in the product UI but still should not automatically enter telemetry, traces or public CI/release artifacts.

This distinction matters: “not a token” does not mean “safe to upload publicly”.

### Evidence rule

Automated screenshots/reports intended for repository/public artifacts should use deterministic synthetic catalog/EPG data unless an explicitly private evidence store is adopted.

---

## S9 — profile/source/channel IDs

### Classification

Usually `OPAQUE_DOMAIN_ID` if generated independently of sensitive locator/content.

### Safe uses

- correlation inside a local diagnostic session;
- revision/generation ownership;
- database relations;
- test assertions.

### Caution

If an ID is derived directly from a raw provider URL/token/header, it inherits sensitivity/correlation risk. Identity derivation must therefore remain documented and secret-independent.

---

## S10 — HTTP headers

### High-risk headers

At minimum treat Authorization/Cookie and provider credentials as secrets. Provider-specific headers may also be sensitive even when their names are uncommon.

### Existing architecture/tests

Core network has explicit sensitive-header, redirect and request-policy tests.

### Cross-origin rule

Sensitive headers must not cross an origin boundary unless the accepted explicit policy says so. Redirect handling must be security logic, not automatic client convenience.

### Observability rule

#193 may record timing phases but never header names+values as generic request dumps. If a header category is needed diagnostically, record a safe boolean/category such as `credential_headers_present=true`, not values.

---

## S11 — URI diagnostics

### Existing architecture

`RedactedUri` and associated tests provide an explicit URI redaction boundary.

### Rule

All new diagnostics that need network identity should consume a redacted/safe projection rather than each subsystem inventing `substringBefore('?')` style redaction.

Dropping only the query string is insufficient because credentials/account IDs can exist in path/user-info/fragment/host.

---

## S12 — exceptions

### Classification

`UNTRUSTED_DIAGNOSTIC_INPUT`.

Third-party/HTTP/parser/OS exception messages can include:

- URL/path;
- request details;
- provider response snippets;
- local file paths;
- device/environment identifiers.

### Rule

Durable/public diagnostics store a typed reason code + safe bounded metadata, not arbitrary `Throwable.message`/stack text.

Raw stack traces may remain in deliberately local developer logs if the debugging policy accepts them, but they must not be promoted to public release evidence by default.

This rule is especially important for #191 WorkManager failure hooks.

---

## S13 — WorkManager progress/output/input

### Classification

Potentially durable/system-observable state.

### Rule

Do not serialize credentials/raw source locator/provider headers into WorkManager `Data` merely to pass them between scheduling and execution. Pass stable source/run/access references and resolve sensitive data at the owning boundary.

#191 diagnostics should map failures to stable typed codes with only bounded secret-safe context.

---

## S14 — traces / Perfetto

### Classification

Developer evidence that can be exported and shared.

### #192 rule

Trace names/attributes may include:

- stable operation family;
- bounded safe duration/counters;
- transport/category;
- revision/generation/result family.

They must not include:

- raw source/stream/catch-up URI;
- headers;
- tokens;
- channel/programme titles;
- private source names;
- unrestricted exception text.

Use trace evidence to locate time, not to reconstruct the user's IPTV account.

---

## S15 — measurements / CI artifacts

### Classification

Potentially long-lived and accessible outside the device.

### Rule

Every artifact uploader/evidence generator assumes outputs can become public to repository collaborators. Use synthetic fixtures and secret-safe structured evidence.

Before adding a new artifact type, ask:

1. can this contain logcat?
2. can logcat contain a raw URI/header/exception?
3. can screenshots contain private channel/provider data?
4. can Room/DB dumps contain persisted locators?
5. can Perfetto string tables contain sensitive trace attributes?

If yes, the artifact needs a sanitization/allowlist policy or must remain local/private.

Never upload a raw production Room database as generic debug evidence.

---

## S16 — release signing and SBOM

### Signing

Private key/keystore/passwords remain outside repository and evidence. Only certificate fingerprint belongs in provenance.

### SBOM

Dependency/component data is safe; local secret paths, repository credentials and provider data are not SBOM inputs.

See `docs/release/2026-08-24-signing-sbom-provenance-contract.md`.

---

## Boundary matrix

| Data | Runtime | Room | Request | UI | Doctor | Trace | CI artifact |
| --- | --- | --- | --- | --- | --- | --- | --- |
| credential secret | owner only | secret store only | required scope only | never | never | never | never |
| `credentialRef` | yes | yes | resolver input | generally no | presence only | opaque category only | synthetic/opaque |
| source URL | yes | only accepted owner | yes | sanitized review only | redacted | never raw | never raw |
| stream locator | yes | current variant model | yes | never raw | never raw | never raw | synthetic only |
| UA/referrer | yes | current variant model | yes | never | presence/category | never raw | synthetic only |
| catch-up template | resolver only | current provider model | generated request | capability only | typed state | never raw | synthetic only |
| channel/programme title | yes | yes | maybe protocol data | yes | avoid by default | avoid | synthetic only |
| domain ID | yes | yes | correlation if needed | internal | safe opaque where useful | safe opaque where useful | synthetic/opaque |
| exception text | transient | never raw diagnostic | n/a | mapped copy | typed code | typed code | local-only unless sanitized |

“Room yes” means the accepted current model may persist that data; it does not mean it is safe to dump/export.

---

## Strong existing protections identified statically

- credential store/AEAD contracts exist;
- `SourceEntity.toString()` does not expose raw credential ref;
- M3U models have secret-safe string representation;
- `RedactedUri` boundary exists;
- exact-origin/redirect/sensitive-header policies have dedicated unit tests;
- source-entry and player HTTP approval Android tests exist;
- compatibility corpus uses explicit synthetic secret-shaped values.

These should be reused rather than replaced with a generic logging/redaction framework.

---

## Highest-priority future security tests

After executable host returns, prefer small negative contracts:

1. #191 WorkManager diagnostic mapper rejects/does not persist raw exception URL/token/header text;
2. #192 trace API does not expose an overload accepting arbitrary raw metadata maps/strings from product call sites;
3. #193 EventListener evidence contains timings/categories but no raw URL/path/query/header values;
4. #186 manifest-driven secret fixture cannot appear in parser model diagnostic output;
5. release evidence collector rejects known secret markers before publication where feasible;
6. physical/device screenshot fixtures use synthetic content for shareable artifacts.

Do not build a heavyweight DLP subsystem for MVP alpha; enforce narrow typed boundaries and executable redaction contracts.

## Static concerns to keep visible, not label as vulnerabilities yet

- raw stream locator/UA/referrer are persisted by the current variant model because playback needs them;
- `catchupSource` can be sensitive depending on provider dialect;
- channel/programme content is private even when not credential material;
- arbitrary exception/logcat/artifact output can bypass otherwise safe model `toString()` methods.

These are **review boundaries**, not claims of observed leakage.

## Stop condition while runner is unavailable

No production credential/network/database/logging change is authorized by this audit alone. The next code changes should begin with focused negative tests at #191/#192/#193/#186 when executable RED can be observed.