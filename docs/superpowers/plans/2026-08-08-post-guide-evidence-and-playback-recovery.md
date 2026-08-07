# Post-Guide evidence integrity and playback recovery execution plan

**Date:** 2026-08-08  
**Repository baseline:** `main@286ece017445b811a7adddd4ba7e85cacc5dd3ea`  
**Status:** implementation plan after repository/code/CI audit

## Goal

Move MuxTV from the accepted Guide milestone to an evidence-backed playback-recovery milestone without weakening the repository's local-first/privacy invariants, single-player ownership, active catalog truth, or CI provenance.

The plan deliberately puts evidence integrity before new performance or compatibility claims. It also keeps #30 split into bounded, reviewable contracts instead of creating one large player/UI/database change.

## Re-audited critical path

```text
#136/#137 exact PR evidence commit provenance
→ reconcile + rerun #133/#134 on accepted CI baseline
→ #27 repeated deterministic M3U evidence
→ #30A same-channel candidate + recovery policy contracts
→ #30B Media3 bounded recovery runtime + typed observations
→ #30C durable redacted diagnostics
→ #30D TV Doctor Lite presentation/export
→ #33/#93 Lounge Light packages
→ #31 alpha hardening + physical-device evidence
```

Parallel work may continue on #118, #111, #113, #101 and #100 only when ownership does not conflict with the critical path. In particular, any Room schema change required by #30 must reserve a schema owner before #100 proceeds.

---

## Phase 0 — Restore evidence integrity (#136 / PR #137)

### Why first

Before #136, PR workflows used default `actions/checkout` behavior while passing `pull_request.head.sha` to repository evidence manifests. On a `pull_request` event this can execute the synthetic merge ref while claiming the source head SHA. Functional validation remains useful, but strict source-head provenance is not established.

### Required implementation

1. Explicitly select the evidence commit during checkout:
   - PR: `github.event.pull_request.head.sha`;
   - manual dispatch: `github.sha`.
2. Run repository-owned `tools/ci/Assert-EvidenceCommit.ps1` before evidence-producing commands.
3. Fail closed unless `git rev-parse HEAD` exactly equals the claimed `SourceCommit`.
4. Statically protect this contract from drift in `tools/android/Test-TvHarnessSyntax.ps1`.
5. Cover:
   - `.github/workflows/self-hosted-validation.yml`;
   - `.github/workflows/android-tv-product-device-matrix.yml`;
   - `.github/workflows/database-migration-device-matrix.yml`;
   - `.github/workflows/measurement-variance-smoke.yml`.

### Definition of done

- source-head checkout and evidence commit are identical by executable assertion;
- affected workflow syntax/static contracts pass;
- Full self-hosted validation passes;
- Product and Database old-edge/current matrices pass when triggered;
- variance smoke passes when triggered;
- PR wording distinguishes source-head evidence from merge-result integration evidence.

### Follow-up integration gate

If branch protection needs a merge-result check, add or retain it as a **separate** integration lane. Never make a merge-ref run claim the PR source-head SHA.

---

## Phase 1 — Reconcile active PRs with the accepted evidence baseline

### PR #133 / issue #112 — provider readiness

Production invariants already implemented on the current source branch:

- explicit `Cancelled` and `Superseded` secondary terminal states;
- successful secondary attempt must target the active revision;
- successful primary attempt must correspond to accepted active catalog truth.

After #136 is accepted:

1. rebase/restack #133 on the accepted CI baseline;
2. rerun Full self-hosted validation;
3. rerun product old-edge/current Android TV matrix;
4. require `git HEAD == SourceCommit` evidence;
5. review #112 acceptance criteria line-by-line;
6. merge independently of #27/#30 if no catalog/runtime ownership conflict remains.

### PR #134 / issue #27 — deterministic M3U measurement series

Current production fix must remain fail-closed:

- if the computed series evidence directory already exists, abort;
- never partially overwrite a previous series;
- repeated reports must agree on profile, seed, source commit, corpus SHA-256, byte count and expected counts;
- initial measurements remain descriptive.

After #136 is accepted:

