# C21 Provider Request Metadata Design

**Status:** accepted for implementation

**Date:** 2026-09-08

**Parent:** #348

**Child:** #352

**Related owners:** #184, #186, #26, networking/security owners

**Baseline:** `MuxTV/Muxtv@9daac297ef4e2c7748e243fc3e912142bbbc6076`

**Reference:** `ahXN00/OwnTV@b70af186731fa860da2b99aa05ead5d77f4da927`, `ahXN00/OwnTV_Core@630e9c09c80345279e248c336657645300208c09`

## 1. Objective

Expand M3U/provider per-item HTTP compatibility beyond the current `User-Agent`/`Referer` pair without allowing parser syntax or unrestricted request metadata to leak into the Media3 adapter.

The accepted direction is an independent MuxTV adaptation:

```text
playlist/provider syntax
        ↓
syntax-specific bounded parsing
        ↓
validated ProviderRequestMetadata
        ↓
immutable staged/persisted variant
        ↓
ResolvedPlaybackRequest
        ↓
request-target projection
        ↓
Media3 / OkHttp
```

Correctness and security dominate performance.

## 2. Existing behavior to preserve

MuxTV already has strong lower-layer contracts:

- `PlaybackRequest` and `PlaybackSessionRequest` own immutable header snapshots;
- Media3 playback uses request-local `OkHttpDataSource.Factory` instances;
- `PlaybackRequestPolicyInterceptor` enforces the playback root and exact-origin cleartext policy;
- `SensitiveHeaderPolicy` strips sensitive headers on cross-origin requests;
- service-owned Media3 playback and recovery remain the only runtime playback owner;
- `PlaybackStartRequest` crossing the MediaSession boundary contains semantic channel/intent data, not resolved provider transport data;
- no raw locator/header values are permitted in logs, Doctor, traces, benchmark evidence, UI semantics or screenshots.

C21 extends metadata compatibility at the existing provider/catalog resolution boundary. It does not introduce another HTTP stack, player, retry owner or Bundle protocol.

## 3. Reference behavior and deliberate deviation

OwnTV_Core recognizes useful real-world syntax:

- `#EXTVLCOPT:http-*`;
- `#EXTHTTP:{...}`;
- Kodi `inputstream.adaptive.stream_headers`, legacy `stream_header`, and `manifest_headers`;
- `url|Key=Value&...`.

OwnTV also canonicalizes common names and gives the URL suffix the final value for duplicate headers.

MuxTV adopts this syntax/precedence evidence but rejects OwnTV's permissive policy where any syntactically valid non-reserved header may pass through. C21 uses a bounded allowlist and MuxTV's existing exact-origin/sensitive-header policy.

## 4. Provider-neutral model

The stable type lives in `core:model`, not in `catalog:ingest` or `player:media3`.

```kotlin
enum class ProviderRequestTarget {
    MANIFEST,
    SEGMENT,
    LICENSE,
}

class ProviderRequestMetadata(
    defaultHeaders: Map<String, String> = emptyMap(),
    manifestHeaders: Map<String, String> = emptyMap(),
    segmentHeaders: Map<String, String> = emptyMap(),
    licenseHeaders: Map<String, String> = emptyMap(),
) {
    val defaultHeaders: Map<String, String>
    val manifestHeaders: Map<String, String>
    val segmentHeaders: Map<String, String>
    val licenseHeaders: Map<String, String>

    fun headersFor(target: ProviderRequestTarget): Map<String, String>
}
```

Semantics:

- `defaultHeaders` apply to MANIFEST and SEGMENT requests;
- target-specific MANIFEST/SEGMENT headers override the same canonical default header only for that target;
- LICENSE receives only `licenseHeaders`; stream/default headers never implicitly flow to a license origin;
- empty metadata is a first-class immutable value;
- metadata equality is based on normalized immutable content, not parser syntax.

C21 reserves the LICENSE target and validates/persists it. Actual DRM descriptor/license URL construction and Media3 DRM configuration remain C22. C21 must not invent DRM configuration simply to exercise a network request.

## 5. Header policy

### Supported canonical header classes

Initial support is intentionally narrow:

```text
User-Agent
Referer
Origin
Authorization
Cookie
X-Api-Key
X-Auth-Token
X-Access-Token
```

Aliases/case normalization:

- header names are matched case-insensitively;
- `Referrer` canonicalizes to `Referer`;
- `http-` is syntax owned by `#EXTVLCOPT` and is removed before header-policy validation;
- accepted output always uses the canonical spelling above.

### Rejected transport-owned/unsafe classes

At minimum:

```text
Host
Content-Length
Connection
Transfer-Encoding
Range
Proxy-Authorization
```

Unknown names are not silently enabled. A new provider header class requires a sanitized compatibility fixture and an explicit policy change.

