# C21 Slice A — Parser Contract

**Status:** accepted for implementation

**Owner:** #352

**PR:** #357

**Baseline:** `9daac297ef4e2c7748e243fc3e912142bbbc6076`

**Reference:** OwnTV `b70af186731fa860da2b99aa05ead5d77f4da927`, OwnTV_Core `630e9c09c80345279e248c336657645300208c09`

## Scope

Slice A is limited to a bounded provider-neutral request-metadata value type plus sanitized M3U/Kodi normalization:

```text
M3U/Kodi per-item syntax
        ↓
bounded syntax parser
        ↓
ProviderRequestMetadata
```

No importer/staging propagation, persistence/schema change, resolved playback propagation, or player/network adapter belongs in #357.

## Existing parser ownership

Preserve current MuxTV behavior:

- option directives are accepted only while an `#EXTINF` entry is pending;
- a locator consumes the pending entry;
- per-entry state resets after emit;
- a new `#EXTINF` replaces an unfinished entry through the existing warning path;
- diagnostics remain secret-free.

## Provider-neutral model

Slice A uses three scopes:

- `defaultHeaders`;
- `manifestHeaders`;
- `segmentHeaders`.

Target-specific values override the same canonical default value only when the corresponding target projection is requested.

The model snapshots caller maps, canonicalizes aliases and duplicates deterministically, enforces bounds, and exposes counts only from `toString()`.

## Header policy

Initial allowlist:

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

`Referrer` canonicalizes to `Referer`; matching is case-insensitive.

Transport-owned/unsafe names remain forbidden, including `Host`, `Content-Length`, `Connection`, `Transfer-Encoding`, `Range`, and `Proxy-Authorization`. Unknown names remain unsupported.

Bounds:

- name <= 64 characters;
- value <= 8,192 characters;
- <= 32 accepted entries across scopes;
- empty values rejected;
- CR/LF/NUL rejected;
- names must satisfy the HTTP token grammar used by the model.

## Syntax mapping

### EXTINF attributes

Existing user-agent and referrer aliases normalize to default metadata while legacy fields remain available during Slice A.

### `#EXTVLCOPT`

Only `http-<accepted-header>` request options are normalized. Other VLC options remain ignored.

### `#EXTHTTP`

Accept a bounded flat JSON object. Only string scalar values whose names pass the header policy are admitted. Malformed objects, non-string values and rejected names produce secret-free typed warnings.

### `#KODIPROP`

Normalize:

- existing simple per-entry user-agent/referrer aliases to default scope;
- `*.stream_headers` / `*.stream_header` to segment scope;
- `*.manifest_headers` to manifest scope.

Header-list values use `Key=Value&Key=Value` with bounded percent decoding.

### URL pipe suffix

Treat a literal `|Key=Value&...` suffix as default request metadata only when at least one assignment is a recognized header. Accepted suffix metadata is removed from the locator. An unrecognized literal pipe remains part of the locator.

## Precedence

Within a scope, later accepted input wins for the same canonical name.

For defaults:

1. EXTINF request attributes;
2. accepted directives in playlist order;
3. recognized URL pipe assignments last.

Per-entry state must never flow to the following entry.

## Invalid input

Malformed/forbidden/unsupported individual metadata is dropped and produces bounded, typed, secret-free warnings. Existing parser hard limits remain authoritative; parser normalization must never bypass model bounds.

## Security invariants

Slice A does not modify `SensitiveHeaderPolicy`, exact-origin policy, redirect behavior, or cross-origin stripping. No raw secret-bearing header value, directive body, locator or query may appear in diagnostics, warnings, reports, traces, screenshots/UI semantics, or generated `toString()` output.

## Corpus acceptance

Extend the existing sanitized `compatibility/m3u` corpus with synthetic `.invalid` fixtures proving:

- VLC normalization;
- simple Kodi normalization;
- flat `#EXTHTTP`;
- Kodi manifest/stream header lists;
- URL pipe parsing and percent decoding;
- duplicate/case precedence;
- pending-entry reset/no leakage;
- malformed/unsupported/forbidden handling;
- redaction probes.

Every parser production change requires observed RED evidence first. #357 is complete only after final Hosted Validation is GREEN on its exact head. Overall C21 remains owned by #352.