1. rebase/restack #134;
2. rerun Full validation and measurement variance smoke with verified source-head provenance;
3. verify that series-manifest failure paths still persist final status/evidence;
4. merge the harness only after its exact-source-head checks pass.

---

## Phase 2 — Produce #27 repeated baseline evidence

### Required runs

Run sequentially on the same controlled Windows self-hosted runner class:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-M3uCorpusSeries.ps1 `
  -SourceBranch main `
  -SourceCommit <accepted-full-sha> `
  -M3uProfile medium-10k `
  -M3uSeed 20260728 `
  -Repetitions 5 `
  -NoDaemon
```

and:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-M3uCorpusSeries.ps1 `
  -SourceBranch main `
  -SourceCommit <accepted-full-sha> `
  -M3uProfile large-50k `
  -M3uSeed 20260728 `
  -Repetitions 5 `
  -NoDaemon
```

### Review checklist

For each series verify:

- manifest source commit equals checked-out commit;
- corpus hash/bytes/expected counts are identical across repetitions;
- analyzer input lists exactly the intended reports;
- no repetition silently failed or disappeared;
- no parallel run contaminated the machine;
- variance is reported rather than hidden by a threshold;
- environment/runner label is stable enough to support comparison;
- no structural parser optimization is proposed unless the evidence identifies a material bottleneck.

### Decision gate

Only after repeated evidence may the project:

- introduce a performance threshold;
- claim a parser regression/improvement;
- prioritize allocation/throughput work;
- use #27 measurements to justify #109/#132 tuning.

Rust/UniFFI, bundled SQLite, libmpv or a second engine are **not** consequences of a slow single measurement. They require a separate measured residual problem and ADR.

---

## Phase 3 — #30A: same-channel candidate and recovery-policy contracts

### Current boundary

`PlaybackCatalog` currently exposes `getChannel(...)` and `resolveVariant(..., preferredVariantId)` and can resolve one preferred/selected variant into a redacted `ResolvedPlaybackRequest`. `PlayableChannel` already owns the ordered variant list. The player API owns `PlaybackRequest`, player state and error semantics.

Do not make UI code enumerate raw locators or perform arbitrary catalog scans.

### Proposed API boundary

Add a catalog-facing candidate resolver that preserves active/profile-visible catalog truth and exposes a bounded ordered set of **same canonical channel** candidates.

Candidate contract should carry only the minimum identity required for policy and resolution, for example:

- canonical channel id;
- variant id;
- stable order/index;
- whether it is the user's preferred variant;
- no secret-bearing locator in diagnostic/policy models.

Actual credential/header/locator resolution remains behind the existing catalog access boundary, one candidate at a time.

### Recovery policy contract

Add pure player-domain policy types covering:

- max candidate attempts;
- total recovery time budget;
- stable candidate ordering;
- terminal vs retryable failure family;
- no cross-channel fallback;
- no repeated attempt of the same candidate within one recovery generation;
- fallback success does not persistently rewrite preferred variant;
- first rendered frame remains the only playback-success boundary.

Do **not** choose magic product defaults in the API contract. Defaults belong in a later policy/configuration layer and should be evidence-reviewed.

### TDD order

Start with failing pure JVM tests for:

1. preferred candidate attempted first when present;
2. remaining same-channel variants keep deterministic order;
3. duplicate candidate ids are rejected/deduplicated by contract, not retried forever;
4. attempt count cannot exceed configured bound;
5. deadline exhaustion terminates recovery;
6. terminal auth/access failure does not create a retry storm;
7. transient candidate failure may advance to the next same-channel candidate;
8. no candidate from another channel can enter the plan;
9. fallback success does not mutate the preferred id;
10. cancellation/supersession invalidates the current recovery generation.

### Expected files

Keep exact names reviewable, but expected ownership is:

- `catalog/api/src/main/kotlin/app/muxtv/catalog/...` — same-channel candidate identity/resolution boundary;
- `player/api/src/main/kotlin/app/muxtv/player/...` — recovery budget/policy/failure-family contracts;
- corresponding JVM tests in those modules.

### Definition of done

