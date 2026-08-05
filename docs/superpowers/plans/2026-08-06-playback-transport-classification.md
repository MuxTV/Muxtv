# Explicit Playback Transport Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development for each behavior change and superpowers:verification-before-completion before merge.

**Goal:** Close #108 by making playback transport a typed, request-scoped decision at the single Media3 source-creation choke point, with an executable raw MPEG-TS contract that can be reused by #30 diagnostics/fallback.

**Architecture:** Add one pure `PlaybackTransportClassifier` and one immutable source policy. `PlaybackMediaSourceFactory` consumes that decision when constructing the service-owned Media3 source. HLS and DASH use their dedicated Media3 media-source factories, raw MPEG-TS uses `ProgressiveMediaSource` with an explicit `TsExtractor.MODE_SINGLE_PMT`, ordinary progressive content uses `ProgressiveMediaSource`/default extractors, and unknown content retains bounded Media3 auto inference. The decision is request-local and carries no locator/header values in diagnostics.

**Official Media3 basis (reviewed 2026-08-06):** `DefaultMediaSourceFactory` chooses a delegate from URI/MIME inference; `ProgressiveMediaSource.Factory` accepts a specific `ExtractorsFactory`; `TsExtractor.MODE_HLS` has HLS-specific semantics while `MODE_SINGLE_PMT` assumes one PMT. This package therefore makes the raw-TS extractor mode explicit instead of relying on unrelated HLS behavior.

## Constraints

- Keep `MuxTvPlaybackService` as the only ExoPlayer/MediaSession owner.
- No retry loop or fallback engine in this issue; #30 owns bounded fallback.
- No HTTPS→HTTP downgrade and no header/origin policy change.
- No locator, query, user-info or header value in transport `toString()`/diagnostics.
- Classification is deterministic and side-effect free; no probing/network I/O in the classifier.
- A URI suffix is a hint, not provider truth. Unknown/misleading-without-explicit-evidence stays `AUTO` rather than triggering speculative retries.
- Manual provider/user override remains out of scope until a real owning contract exists.

---

### Task 1 — RED: typed classifier and source policy

**Files:**
- Create `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackTransportClassifierTest.kt`

- [ ] Assert `.m3u8` (case-insensitive, query-safe) → `HLS`.
- [ ] Assert `.ts` → `MPEG_TS_LIVE`.
- [ ] Assert `.mpd` → `DASH`.
- [ ] Assert known ordinary file/progressive URI → `PROGRESSIVE`.
- [ ] Assert suffixless/ambiguous URI → `AUTO`.
- [ ] Assert transport decision string representation does not expose the locator.
- [ ] Assert raw TS policy is `PROGRESSIVE` with `TsExtractor.MODE_SINGLE_PMT`, never `MODE_HLS`.
- [ ] Run focused JVM test and record the expected unresolved-symbol RED before production code.

### Task 2 — GREEN: pure transport owner

**Files:**
- Create `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackTransport.kt`

- [ ] Implement immutable enums/data without Android Context or I/O.
- [ ] Normalize only URI path suffix casing; ignore query/fragment values for classification.
- [ ] Keep `AUTO` explicit rather than silently equating it to progressive.
- [ ] Add privacy-safe `toString()`.
- [ ] Rerun focused JVM test to GREEN.

### Task 3 — explicit Media3 mapping

**Files:**
- Modify `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackMediaSourceFactory.kt`
- Modify `player/media3/build.gradle.kts` only if DASH dedicated source support is needed.
- Add focused source-factory/instrumentation tests.

- [ ] HLS uses dedicated HLS source semantics.
- [ ] MPEG_TS_LIVE uses `ProgressiveMediaSource.Factory` with an extractor factory that constructs `TsExtractor.MODE_SINGLE_PMT`.
- [ ] DASH uses dedicated DASH source semantics if module support is included by this package; otherwise fail as a typed unsupported mapping rather than implicit ClassNotFound behavior.
- [ ] PROGRESSIVE uses the normal progressive extractor set.
- [ ] AUTO preserves Media3 inference once, with no internal retry/reclassification loop.
- [ ] Request-scoped HTTP headers/client policy remain unchanged.

### Task 4 — generation/privacy regression

- [ ] Existing setup-generation/cancellation tests remain authoritative; transport creation must occur only for the request being installed.
- [ ] Add A→B request test showing transport/header decision is reconstructed from B and cannot retain A state.
- [ ] Verify no second ExoPlayer or feature-owned player is introduced.
- [ ] Verify no raw locator/header values enter logs, semantics or `toString()`.

### Task 5 — acceptance and closure

- [ ] Full host validation exact head.
- [ ] Product DeviceMatrix exact head on API26/API36 with Media3 instrumentation non-zero.
- [ ] Final diff/review-thread/privacy review.
- [ ] Mark PR ready and squash-merge with `Closes #108`.
- [ ] Update #30 to consume the typed transport decision rather than re-detect formats.
