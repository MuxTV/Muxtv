# EPG gzip/content-encoding hardening plan

> Execute as a narrow post-#28 transport hardening slice from accepted `main@8fced4dc282eaf07e8160f463c8276d7e48ba01b`. Self-hosted CI is currently disabled, so source/TDD work may proceed but this branch must remain draft/unmerged until exact-head validation returns.

## Goal

Close #110 by hardening the already-existing streaming EPG decoder instead of introducing a second transport/parser path. Preserve immutable EPG revisions, OkHttp 5.3 transparent gzip, separate compressed/decoded byte budgets and the existing streaming XMLTV parser.

## Existing accepted baseline

- `MuxTvHttpClients.source` has a network interceptor that limits compressed wire bytes and an application interceptor that limits the post-OkHttp decoded body.
- `EpgPayloadDecoder` already streams Plain/Gzip/Zip without materialising the whole payload.
- gzip magic detection already handles provider downloads with no `.gz` suffix or useful content type.
- decoder-level decoded-byte limits remain necessary for gzip bytes that arrive without HTTP `Content-Encoding` and therefore are not transparently decoded by OkHttp.
- `EpgRevisionImporter` stages immutable revisions and discards non-activated staging revisions.

## Design decisions

1. Keep OkHttp transparent gzip enabled. Do not set `Accept-Encoding: identity` and do not duplicate network decompression.
2. Treat a non-empty HTTP `Content-Encoding` as authoritative transport metadata:
   - `identity` continues to sniff actual payload bytes;
   - `gzip` / `x-gzip` selects gzip when raw encoded bytes reach the decoder;
   - unsupported or layered encodings are rejected as `UnsupportedContentEncoding` before payload bytes are read.
3. When `Content-Encoding` is absent/identity, payload magic is stronger than `Content-Type`.
4. `Content-Type` remains only a fallback hint when magic is inconclusive.
5. Do not add URL suffix detection. Actual magic bytes cover gzip-without-suffix and avoid false positives from misleading `.gz` filenames; add an end-to-end regression proving a `.gz` path with plain XML still imports as Plain.
6. Preserve existing ZIP support as accepted baseline, but do not expand archive scope in #110.
7. No new database schema or migration.

## Task 1 — Contract RED: Content-Encoding precedence

Update `EpgPayloadDecoderTest` so an unsupported `Content-Encoding` with gzip-looking bytes is rejected rather than decoded. Keep separate coverage that gzip magic works when encoding metadata is absent.

Add a resource contract proving unsupported encoding is rejected without reading payload bytes and still closes the input.

Expected current-main RED by source inspection: accepted `decode()` sniffs payload bytes before `selectFormat()`, and accepted `selectFormat()` calls `magicFormat()` before parsing `contentEncoding`.

## Task 2 — Implement decoder precedence

Split transport metadata selection from payload-format selection:

1. normalize and classify `Content-Encoding` before sniffing the body;
2. reject unsupported non-empty encoding before any payload read;
3. retain recognized gzip selection as authoritative;
4. only absent/identity encoding proceeds to magic sniff;
5. content type remains fallback after magic.

Do not leak header values in diagnostics. Preserve empty-payload handling and all existing streaming/close behavior for supported encodings.

## Task 3 — HTTP integration contracts

Extend `RemoteEpgRefresherTest` with:

- `Content-Encoding: gzip` response succeeds through OkHttp transparent decoding without double-decode;
- gzip bytes with generic content type and no `.gz` suffix import successfully;
- misleading `.gz` URL with plain XML imports successfully;
- compressed-byte overflow maps to typed `ResponseTooLarge(Compressed, ...)`;
- decoded overflow after one previous successful refresh leaves the previous active revision unchanged and discards only the new staging revision;
- malformed gzip after an existing successful revision leaves previous-good active.

## Task 4 — Cancellation/resource boundary

Add a decoder resource test that cancellation/failure during compressed consumption closes the underlying stream and propagates cancellation unchanged. Avoid synthetic full-buffer decompression.

## Task 5 — Issue truth-sync

Update #110 to reflect that gzip/ZIP streaming support and dual network limits pre-existed the issue; the remaining work is precedence + end-to-end hardening rather than a new decoder subsystem.

## Runner-off execution status

Implemented on `work/epg-gzip-hardening`:

- source-proven RED contract for unsupported `Content-Encoding` taking precedence over gzip magic;
- second source-proven RED proving unsupported encoding must be rejected before any payload read;
- production split between authoritative content-encoding selection and payload magic/content-type selection;
- unsupported/layered encoding is now rejected before reading the body while the input is still closed;
- explicit regression retaining gzip-magic fallback when `Content-Encoding` is absent;
- MockWebServer journey for `Content-Encoding: gzip` through the accepted OkHttp transparent-decompression path;
- generic-content-type/no-suffix gzip journey;
- misleading `.gz` URL with plain XML journey;
- typed compressed-wire-byte overflow journey;
- previous-good active revision preservation after decoded overflow;
- previous-good active revision preservation after malformed gzip;
- cancellation during gzip consumption closes the compressed input and propagates the exact cancellation object.

Static diff review from accepted main shows only the decoder, decoder/refresher tests and this plan. No Room schema, XMLTV parser, EPG matching, Guide UI or HTTP client policy has been modified.

No test in this section is claimed as executed while self-hosted CI is disabled. Both precedence RED contracts are source-proven against accepted main; all new contracts require exact-head execution when the runner returns.

## Acceptance when self-hosted returns

Exact final head must pass:

1. `:catalog:refresh:testDebugUnitTest` including all new decoder/refresher contracts;
2. `:core:network:testDebugUnitTest` to preserve compressed-before-transparent-gzip semantics;
3. Full host validation;
4. product/database old-edge + current device paths that execute remote EPG import;
5. previous-good revision assertions on a real Room-backed path;
6. zero unresolved review threads and privacy review.

## Non-goals

- disabling OkHttp transparent gzip;
- a second XMLTV parser;
- loading/decompressing the guide fully in RAM;
- Brotli/zstd/7z/RAR support;
- expanding ZIP behavior;
- Guide UI, EPG matching or Room migration changes;
- changing parser element/text/programme limits.