- pure contract tests pass without Media3/Android runtime;
- no Room schema change;
- no UI change;
- no alternate engine;
- no raw URL/header/credential appears in policy `toString()` or diagnostics models.

---

## Phase 4 — #30B: Media3 bounded recovery runtime

### Runtime ownership

The existing process-owned `MediaSessionService` / single `ExoPlayer` remains authoritative. Recovery must be integrated there; Activity/ViewModel recreation must not create a second retry owner.

### Failure observation expansion

Current `PlaybackErrorCode` is too coarse for TV Doctor acceptance. Add typed internal observations for at least the issue-defined families:

- DNS;
- TLS;
- HTTP status/rejection;
- redirect/policy;
- timeout;
- network unreachable;
- manifest/format;
- codec/decoder;
- player/render failure;
- credential/access unavailable.

Do not store raw exception messages. Map exceptions into typed sanitized fields at the Media3 boundary.

### Recovery generation rules

Use the same generation/identity discipline already used by first-frame truth:

- one active recovery generation per requested profile/canonical channel playback;
- stale callbacks cannot advance the new generation;
- Activity recreation reattaches to service state, it does not restart the policy;
- WorkManager is not a playback retry owner;
- first accepted frame ends recovery successfully;
- user channel change/cancel supersedes the generation immediately.

### Media3 tests

Add deterministic tests/fixtures for:

- first variant HTTP/network failure → second same-channel candidate succeeds;
- terminal access failure stops according to policy;
- all candidates fail → one final bounded diagnostic result;
- deadline expires during an attempt;
- stale callback from previous candidate/generation cannot mark success;
- first frame from the accepted candidate is the only successful completion;
- recreation does not multiply attempt count;
- preferred variant remains unchanged after temporary fallback.

### Definition of done

- bounded attempts/time are executable, not comments;
- single-player ownership preserved;
- no endless retry loop;
- no cross-channel fallback;
- exact identity/first-frame semantics preserved;
- relevant host and Android TV matrix tests pass on verified source-head CI.

---

## Phase 5 — #30C: durable redacted playback diagnostics

### Schema ownership gate

Room is currently v10. Before adding durable #30 diagnostics:

1. reserve the next schema owner explicitly;
2. coordinate with #100 so two branches do not independently claim the same migration version;
3. prefer one narrowly scoped migration PR if persistence is actually necessary for #30 acceptance.

### Diagnostic record requirements

Persist only bounded, redacted, typed observations such as:

- timestamp/duration bucket;
- profile/channel opaque identity only if needed and safe;
- candidate ordinal/id in non-secret form;
- failure family and sanitized status/code;
- attempt number;
- whether fallback succeeded;
- terminal reason;
- app/player build provenance.

Never persist:

- raw stream locator;
- query token;
- Authorization/Cookie;
- credential material;
- unrestricted request headers;
- raw exception message/stack containing URLs.

### Retention

Use an explicit bounded retention policy. The limit must be covered by tests and must not grow with channel count or playback duration without bound.

### Definition of done

- migration + schema JSON accepted;
- migration matrix passes old-edge/current Android TV;
- redaction tests prove forbidden material cannot enter persistence/export;
- bounded retention verified;
- previous-good product data unaffected.

---

## Phase 6 — #30D: TV Doctor Lite

### Presentation scope

Doctor consumes typed redacted diagnostics; it must not reimplement network/player probing in Compose.

Expose actionable families, for example:

- source/credential access problem;
- provider/auth/rate-limit response;
- local/network connectivity;
- TLS/HTTP/redirect policy;
- manifest/stream format;
- decoder/codec/render capability;
- bounded fallback exhausted.

### TV constraints

- D-pad reachable from Player/error/recovery surface;
- no focus traps;
- 720p dialog/content remains scrollable;
- long messages do not expose raw locators;
- export is explicitly user initiated and redacted;
- failed export does not alter playback/catalog state.

Coordinate these UI details with #111 rather than duplicating remote/focus primitives.

### Device evidence

Emulators prove lifecycle/API/focus/database behavior but do not prove vendor MediaCodec/HDR/passthrough/weak ARM/Fire OS. Codec/decoder statements remain device-specific until physical evidence is collected.

