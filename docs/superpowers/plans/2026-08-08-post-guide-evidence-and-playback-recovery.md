# Post-Guide evidence integrity, playback recovery and TV design execution plan

**Date:** 2026-08-08  
**Repository baseline:** `main@286ece017445b811a7adddd4ba7e85cacc5dd3ea`  
**Status:** implementation plan after repository/code/CI/design audit

## Goal

Move MuxTV from the accepted Guide milestone to an evidence-backed playback-recovery milestone and then to a coherent TV-first Lounge Light interface without weakening local-first/privacy invariants, single-player ownership, active catalog truth, CI provenance or established D-pad/focus restoration contracts.

The plan deliberately puts evidence integrity before new performance or compatibility claims. It also keeps #30 split into bounded, reviewable contracts and keeps design work split into interaction/accessibility primitives before broad visual modernization.

## Normative design input

The external design source requested for this plan is:

`https://github.com/emilkowalski/skills/blob/main/skills/apple-design/SKILL.md`

Use it as a principles/motion/craft input, **not** as an instruction to visually clone iOS/tvOS or to introduce touch-only interaction into Android TV.

Android TV mapping:

- **purpose:** daily viewing tasks remain visually dominant; setup/advanced diagnostics do not crowd the primary path;
- **agency:** the user controls source/preference/navigation decisions; temporary playback fallback never silently rewrites the preferred variant;
- **familiarity:** Compose for TV, D-pad, OK and Back remain platform-native and predictable;
- **flexibility:** layouts remain usable at 720p/1080p and resilient to long Russian strings;
- **simplicity:** technical details use progressive disclosure rather than always-visible controls;
- **craft:** focus, typography, spacing, copy, state distinctions and transitions are treated as product correctness;
- **response:** focused/pressed state is visible immediately on the D-pad input path;
- **interruptibility:** spatial transitions may be retargeted/cancelled by new navigation intent and must not queue behind repeated D-pad input;
- **spatial consistency:** enter/exit paths and focus restoration preserve where the user came from;
- **materials/depth:** tone/elevation/translucency may support hierarchy, but blur/glass is never mandatory on weak TV hardware;
- **typography:** platform/system typography first; hierarchy is carried by size/weight/leading rather than decorative fonts;
- **reduced motion:** translation/scale/overshoot can be removed while outline/tone/opacity feedback remains useful;
- **accessibility:** focused/selected/playing/disabled states must remain distinguishable without colour alone.

Gesture-specific drag velocity, momentum projection and rubber-band rules are not copied into the five-button TV model unless a future pointer/touch surface has an explicit product requirement.

## Re-audited critical path

```text
#136/#137 exact PR evidence commit provenance
→ reconcile + rerun #133/#134 on accepted CI baseline
→ #27 repeated deterministic M3U evidence
→ #30A same-channel candidate + recovery policy contracts
→ #30B Media3 bounded recovery runtime + typed observations
→ #30C durable redacted diagnostics
→ #30D TV Doctor Lite presentation/export
→ #33/#93 Lounge Light visual packages
→ #31 alpha hardening + physical-device evidence
```

Parallel work may continue on #118, #111, #113, #101 and #100 only when ownership does not conflict with the critical path. In particular, #111 D-pad/focus/accessibility packages are intentionally parallel because they should not own Room/catalog/playback-recovery state. Any Room schema change required by #30 must reserve a schema owner before #100 proceeds.

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

### Current implementation state

PR #137 already contains the checkout/provenance assertion implementation. On its current head:

- Full self-hosted validation has passed;
- Database old-edge/current matrix has passed with the new provenance assertion;
- Product matrix and variance smoke still require completion before merge.

A later run on PR #135 lost communication with the self-hosted runner during artifact upload. That outage is execution evidence, not a reason to weaken #137 gates.

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

### PR #135 — repository truth sync

The branch updates `CURRENT-STATE`, `README`, machine-readable status and this execution plan after Guide completion.

Before merge:

1. land/reconcile #137 first if its workflow semantics become accepted `main`;
2. restack #135 on the accepted baseline if needed;
3. rerun Full validation;
4. distinguish an actual repository failure from a self-hosted-runner disconnect/artifact-upload failure;
5. merge only when the required check is genuinely green.

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

## Parallel Design Phase D0 — Source and adaptation contract (#93/#111)

### Current state

The existing Lounge Light specification already chooses a compact light TV direction with system typography, stable geometry, explicit focus and no mandatory blur/parallax. The current accepted implementation has **not** yet completed that visual rebuild: `MuxTvTheme` is still dark and the shared dense-focus primitive currently uses scale/fade motion.

A historical branch `work/tv-design-craft-111` contains useful authored Package A work, but it is stale/diverged and never obtained current compile/device acceptance. It is provenance, not a merge candidate.

### Accepted rule

Do not create a second design system. Refine existing `core:designsystem` and existing real feature routes.

Do not rewrite existing state/navigation contracts for visual work. In particular preserve:

