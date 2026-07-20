# Network foundation — self-review

**Branch:** `feat/network-foundation`  
**Base:** `main`  
**Verified head:** `bd095a9a35eda84f132d27e0a79015686ba0a645`  
**Self-hosted run:** `29768076145`  
**Mode:** `Full`  
**Result:** passed

## Implemented boundary

- Added `core:network` using OkHttp BOM/client and MockWebServer3 `5.3.0`.
- Added `SourceUrlPolicy` for remote HTTP(S) source URLs, explicit insecure-HTTP approval, user-info rejection, fragment rejection and encoded control-separator rejection.
- Added `RedactedUri` so credentials and sensitive query values are not emitted by diagnostics or exceptions.
- Added `RedirectPolicy` with a five-hop budget, relative URL resolution, HTTPS-to-HTTP downgrade rejection and cross-origin header disposition.
- Added `SensitiveHeaderPolicy` to remove authorization, cookies, origin/referrer and known token headers only when the redirect changes origin.
- Added `SecureRedirectInterceptor` as an application interceptor for GET/HEAD source requests. Built-in OkHttp redirects remain disabled.
- Added typed `SourceRequestContext` for insecure-transport approval and response-size limits.
- Added shared `MuxTvHttpResources` and specialized source/playback clients derived through `newBuilder()` so dispatcher and connection pool remain shared while timeout policies differ.
- Added independent compressed and decoded response limits. A network interceptor observes bytes before transparent gzip decoding; an application interceptor observes bytes after decoding.

## Reference adaptation

### Official OkHttp / MockWebServer3

Adoption mode: `adapt`, public APIs only.

- Retained one shared transport owner and derived clients with `newBuilder()`.
- Explicitly disabled `followRedirects` and `followSslRedirects`; MuxTV applies stricter redirect policy than the OkHttp default.
- Used typed request tags for per-source policy context.
- Used application interception for the redirect loop so one call deadline spans the complete chain.
- Used MockWebServer3 for same-origin, cross-origin, chunked and gzip behavior tests.
- No `okhttp3.internal` API is imported.

### Jellyfin Android TV

Adoption mode: `architecture-reference`.

- Adapted the dedicated OkHttp-backed Media3/network factory direction and separate playback timeout profile.
- Did not copy Koin modules, SDK-specific options or player plugins.

### StreamVault IPTV

Adoption mode: `clean-room`, `test-corpus`, `anti-pattern`.

- Adapted shared base-client resources, per-purpose timeout profiles, unusual IPTV headers and redacted request-shape testing.
- Rejected trust-all TLS, disabled hostname verification, unconditional redirects and credential-bearing logging.

### Android / Media3

Adoption mode: `architecture-reference`.

- `core:network` exposes OkHttp clients and policy types only.
- Media3 `OkHttpDataSource.Factory` remains owned by `player:media3` in the playback work package.
- Cleartext and local-network product approvals remain explicit application policy rather than a silent transport fallback.

## Security review

1. No unsafe TLS bypass exists.
2. HTTPS-to-HTTP redirect downgrade is rejected even when an unrelated HTTP source was previously approved.
3. Cross-origin redirects remove credentials before the second request is sent.
4. Redirect exceptions contain only a redacted current URL.
5. Source bodies are bounded both before and after decompression.
6. Intermediate redirect responses are closed before another `chain.proceed` call.
7. Redirect execution is restricted to GET and HEAD; request bodies are never replayed.
8. HTTP remains possible for real IPTV providers only through typed explicit approval.

## Verification evidence

Artifact: `self-hosted-validation-29768076145-1`

The generated manifest reports `status: passed` for:

- Gradle/toolchain inspection;
- build-logic tests;
- configuration cache creation and reuse;
- pure Kotlin tests;
- Android unit tests including `core:network`;
- debug APK assembly;
- Android lint including `core:network`;
- release APK assembly.

## Deferred by design

- Android Keystore credential storage and credential application;
- DNS/private-address approval enforcement, which must be bound to persisted per-source policy rather than a process-global switch;
- Media3 data-source creation;
- M3U/XMLTV parsing and immutable revision activation;
- image-client integration until Coil is actually introduced;
- retry/failover policy, which belongs to source refresh and playback orchestration rather than the raw transport client.

These are separate work packages so the current PR remains reviewable and its security invariants remain directly testable.
