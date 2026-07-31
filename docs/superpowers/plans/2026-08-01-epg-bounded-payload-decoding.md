# Bounded EPG Payload Decoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task.

**Goal:** Convert one remote EPG response body into a bounded XML input stream for the existing streaming XMLTV importer, supporting plain XML, gzip and ZIP.

**Architecture:** Add a single decoder boundary in `catalog:refresh`. It uses magic bytes before normalized HTTP hints, streams decoded bytes through an exact limit, and calls a suspend consumer while the wrapper remains open. ZIP processing skips a bounded number of directory entries and exposes only the first regular member; it never extracts files or buffers the complete document.

**Tech Stack:** Kotlin 2.4, Java `PushbackInputStream`, `GZIPInputStream`, `ZipInputStream`, JUnit 4, Truth, Coroutines Test.

## Global Constraints

- Keep `minSdk = 26`.
- Add no compression/archive dependency.
- Do not buffer the decoded XMLTV document or extract an archive to disk.
- Never include URL, path, ZIP entry name, header value or programme content in diagnostics.
- Magic bytes override HTTP hints; hints apply only when magic is inconclusive.
- Count bytes after decompression and reject immediately after the configured bound.
- Preserve transport errors, cancellation and consumer exceptions unchanged.
- Do not add network acquisition, conditional HTTP, WorkManager, matching or UI in this package.

---

### Task 1: Executable decoder contract

**Files:**
- Create: `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/EpgPayloadDecoderTest.kt`

- [x] Add RED tests for plain/gzip/ZIP detection, decoded-byte bounds, ZIP structural bounds, malformed inputs, redacted diagnostics and exception propagation.
- [x] Verify the contract fails because decoder types are absent.
- [x] Commit the RED contract.

### Task 2: Plain and gzip decoding

**Files:**
- Create: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/EpgPayloadDecoder.kt`

- [x] Add typed formats, hints, limits, rejection reasons and generic decode result.
- [x] Sniff four bytes through `PushbackInputStream`.
- [x] Recognize gzip magic and normalized gzip hints.
- [x] Add post-decompression byte counting for `read`, bulk read and `skip`.
- [x] Preserve arbitrary transport/consumer failures.

### Task 3: Bounded ZIP decoding

**Files:**
- Modify: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/EpgPayloadDecoder.kt`
- Test: `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/EpgPayloadDecoderTest.kt`

- [x] Recognize local, empty and spanned ZIP signatures.
- [x] Bound leading directory entries and entry-name characters.
- [x] Stream only the first regular member.
- [x] Distinguish a valid empty archive from a truncated local header.
- [x] Keep entry names and exception messages out of public failures.

### Task 4: Repository integration

- [ ] Run `:catalog:refresh:testDebugUnitTest` and `:catalog:refresh:lintDebug`.
- [ ] Run `:catalog:ingest:test` and `:catalog:importer:testDebugUnitTest`.
- [ ] Run repository Full validation on the exact head.
- [ ] Review for buffering, archive extraction, leaked values, swallowed cancellation and unsupported API use.
- [ ] Mark PR ready and merge only after the exact-head gates pass.

## Follow-up Package

The next PR will combine this decoder with the existing source OkHttp/credential boundaries and add conditional HTTP (`ETag`, `Last-Modified`, `304`) without introducing scheduling or UI.