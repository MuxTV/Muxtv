# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** starts after PR #42 is squash-merged and issue #39 is closed.

## Objective

Move from the completed secure source → catalog → Player vertical slice to reproducible performance evidence, immutable EPG and daily-use TV flows while paying down repository hygiene before adding new runtime breadth.

## Immediate order

1. **Repository truth and runner hygiene**
   - update README, `.work/CURRENT-STATE.md`, `.work/meta/status.yaml` and the active execution plan to the PR #42 merge;
   - replace stale issue #39 blocker text with issue #27;
   - fix Windows self-hosted cleanup so long Kotlin class paths cannot survive checkout;
   - keep this as a small documentation/CI PR with no product runtime changes.

2. **Issue #27 — deterministic IPTV corpus foundation**
   - generate provider-neutral M3U fixtures from repository code and a documented seed;
   - define 1k, 10k and 50k entry profiles without committing private playlists;
   - emit a manifest containing seed, profile, expected counts and SHA-256;
   - cover duplicates, malformed attributes, long metadata, relative URLs and header variants;
   - make measurements descriptive first; do not invent failing budgets before variance evidence exists.

3. **Issue #27 — measured boundaries**
   - streaming parse wall time and allocations;
   - 250-entry staging batches and activation transaction;
   - active channel and source-overview queries;
   - Player request installation proxy;
   - normal and low-RAM virtual profiles with exact environment metadata.

4. **Issue #28 — streaming XMLTV and immutable EPG revisions**
   - bounded parser, timezone/DST contracts and malformed input limits;
   - EPG access separate from M3U credentials;
   - staging, atomic activation and previous-good retention;
   - explicit channel-match confidence/reason.

5. **Issue #29 — daily-use discovery**
   - now/next projections, bounded Guide, Search, Favorites and Recent;
   - preserve stable channel identity and existing focus ownership;
   - no URL/program payload in navigation keys or semantics tags.

6. **Issues #30, #33 and #31**
   - bounded fallback/TV Doctor only after corpus evidence;
   - remaining light TV-first visual packages in data-dependency order;
   - R8, Baseline Profile, signing, SBOM and physical Android/Google TV/Fire TV alpha gate.

## Architecture constraints

- Kotlin/Compose/Room/Media3 remain the baseline.
- One process-owned ExoPlayer and MediaSession remain authoritative.
- Remote input is untrusted and bounded at every parser/network boundary.
- Encrypted source access has one in-process owner; no duplicate approval/security store.
- Emulator API matrices validate platform/lifecycle behavior, not vendor decoders or Fire OS.
- Rust/UniFFI, libmpv, bundled SQLite, Paging and a second engine require corpus-backed evidence and a separate ADR.

## Completion evidence per package

- reviewed exact head;
- focused RED/GREEN tests;
- Full validation;
- DeviceCurrent/DeviceMatrix only where Android system boundaries changed;
- secret-free evidence artifacts;
- synchronized human and machine-readable repository status after merge.