### Bounds

The normalized metadata contract enforces:

- at most 32 accepted header entries across all four scopes after canonical duplicate replacement;
- header name length <= 64 characters;
- header value length <= 8,192 characters;
- non-empty names and values;
- CR, LF and NUL forbidden in names/values;
- `:` forbidden in names;
- immutable snapshots after validation.

The parser additionally remains subject to existing M3U line/input limits.

Malformed/forbidden individual metadata is ignored with a secret-free typed parser warning. A hard metadata capacity violation is a configured input-bound failure and may terminate the source parse like other M3U hard bounds.

## 6. M3U syntax mapping

### `#EXTINF` attributes

Existing safe `http-user-agent` / `user-agent` and referrer aliases remain supported as DEFAULT metadata inputs for backward compatibility.

### `#EXTVLCOPT`

Only `http-<allowed-header>` is interpreted as request metadata. Other VLC options remain ignored and are never executed.

Examples:

```text
#EXTVLCOPT:http-user-agent=Example/1.0
#EXTVLCOPT:http-referrer=https://portal.invalid/
#EXTVLCOPT:http-origin=https://portal.invalid
#EXTVLCOPT:http-cookie=session=synthetic
```

### `#EXTHTTP`

The body is a bounded JSON object. Only string scalar values whose names pass the header policy are accepted. Nested values, arrays, malformed JSON and unsupported names are ignored/rejected through secret-free warning families; raw body/value text never appears in warnings.

### `#KODIPROP`

Supported request-header properties:

```text
inputstream.adaptive.stream_headers
inputstream.adaptive.stream_header
inputstream.adaptive.manifest_headers
```

Suffix matching may accept the existing legacy `inputstream.*` spelling where safe.

- `stream_headers` / `stream_header` → SEGMENT scope;
- `manifest_headers` → MANIFEST scope.

The value uses `Key=Value&Key=Value` syntax with percent-decoded values.

Other Kodi properties are not executed by C21. DRM properties remain C22.

### URL pipe suffix

A locator may end with:

```text
https://streams.invalid/live/master.m3u8|User-Agent=Example%2F1.0&Referer=https%3A%2F%2Fportal.invalid%2F
```

When the suffix is recognized as header syntax, the persisted locator is the URL before `|` and accepted suffix headers map to DEFAULT scope.

A literal `|` that is not a header suffix must remain part of the locator.

## 7. Deterministic precedence

For the same canonical header in the same scope, later accepted input wins.

Within an entry:

1. `#EXTINF` HTTP attributes establish DEFAULT values;
2. header directives are applied in playlist order;
3. URL pipe DEFAULT values are applied last.

Target resolution then overlays MANIFEST/SEGMENT target-specific values over DEFAULT values.

Example:

```text
#EXTVLCOPT:http-user-agent=A
#EXTHTTP:{"user-agent":"B"}
https://streams.invalid/master.m3u8|USER-AGENT=C
```

DEFAULT `User-Agent` is `C`.

## 8. Persistence

Per-item metadata must survive import, process death and catalog refresh publication. It therefore belongs on the persisted `StreamVariantEntity`, not on an ephemeral parser/request object.

C21 uses Room schema v11 and retains the existing `userAgent` / `referrer` columns for migration/backward compatibility.

New nullable TEXT columns:

```text
requestHeadersDefault
requestHeadersManifest
requestHeadersSegment
requestHeadersLicense
```

Each column uses a deterministic database-owned encoding of one canonical `Key: Value` per line. The model forbids CR/LF in values and `:` in names, so round-trip decoding is unambiguous. Empty maps encode as null.

Resolution rule:

- if any v11 request-metadata column is present, decode and validate `ProviderRequestMetadata`;
- otherwise project legacy `userAgent` / `referrer` into DEFAULT metadata;
- decoder corruption fails closed for metadata: malformed stored metadata is not sent as HTTP headers and must not expose its raw value in errors/diagnostics.

New imports may continue populating legacy UA/referrer columns for compatibility while v11 metadata is authoritative for playback resolution. No credential/ref/token value becomes identity or a diagnostic field.

## 9. Resolved playback contract

`ResolvedPlaybackRequest` carries `ProviderRequestMetadata` in addition to the legacy `requestHeaders` compatibility surface during this focused change.

The two must not become independent sources of truth:

- legacy `requestHeaders` represent DEFAULT headers only;
- candidate production code constructs metadata first and derives the legacy map from `metadata.defaultHeaders`;
- compatibility constructors/tests that only provide `requestHeaders` produce equivalent DEFAULT metadata.

`ResolvedPlaybackRequest.toString()` must not expose header names. It may expose only scope counts and other existing safe facts.

`PlaybackRequest` and `PlaybackSessionRequest` follow the same compatibility rule. Their existing immutable ownership and bounds remain in force.

