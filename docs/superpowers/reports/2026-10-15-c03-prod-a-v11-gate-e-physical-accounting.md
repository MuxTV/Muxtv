# C03-PROD-A / Gate E — Room v11 Physical SQL Accounting Checkpoint

**Date:** 2026-10-15  
**Owner:** MuxTV/Muxtv issue #387, Draft PR #388  
**Scope:** integrated `RoomSourceRevisionStore` / additive Room v11, **not** Search/Browse/Playback read cutover  
**Status:** physical SQL mutation improvement **verified**; latency/WAL improvement **not established**; full Gate E **OPEN**.

## Evidence, with immutable run provenance

| Label | Source commit | GitHub-hosted API36 run | Method |
|---|---|---|---|
| Baseline A | `bf4e164b572344b7e4200cfa38c095f1f02278b6` | [#38675736565](https://github.com/MuxTV/Muxtv/actions/runs/38675736565) | `c03-room-delta-v2-v11-physical-audit` |
| No-op-display A | `8039d156e3bcccb20b4fbe9340ad0b0dfdcb762b` | [#38678449146](https://github.com/MuxTV/Muxtv/actions/runs/38678449146) | `c03-room-delta-v2-v11-physical-audit` |

Artifacts: `c03-prod-a-v11-accounting-<run>-1-attempt-1`, report
`.work/evidence/c03-prod-a-v11/c03-prod-a-v11-accounting.json`.

Both runs report successful correctness digests, full seven-scenario coverage, 10,000 corpus entries, API36, **one measured iteration and zero warmups**. There were no performance thresholds. A is the real production dual-write Room v11 path; B is a structurally different benchmark-only candidate and **must not be used to estimate a production gain**.

## Exact mutation and file outcomes

All values below are from the A_CURRENT_MUXTV variant of the two downloaded, verified JSON artifacts. WAL values are file byte counts measured at the fixture's post-stage/post-activation checkpoints. Timings are one run each; **not** paired interleaved trials.

| Scenario | Total audited row mutations: before → after | Canonical UPDATE: before → after | Stage ms: before → after | Activation ms: before → after | Peak WAL bytes: both |
|---|---:|---:|---:|---:|---:|
| delta-0 | 70,004 → 60,004 | 10,000 → 0 | 11,397 → 11,537 | 456.6 → 450.4 | 17,917,912 |
| delta-1 | 70,104 → 60,104 | 10,000 → 0 | 10,973 → 11,194 | 423.0 → 419.9 | 17,942,632 |
| delta-10 | 71,004 → 61,004 | 10,000 → 0 | 10,944 → 11,060 | 423.6 → 424.9 | 17,884,952 |
| delta-100 | 80,004 → 70,004 | 10,000 → 0 | 10,935 → 11,303 | 434.2 → 434.4 | 22,173,872 |
| reorder | 70,004 → 60,004 | 10,000 → 0 | 10,845 → 11,299 | 428.3 → 443.0 | 17,922,032 |
| remove-10 | 63,004 → 54,004 | 9,000 → 0 | 9,751 → 9,865 | 410.7 → 409.3 | 16,422,352 |
| token-churn | 80,004 → 70,004 | 10,000 → 0 | 11,081 → 11,235 | 431.3 → 426.1 | 22,264,512 |

**The post-checkpoint database growth bytes are also identical in corresponding before/after samples.** Eliminating an audited SQLite UPDATE does *not* imply a proportional reduction in dirty B-tree/index pages or WAL growth. The initial hypothesis `10k fewer UPDATEs ⇒ lower WAL` is **not supported** here.

In `delta-0`, the new real v11 representation writes 10,000 memberships and **zero new immutable payloads**, while maintaining 30,000 v10-compatible provider Search-document INSERTs. That legacy Search representation is still authoritative in PROD-A and must not be removed as a speculative optimization.

## Explanation and implementation

The initial `SourceRevisionDao.publishCanonicalDisplayMetadata` always updated each canonical row active through the affected source, even when the winning name had not changed. The change at `9bb05736c02f4bb8750c563635fe2f8e6fd59abe` retains the original deterministic active-source/provider ordering and adds an SQLite null-safe `IS NOT (winner scalar subquery)` predicate.

Correctness coverage:
- No-op publication followed by a real rename and canonical Search refresh: `CatalogShadowProjectionParityTest`, `41fc7e26abc5a6a55cc8050757725dbd6f52f288`.
- Multi-source winner priority and fallback when the previous winning source no longer has an active occurrence: `CatalogCanonicalWinnerAcrossSourcesTest`, starting at `c4f84d9d9665f015a67aab8e46b6d4e5bea8be92`, **exact-head CI pending at the time of this checkpoint**.
- Explicit `canonical_channels.update == 0` in the seven immutable-display fixture scenarios: `RefreshDeltaDatabaseMeasurementTest` and `Run-HostedC03ProdAV11Accounting.sh` on `8039d15`, proven in prior API36 run.

## Decision / guardrails

1. **ACCEPT the measured SQL-mutation reduction** for the fixed 10k fixture and API36 evidence.
2. **NO PERFORMANCE PASS**. One sample per scenario, unpaired runs, identical WAL and file growth, mixed directional latency differences. Further tuning needs an independently identified bottleneck and replicated, methodologically comparable trials.
3. **No Search read cutover.** Canonical, EPG and profile overlay parity remain necessary, but the 30k provider Search-document writes belong to the preserved v10-compatible surface until a separate fully qualified read-replacement slice.
4. **No schema change.** PROD-A remains additive schema 11 with real Room dual-write, previous-good and guarded publication.
5. **Keep PR Draft and issue #387 open.** New exact-head multi-source and full API26/API36 device results must pass before Gate E correctness can be accepted. This report is a historical measurement snapshot, not release approval.