---

## Phase 7 — Lounge Light (#33/#93)

Do not redesign placeholder screens. Apply visual packages only to accepted real destinations:

1. global shell/tokens;
2. Channels;
3. Search;
4. Recent;
5. Guide;
6. Player/Doctor;
7. Sources/settings as needed.

Every package keeps D-pad/focus/selected/playing states explicit and must preserve semantic truth and redaction boundaries.

Use small reviewable PRs instead of one repo-wide visual rewrite.

---

## Phase 8 — Alpha hardening (#31)

Required before an alpha-quality claim:

- R8/minification review;
- Baseline/Startup Profiles backed by startup evidence;
- low-RAM/endurance runs;
- repeated zapping/player lifecycle scenarios;
- signed release artifacts;
- SBOM/release provenance;
- physical Android TV/Google TV evidence;
- at least one constrained/weak physical target;
- Fire TV/Fire OS evidence;
- codec/HDR/audio claims scoped to observed devices;
- #39/#40 user/recovery/release documentation.

The existing automated API26/API36 matrix remains required but is not a substitute for physical devices.

---

## Parallel hardening queue

These can proceed when ownership is independent:

### #118 Direct Boot / WorkManager

- no refresh before user unlock;
- idempotent post-unlock initialization;
- reboot/package-replace without duplicate periodic work;
- explicit boot/unlock tests.

### #111 TV remote contracts

- long-press not swallowed by preview handlers;
- dialog D-pad scrollability at 720p;
- visible focus/selected/playing contrast.

### #113 portable backup envelope

- versioned non-secret envelope;
- integrity digest;
- SAF capability detection;
- restore available on first run;
- secret model handled separately.

### #101 CI database-suite split

Proceed only with before/after runner wall-time evidence and nonzero selected-module assertions. Do not weaken coverage merely to shorten CI.

### #100 conditional M3U validators

Wait for a free Room schema owner. Preserve previous-good source revision on malformed/error paths and model `304 Not Modified` as success without a new catalog revision.

---

## Branch and PR hygiene

The repository contains many historical/backup/feature branches. Do not bulk-delete while active provenance/restacks are in flight.

After #137/#135/#133/#134 are reconciled:

1. classify branches as active, merged, superseded, backup/provenance, or unknown;
2. protect any branch referenced by open PR/evidence/ADR;
3. tag or document provenance where branch retention is unnecessary;
4. delete only confirmed merged/superseded stale refs;
5. repeat periodically to keep branch navigation usable.

---

## Merge order

Preferred order after checks/reviews:

1. **#137** — evidence commit provenance;
2. **#135** — repository truth synchronized to the accepted post-audit state (restack if #137 changes accepted main first);
3. **#133** — provider readiness, after post-#137 verified host/device rerun;
4. **#134** — measurement-series harness, after post-#137 verified host/variance rerun;
5. **#27 evidence run/review** — 5×10k + 5×50k;
6. **#30A** — pure candidate/recovery policy contracts;
7. **#30B** — Media3 bounded runtime;
8. **#30C** — durable diagnostics if required, coordinated with schema ownership;
9. **#30D** — TV Doctor Lite;
10. **#33/#93** — visual modernization packages;
11. **#31** — alpha hardening/release/device matrix.

If #133 has no dependency conflict, #133 and #134 may exchange positions after #137; neither may use pre-#136 PR runs as strict exact-source-head evidence.

---

## Stop conditions / anti-goals

Stop and reassess rather than expanding scope if any of these occur:

- CI executes a different commit than evidence claims;
- repeated #27 runs are not reproducible enough to support thresholds;
- #30 requires cross-channel fallback to appear successful;
- a retry owner appears outside the process-owned player service;
- diagnostics require raw URL/header/credential storage;
- two concurrent branches try to own the same Room migration version;
- a proposal introduces libmpv/Rust/second engine without measured Media3 residual failure;
- emulator results are presented as proof of physical decoder/HDR/Fire TV compatibility.

The objective is not maximum feature count. The objective is a reviewable, bounded and evidence-backed route from the current functional pre-alpha to a defensible alpha.