- Channels focus anchors/restoration;
- Search query/focus continuity;
- Guide bounded viewport and focus restoration;
- Player/Back canonical focus restoration;
- active/profile-visible catalog truth;
- no fake channels/EPG/status data.

### Definition of done

- exact Apple-design source linked from #93/#111;
- TV adaptation rules documented;
- touch-only gesture mechanics explicitly excluded;
- broad visual work remains separate from product/recovery state ownership.

Status: **started/completed as documentation contract**.

---

## Parallel Design Phase D1 — Immediate dense D-pad focus (#111 / PR #138)

### Problem in accepted main

Current shared tokens/primitive use approximately:

- focus scale `1.06`;
- unfocused alpha `0.84`;
- focus motion `140 ms`.

That is inappropriate for high-frequency D-pad traversal: geometry moves while the user is trying to establish spatial position, and repeated key input can visually lag behind navigation intent.

### TDD contract

A clean restack branch from accepted `main` must first make JVM tests require:

- dense focus scale = `1.0`;
- focused alpha = `1.0`;
- unfocused alpha = `1.0`;
- focus outline >= `2dp`;
- dense focus geometry duration = `0 ms`;
- ordinary screen transition budget <= `300 ms`.

PR #138 currently starts as **RED-only**. Do not infer RED from static inspection: the failing test must actually execute before production GREEN is committed.

### Minimal GREEN after observed RED

Prefer the smallest production change that satisfies the contract:

1. update shared focus/motion tokens;
2. remove dead scale/fade animation logic from `MuxTvFocusSurface` once it has no behavioural purpose;
3. preserve native Compose `clickable` ownership;
4. use immediate outline plus neutral surface-tone/depth change for focus;
5. do not synthesize OK/Enter through preview-key handlers.

### Verification

- JVM `core:designsystem` tests;
- app compile;
- API26 old-edge product interaction;
- API36 current product interaction;
- rapid D-pad traversal has no focus geometry queue;
- disabled/click behaviour remains native.

### Definition of done

- observed RED exists before GREEN;
- production change is minimal;
- focus geometry is stable;
- no navigation/state regression;
- source-head host/device evidence passes after #137 baseline is accepted.

---

## Parallel Design Phase D2 — Remote semantics: OK, long-press and repeat (#111)

### Scope

Add real interaction tests before production changes for:

- one OK/DPAD_CENTER activation → exactly one click;
- disabled surface → no activation;
- long-press callback where a product control explicitly supports it;
- key auto-repeat does not generate accidental repeated activation;
- directional movement is not swallowed by preview handlers;
- Back remains platform-owned unless a destination has an explicit local unwind step.

### Implementation rule

Use native Compose/TV input semantics and advanced click primitives only where long-press is a real product requirement. Do not add a global key interception layer to manufacture clicks.

### Definition of done

- API26 and API36 instrumentation coverage;
- no duplicate activation;
- no swallowed repeat/navigation events;
- no global input-owner regression.

---

## Parallel Design Phase D3 — State model and reduced motion (#111/#93)

### Required independent states

A reusable interactive surface must be able to express:

- focused;
- selected;
- currently playing;
- disabled;
- combinations such as focused+selected or focused+playing.

No pair may depend on colour alone.

### Reduced-motion contract

When reduced motion is active:

- remove scale/translation/overshoot used only for flourish;
- preserve focus outline, tone/luminance and useful opacity changes;
- keep screen state changes understandable;
- never slow D-pad response.

### Verification

Create deterministic state fixtures/tests for all supported combinations and long Russian labels. Prefer screenshot/golden evidence only after semantic interaction/state tests exist.

---

## Parallel Design Phase D4 — Dialog and viewport reachability (#111)

### Required scenarios

At minimum test 720p and 1080p TV viewports for:

- long dialog content remains D-pad scrollable;
- first actionable element can receive focus;
- every action can be reached without pointer/touch;
- Back dismisses/unwinds predictably;
- focus cannot escape into obscured background controls;
- large Russian strings do not make actions unreachable.

### Definition of done

- interaction/device tests prove reachability;
- no focus traps;
- no hidden required actions below a non-scrollable viewport.

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

Current `PlaybackErrorCode` is too coarse for TV Doctor acceptance. Add typed internal observations for at least:

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

Use #111 D1–D4 primitives/contracts rather than duplicating remote/focus logic.

### Device evidence

Emulators prove lifecycle/API/focus/database behavior but do not prove vendor MediaCodec/HDR/passthrough/weak ARM/Fire OS. Codec/decoder statements remain device-specific until physical evidence is collected.

---

## Phase 7 — Lounge Light visual rebuild (#33/#93)

Do not redesign placeholder screens and do not rewrite feature state machines for visuals. Apply packages only to accepted real destinations.

### D5 — Foundation

Implement first as a narrow design-system/shell package:

- Lounge Light colour scheme replacing the current dark placeholder theme;
- platform/system typography;
- spacing/shape/elevation tokens;
- stable navigation rail/shell;
- bounded interruptible spatial transition primitives;
- reduced-motion behaviour;
- no mandatory blur/glass/parallax dependency.