## 10. Media3 request-target adapter

Known transport types get target-aware data sources without teaching Media3 any M3U/Kodi syntax.

### HLS

Use Media3 `HlsDataSourceFactory` data type information:

- playlist/manifest requests → `headersFor(MANIFEST)`;
- media/chunk requests → `headersFor(SEGMENT)`;
- non-manifest HLS auxiliary data follows the segment/default policy unless a separately typed contract owns it.

### DASH

Use separate data-source factories:

- MPD manifest factory → MANIFEST;
- chunk/media factory → SEGMENT.

### Progressive/raw MPEG-TS

Use `headersFor(SEGMENT)`.

### AUTO/unknown

Do not infer parser/provider syntax. Use the conservative DEFAULT projection until transport classification selects a known path.

### LICENSE

`headersFor(LICENSE)` is part of the normalized request adapter contract. Media3 DRM wiring remains C22 because there is no accepted C21 DRM descriptor/license locator to attach it to safely.

## 11. Redirect and origin policy

C21 does not replace network security policy.

Every concrete HTTP request still flows through:

```text
OkHttpDataSource
  ↓
SecureRedirectInterceptor
  ↓
PlaybackRequestPolicyInterceptor
  ↓
SensitiveHeaderPolicy
```

Consequences:

- same-origin manifest/segment requests retain intended metadata;
- cross-origin requests strip MuxTV-sensitive classes including Authorization, Cookie, Referer, Origin and token headers;
- HTTPS downgrade remains rejected;
- insecure HTTP still requires exact-origin approval;
- per-item metadata cannot authorize a new origin.

## 12. Diagnostics and redaction

Forbidden everywhere outside the actual transport request/persisted variant row:

- raw request-header values;
- Authorization/Cookie/token values;
- raw locator/query values;
- parser `#EXTHTTP` JSON bodies;
- encoded Room metadata content.

Specifically forbidden in:

- logs;
- Doctor;
- Room diagnostics/export summaries;
- traces;
- benchmark JSON/reports;
- screenshot/UI semantics;
- generated `toString()` output.

Safe facts include bounded counts, target kind, disposition/warning enum and booleans such as metadata-present.

## 13. Test-first evidence matrix

### Parser/corpus

Fixtures under the existing #186 corpus cover:

- UA only;
- Referer only;
- Origin only;
- multiple headers;
- duplicate/case variants;
- `#EXTHTTP`;
- Kodi stream headers;
- Kodi manifest headers;
- URL pipe suffix;
- malformed header;
- forbidden header;
- hard count/value bounds;
- redaction probes.

Every production parser change requires an observed RED fixture first.

### Import/database/resolution

Prove:

```text
fixture
  → M3uEntry.requestMetadata
  → CatalogImportEntry
  → StagedCatalogEntry
  → StreamVariantEntity v11
  → ActiveVariantAccessRow
  → ResolvedPlaybackRequest.requestMetadata
```

Also prove v10→v11 migration and legacy UA/referrer fallback.

### Media3/MockWebServer

Deterministic tests cover:

- HLS manifest receives manifest/default headers;
- HLS segment receives segment/default headers;
- exact target override precedence;
- same-origin redirect behavior;
- cross-origin sensitive stripping;
- channel A → B isolation;
- provider A → B isolation;
- mutable caller map cannot change an installed request.

## 14. A/B/C evidence

### A — current MuxTV

- UA/Referer only;
- no `#EXTHTTP`;
- no URL-pipe normalization;
- no manifest-vs-segment metadata distinction.

### B — candidate MuxTV

- validated `ProviderRequestMetadata`;
- bounded allowlist;
- syntax normalization at ingest;
- persistence through active revision;
- target-aware Media3/OkHttp projection;
- existing MuxTV exact-origin/redaction policy preserved.

### C — OwnTV reference

Run equivalent sanitized syntax fixtures against the pinned OwnTV_Core parser/reference behavior. Reference output is behavioral evidence only; it is not linked into the MuxTV production Gradle graph.

Primary result is correctness/security. Performance is secondary and only recorded after the correctness oracle passes.

## 15. Acceptance

C21 favors **ADAPT** only if B demonstrates all of the following:

- providers requiring accepted per-item headers can reach the correct requests;
- exact required metadata reaches exact request targets;
- zero channel/provider metadata leakage;
- zero cross-origin sensitive-header leakage;
- deterministic duplicate/case precedence;
- malformed/forbidden metadata is bounded and secret-safe;
- no arbitrary/unbounded header surface;
- parser syntax remains outside Media3;
- no raw secret-bearing data appears in diagnostics/evidence;
- existing one-player/recovery/exact-origin ownership remains unchanged.

If those gates are not met, the experiment is REJECT or DEFER rather than weakening MuxTV security.