# MuxTV Next Execution Plan — Archived 2026-07-25 Baseline

> **Status:** superseded by [`2026-07-27-next-execution.md`](2026-07-27-next-execution.md).

This file is retained as the historical execution baseline that led from durable source onboarding through the first hardened Media3 vertical slice. Do not use its unchecked boxes as current repository state.

## Historical packages now completed

- PR #20 — durable pending-source registry and Room schema v4.
- PR #22 — transactional catalog staging and importer hardening.
- PR #32 — secure Android TV source-entry wizard.
- PR #34 — deterministic focus ownership and touch-free source journey.
- PR #35 — first repository-truth synchronization.
- PR #36 — request-scoped Media3 OkHttp transport and header isolation.
- PR #37 — retryable MediaController lifecycle and disconnect invalidation.
- PR #38 — cancellable playback setup and remote-session reconnect.
- Issues #24, #25 and #26 are closed.

## Final Media3 baseline

- Issue #26 merge: `8665f80d6e38bc90d10ead0d3a3618fbecd4e304`.
- DeviceMatrix: run `30222900566`, Android TV API 26 and API 36, no fallback.
- Per profile: 4 credential, 19 database, 10 Media3 and 11 app tests; zero failures/errors/skips.
- Final cleaned-head Full: run `30223482178` on `f4c7731dff930200c5cefb77765d0fa37b13b02f`.

## Preserved constraints

- `minSdk = 26`.
- One process-owned `ExoPlayer` and one `MediaSession`.
- No locator/query/cookie/credential/header values in Navigation, SavedState, Room projections, logs, traces, screenshots, semantics or exception text.
- No Rust, libmpv, bundled SQLite, Paging or second player engine without corpus-backed evidence and a separate ADR.
- Functional, schema/security, visual and release changes remain separate reviewable PRs.

## Current continuation

Use [`2026-07-27-next-execution.md`](2026-07-27-next-execution.md) for the active order:

1. close repository-truth issue #40;
2. implement exact-origin HTTP playback approval #39;
3. build deterministic corpus/measurements #27;
4. implement immutable EPG #28;
5. implement Guide/Search/Favorites/Recent #29;
6. implement bounded fallback/TV Doctor #30;
7. complete remaining light TV-first visual packages #33;
8. complete release and physical-device gate #31.
