# Secure Remote EPG Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: execute task-by-task with TDD and exact-head verification.

**Goal:** Download a remote XMLTV guide through the existing encrypted access/network policy, apply conditional validators, bounded raw/decoded payload limits, and stream the result into immutable EPG revisions.

**Architecture:** `RemoteEpgRefresher` lives in `catalog:refresh`. It reuses the singleton `RemoteSourceAccessManager`, `MuxTvHttpClients.source`, `SourceUrlPolicy`, `SourceRequestContext`, `EpgPayloadDecoder` and `EpgRevisionImporter`. Validators are explicit bounded request/result values; persistence belongs to the later scheduler/state package #70, so this package requires no Room v6.

## Constraints

- No second HTTP client, credential codec or scheduler.
- No URL, validator, header, source identity or programme content in diagnostics.
- `304` creates no staging revision.
- Response validators advance only after successful import or safe `304`.
- Failed/empty/superseded import does not advance validators.
- Raw response and post-decompression limits are independent.
- Cancellation cancels OkHttp and rethrows.
- No WorkManager, matching or UI in this package.

## Task 1 — Shared cancellable OkHttp boundary

- [ ] Add `OkHttpAwait.kt` with internal `Call.awaitResponse()`.
- [ ] Move the existing private implementation out of `RemoteSourceRefresher.kt` without behavior change.
- [ ] Preserve response close after late cancellation.

## Task 2 — Typed validators and RED contracts

- [ ] Add bounded `EpgHttpValidators` with redacted `toString()`.
- [ ] Add `RemoteEpgRefreshRequest` and typed result/failure models.
- [ ] RED tests: request/result diagnostics, validator limits/CRLF rejection, `304` no-import behavior.

## Task 3 — Remote acquisition

- [ ] Read encrypted access through the shared manager.
- [ ] Apply existing URL and explicit HTTP approval policy.
- [ ] Build XMLTV `Accept`, User-Agent, Referer and approved sensitive headers.
- [ ] Add conditional headers from validated request values.
- [ ] Handle `304` before generic HTTP failure.
- [ ] On `200`, pass response hints/body through `EpgPayloadDecoder` and `EpgRevisionImporter`.
- [ ] Map raw-size, decoded-payload, redirect, timeout, DNS, TLS and IO failures.
- [ ] Return response validators only for successful import or `304`.

## Task 4 — Integration contracts

- [ ] Plain, gzip and ZIP responses through `MockWebServer`.
- [ ] Magic bytes override misleading hints.
- [ ] Failed import and superseded import do not advance validators.
- [ ] Cancellation leaves previous-good guide intact.
- [ ] Sensitive headers never cross redirect origin.
- [ ] Full module diagnostics remain value-free.

## Task 5 — Application wiring and verification

- [ ] Expose `EpgRevisionStore`, `EpgRevisionImporter` and `RemoteEpgRefresher` through existing Hilt singletons.
- [ ] Run `:catalog:refresh:testDebugUnitTest` and lint.
- [ ] Run importer/ingest regressions.
- [ ] Run repository Full on exact head.
- [ ] Merge only after code review and exact-head success.

## Follow-up

#70 persists validators/policies/state and schedules work. #71 performs deterministic matching and now/next projection.