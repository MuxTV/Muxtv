# Network foundation reference matrix

Last reviewed: 2026-07-20

This matrix ranks sources by technical usefulness, independent of license. Adoption still records provenance and uses one of: `adapt`, `clean-room`, `test-corpus`, `architecture-reference`, `anti-pattern`.

## Selected library baseline

| Component | Version | Decision | Reason |
|---|---:|---|---|
| OkHttp BOM / client | `5.3.0` | selected | One maintained HTTP stack for source, playback and image clients; HTTP/2, pooling, cancellation and interceptors. |
| MockWebServer3 | `5.3.0` | selected, test-only | Deterministic redirect, truncation, timeout, gzip and header tests. |
| Media3 OkHttp data source | `1.10.1` | deferred to player integration | Consume the playback client from `core:network`; do not couple the network foundation to Media3. |
| Coil network OkHttp | `3.5.0` | deferred to channel browser | Add only when an image loader exists; derive its client from shared resources. |
| Retrofit / Moshi / Gson | — | rejected for Phase 01 | M3U/XMLTV ingestion does not require an API schema layer. |
| Cronet / HttpEngine | — | rejected for the baseline | Fire TV and API 26 support, deterministic policy sharing and one connection-pool strategy are higher priority. Revisit only with measured playback evidence. |

## Repository evidence

### `square/okhttp` — selected library and primary API reference

- Artifact baseline: `5.3.0`.
- Use public `OkHttpClient`, `Dispatcher`, `ConnectionPool`, `Interceptor`, `HttpUrl` and MockWebServer3 APIs only.
- Never import `okhttp3.internal` packages.
- Prefer clients derived with `newBuilder()` so the dispatcher, pool and route database remain shared.

### `jellyfin/jellyfin-androidtv@ee4a20a2b577d4ccd750dee429255a2466b7ae2c` — production playback reference

Adoption: `architecture-reference`, `test-corpus`.

Useful:

- inject a dedicated `HttpDataSource.Factory` into Media3;
- playback timeouts differ from metadata/source timeouts;
- live TV must not inherit a short global request timeout;
- player/session lifecycle is separate from API networking.

Do not copy Koin or Jellyfin-specific playback plugins.

### `Davidona/StreamVault-IPTV@593333714c43cb1802e14a1452cd9f5906e0a286` — IPTV behavior corpus

Adoption: `clean-room`, `test-corpus`, `anti-pattern`.

Useful:

- line/control-separator injection tests, including double encoding;
- source URL and stream-entry URL are different policy domains;
- real IPTV often requires HTTP, tokenized query strings and unusual ports;
- shared OkHttp resources and separate request profiles;
- parser fixtures for malformed playlists, global/per-item user-agent and catch-up metadata.

Reject:

- trust-all `X509TrustManager`;
- disabled hostname verification;
- broad scheme allow-list shared by source downloads, local files and playback;
- unconditional automatic redirect following;
- materializing complete large playlists as a `List`.

### `NuvioMedia/NuvioTV@797f6deca60b6e3be7ffd42829b995b0aa65998c` — TV product/network anti-pattern corpus

Adoption: `architecture-reference`, `anti-pattern`.

Useful:

- derive specialized clients from a base client;
- centralize user-agent and language headers;
- disable verbose release logging.

Reject:

- trust-all TLS and hostname verification bypass;
- one oversized DI module containing unrelated APIs;
- credential-bearing diagnostic URLs;
- Retrofit topology for M3U/XMLTV ingestion.

### `androidx/media@5fb306449733dd71595700c1227ad6087578c559` — playback API reference

Adoption: `architecture-reference`.

- Media3 remains behind `player:api`.
- `core:network` exposes a playback OkHttp client, not Media3 types.
- `player:media3` creates the Media3 data-source factory later.

### `android/tv-samples@4bc473babb6497ccdbbbeaea949e55f6ede71399` — TV interaction reference

Adoption: `adapt` for bounded focus/key behavior only.

Network relevance is limited to keeping network state out of composables and preserving deterministic focus while catalog content refreshes.

### `android/nowinandroid@7d45eae4f8720a0c77f507712ba2437ff974b6ed` — offline-first reference

Adoption: `architecture-reference`.

- network responses never become the UI source of truth;
- imported content is staged and committed to Room as a revision;
- repositories expose local flows;
- background refresh is orchestration, not storage ownership.

### `oxyroid/M3UAndroid@e68a71eee552f8ebf09333dd3e78954dfc976e9e` — module/test organization reference

Adoption: `test-corpus`, `architecture-reference`.

Useful:

- separate parser included build and mock-server/device-benchmark modules;
- Flow-based parser consumption;
- TV-specific baseline-profile journeys.

Reject its full plugin/module graph and JitPack dependency topology.

### `kodi-pvr/pvr.iptvsimple@d2310c911632a06e8dca7630befbf42d26f6fa70` — mature IPTV dialect corpus

Adoption: `test-corpus`.

Use later for header/referrer/user-agent, channel numbering, radio, catch-up, timeshift and EPG-correction fixtures. Do not copy the C++ architecture.

### `4gray/iptvnator@bc6e7018e0d9e9662bd1080d70f3d9d11dc118ed` — product workflow reference

Adoption: `product-reference`, `test-corpus`.

Use for source-management, category visibility, favorites/history, EPG grid and external-player fallback behavior. Do not adopt Electron/Angular/backend architecture.

## MuxTV-specific decisions

1. `core:network` accepts only remote `http` and `https` URLs. `content://` and `file://` are handled by a separate local-import boundary. RTSP/RTMP are playback concerns, not source-download concerns.
2. HTTPS is accepted by default. HTTP yields an explicit approval decision; it is not silently upgraded or downgraded.
3. User-info credentials in URL authority are rejected. Credential application is a later typed layer.
4. Control separators are rejected before parsing, including common double-encoded forms.
5. Fragments are rejected for source URLs. Queries are allowed because IPTV providers commonly require tokens.
6. Sensitive query fields and authority credentials must never appear in logs, exceptions or diagnostics.
7. Cross-host redirects strip sensitive headers. HTTPS-to-HTTP redirects are rejected.
8. Source downloads have independent compressed and decompressed byte limits.
9. No unsafe TLS bypass exists, including debug builds.
10. Network clients share transport resources but expose separate source, playback and image policies.