Acceptance:

- 720p and 1080p layouts;
- Russian strings;
- no dense-focus scale motion;
- D-pad/Back semantics unchanged;
- no fake data.

### D6 — Daily content surfaces

Modernize one real surface per reviewable package, preserving existing state/navigation contracts:

1. Channels;
2. Search;
3. Recent;
4. Guide.

For Channels specifically preserve existing focus-anchor/nearest-previous restoration and filter graph rather than replacing it with new visual navigation state.

### D7 — Playback/support surfaces

1. Player + recovery/TV Doctor;
2. Sources;
3. Settings/advanced support surfaces.

Doctor consumes typed/redacted diagnostics; Compose never becomes a retry/diagnostics source of truth.

### Visual verification

After semantic interaction tests are green, add screenshot/golden evidence for stable states at representative 720p/1080p viewports. Goldens complement but do not replace D-pad tests.

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

### #118 Direct Boot / WorkManager

- no refresh before user unlock;
- idempotent post-unlock initialization;
- reboot/package-replace without duplicate periodic work;
- explicit boot/unlock tests.

### #111 TV remote/design contracts

Delivered through D1–D4 above. Keep it separate from broad D5–D7 visual rebuild.

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

Known design branch rule:

- `work/tv-design-craft-111` is retained as historical/provenance input;
- active clean design restack is `work/111-tv-design-craft-restack` / PR #138;
- never merge the stale branch wholesale merely because it contains useful prior commits.

After #137/#135/#133/#134/#138 are reconciled:

1. classify branches as active, merged, superseded, backup/provenance, or unknown;
2. protect any branch referenced by open PR/evidence/ADR;
3. tag or document provenance where branch retention is unnecessary;
4. delete only confirmed merged/superseded stale refs;
5. repeat periodically to keep branch navigation usable.

---

## Preferred merge / execution order

### Functional critical path

1. **#137** — evidence commit provenance;
2. **#135** — repository truth synchronized/restacked on accepted CI baseline;
3. **#133** — provider readiness, after post-#137 verified host/device rerun;
4. **#134** — measurement-series harness, after post-#137 verified host/variance rerun;
5. **#27 evidence run/review** — 5×10k + 5×50k;
6. **#30A** — pure candidate/recovery policy contracts;
7. **#30B** — Media3 bounded runtime;
8. **#30C** — durable diagnostics if required, coordinated with schema ownership;
9. **#30D** — TV Doctor Lite;
10. **#33/#93 D5–D7** — broad visual modernization;
11. **#31** — alpha hardening/release/device matrix.

### Parallel design/accessibility lane

- **#138 / #111 D1** may land once RED→GREEN and verified host/device evidence are complete;
- then **D2 remote semantics**;
- then **D3 state/reduced-motion**;
- then **D4 dialog/reachability**.

D1–D4 must not block #27 measurement work unless a shared CI/device runner is temporarily saturated. D5–D7 should wait until playback recovery/Doctor contracts are stable enough that UI work is not targeting placeholders.

If #133 has no dependency conflict, #133 and #134 may exchange positions after #137; neither may use pre-#136 PR runs as strict exact-source-head evidence.

---

## Verification matrix by change class

| Change | JVM/host | API26 | API36 | Measurement | Physical TV |
|---|---:|---:|---:|---:|---:|
| CI provenance | required | when matrix affected | required when matrix affected | required for variance lane | no |
| #133 catalog readiness | required | required | required | no | no |
| #134 measurement harness | required | as triggered by shared acceptance | as triggered | required | no |
| #111 D1–D4 | required | required | required | no | later UX spot check |
| #30A policy | required | compile/integration as needed | integration as needed | no | no |
| #30B runtime | required | required | required | targeted playback evidence | later required |
| #30C Room diagnostics | required | migration matrix | migration matrix | no | no |
| #30D Doctor | required | required | required | no | later required |
| Lounge D5–D7 | required | required | required | no | visual/focus spot check |
| Alpha #31 | required | required | required | repeated | required |

---

## Stop conditions / anti-goals

Stop and reassess rather than expanding scope if any of these occur:

- CI executes a different commit than evidence claims;
- the self-hosted runner is unhealthy and a required RED/GREEN or device result has not actually executed;
- repeated #27 runs are not reproducible enough to support thresholds;
- #30 requires cross-channel fallback to appear successful;
- a retry owner appears outside the process-owned player service;
- diagnostics require raw URL/header/credential storage;
- two concurrent branches try to own the same Room migration version;
- a proposal introduces libmpv/Rust/second engine without measured Media3 residual failure;
- broad Lounge work rewrites feature state/navigation instead of styling accepted contracts;
- dense D-pad focus introduces queued scale/translation motion;
- a design decision depends on touch-only gestures in the remote-only path;
- emulator results are presented as proof of physical decoder/HDR/Fire TV compatibility.

The objective is not maximum feature count. The objective is a reviewable, bounded, TV-native and evidence-backed route from the current functional pre-alpha to a defensible alpha.