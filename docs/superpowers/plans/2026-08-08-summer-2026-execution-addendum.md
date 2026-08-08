# Summer 2026 execution addendum

**Date:** 2026-08-08  
**Accepted repository head at this update:** `main@8fadb411e20c6a854fafd2005c5c5b17e868f858`  
**Parent plan:** `docs/superpowers/plans/2026-08-08-post-guide-evidence-and-playback-recovery.md`

This addendum records execution facts and current implementation ordering after the Guide milestone, exact-source CI provenance fix, D1 TV-focus acceptance and provider-readiness contract acceptance. It also corrects one earlier dependency assumption: as of 2026-08-08 the official Android Developers Room3 release page still lists **3.0.0** as the stable Room3 release. There is no verified Room3 `3.0.1` target to implement.

## 1. Accepted state

Accepted sequence on `main`:

- PR #131 / Guide TV route → `286ece017445b811a7adddd4ba7e85cacc5dd3ea`;
- PR #137 / exact PR evidence provenance → `5bb6ee1f754785b2b236d6dcb52fd4458780e758`;
- PR #135 / repository truth + execution plan → `2ea7da253ed5c216099f77c3288474d87816616a`;
- PR #138 / immediate dense-TV custom focus D1 → `d109ad6a4fc68bfb3083edccdf469a67ddb3352f`;
- PR #133 / provider-readiness pure API contract → `8fadb411e20c6a854fafd2005c5c5b17e868f858`.

PR #137 established executable evidence identity: evidence-producing PR workflows checkout the exact source head they claim and fail closed when `git rev-parse HEAD != SourceCommit`.

PR #138 accepted D1 only. It removed queued scale animation from the shared dense custom focus surface, retained native Compose click ownership and added real-key app instrumentation. Issue #111 remains open for D2–D4.

PR #133 accepted a provider-neutral readiness API where an active live catalog is enough for `USABLE`, secondary enrichment is independent, cancellation/supersession preserves previous-good state, and successful attempt revisions must match accepted active truth. Issue #112 remains open as an integration umbrella until a real provider-neutral orchestration path consumes that contract.

## 2. Current critical path

```text
#134 manual exact-head provenance RED → GREEN
→ #140 accepted-main focused 5×10k + 5×50k evidence lane
→ #27 repeated evidence review
→ #139 clean tracked-worktree provenance hardening
→ #30A pure same-channel recovery policy
→ #30B Media3 bounded recovery runtime
→ #30C durable redacted diagnostics only if persistence is required
→ #30D TV Doctor Lite
→ #111 D2–D4 in parallel
→ #33/#93 Lounge Light D5–D7
→ #31 alpha hardening + physical-device evidence
```

Issue #101 remains the CI-efficiency umbrella for eliminating duplicate host/device work only after before/after runner evidence.

## 3. #134 — focused deterministic M3U series

PR #134 remains the active evidence-harness PR. Existing behavior includes:

- sequential 1k/10k/50k focused series;
- fixed seed/source-commit provenance fields;
- five repetitions by default;
- fail-closed ownership of the complete series evidence directory;
- exact corpus SHA-256/byte-count/expected-count consistency across repetitions;
- threshold-free analyzer reuse;
- no parallel execution.

A second provenance defect was found during review: the manual `Invoke-M3uCorpusSeries.ps1` entrypoint accepted `-SourceCommit`, but did not itself verify that the checked-out Git HEAD equaled that SHA. CI workflows are protected by #137, but the README-published manual entrypoint was not self-contained.

Current #134 RED therefore requires the entrypoint to invoke repository-owned `tools/ci/Assert-EvidenceCommit.ps1 -ExpectedCommit $SourceCommit` **before** creating the series evidence directory. Production must remain unchanged until the RED is actually observed.

After observed RED, minimal GREEN is only the provenance-helper existence check + call before evidence creation. Then rerun exact-source Full/variance on the combined current-main tree.

## 4. #140 — accepted-main focused evidence lane

The existing `measurement-variance-smoke.yml` runs the general two-repetition `current-normal` variance series. It does not produce the focused claim-eligible 5× `medium-10k` + 5× `large-50k` M3U datasets.

Issue #140 owns the next CI slice after #134:

- dedicated self-hosted Windows evidence lane;
- accepted `main` commit, not only a PR head;
- exact checkout + `Assert-EvidenceCommit.ps1`;
- sequential 5× `medium-10k` and 5× `large-50k` with seed `20260728`;
- `cancel-in-progress: false` for claim-eligible execution;
- finalize interrupted manifests;
- upload JSON/log evidence;
- no threshold and no parser/runtime change.

Do not make these expensive runs part of every PR.

## 5. #139 — tracked-worktree provenance

`HEAD == SourceCommit` does not by itself prove that a manual checkout has no staged or unstaged **tracked** edits. CI already resets/cleans its workspace, but claim-eligible manual evidence needs an explicit tracked-worktree guard.

After #134:

- reject `git diff --quiet` failure;
- reject `git diff --cached --quiet` failure;
- allow unrelated untracked evidence output;
- run before claim-eligible evidence production;
- preserve the existing exact-head assertion.

Keep this separate from #134 so its provenance RED→GREEN stays reviewable.

## 6. #30A — pure recovery policy

A clean branch `work/30a-playback-recovery-policy` has been started from accepted main with a plan and **RED-only** JVM test. No production recovery code exists yet.

The first RED requires an explicitly preferred same-channel candidate to be first in a deterministic recovery plan. Production types are added only after that RED executes.

Policy sequence:

1. preferred same-channel candidate first;
2. deterministic remaining source order;
3. duplicate variant identity cannot create repeated attempts;
4. foreign canonical-channel candidate is rejected;
5. explicit positive attempt budget;
6. explicit maximum recovery duration; runtime derives monotonic deadline rather than reading wall clock inside the pure policy;
7. terminal vs retryable typed failure disposition;
8. cancellation/supersession invalidates the current generation;
9. stale generation cannot advance a newer generation;
10. temporary fallback success reports the successful candidate without mutating preferred identity.

No hidden retry/deadline defaults are chosen in #30A. M3U parser timing evidence is not sufficient to choose network/playback recovery milliseconds.

## 7. #30B — Media3 runtime boundary

The process-owned `MediaSessionService` / single `ExoPlayer` remains the only player/recovery owner.

Media3 loader retries and MuxTV same-channel candidate switching are distinct layers. Media3 resource fallback must not be used to cross MuxTV catalog/source/credential boundaries.

One total user-visible recovery deadline must include:

- Media3 loader retry/backoff time;
- candidate setup/resolution time;
- MuxTV candidate switching time.

Activity, ViewModel, WorkManager and TV Doctor must never become additional playback retry owners. First rendered frame remains the accepted success boundary.

Typed resolution failures must not be collapsed with `getOrNull()`/nullable generic failure where recovery meaning would be lost.

## 8. #30C / #30D

### Durable diagnostics

Only add persistence if product acceptance requires history/export beyond process lifetime. Before any Room schema change:

- reserve the next schema owner;
- coordinate with #100;
- persist only bounded typed/redacted observations;
- never persist raw locator, Authorization/Cookie, unrestricted headers, credentials or raw exception text containing URLs/tokens.

### TV Doctor Lite

Doctor is presentation over typed observations, not a second probe/player engine.

Acceptance:

- D-pad reachable;
- no focus traps;
- 720p/1080p reachability;
- long Russian text remains actionable/scrollable;
- Back predictable;
- export explicit and redacted;
- export failure cannot mutate playback/catalog state.

## 9. TV design/accessibility after D1

PR #138 D1 is accepted. Remaining #111 work:

- **D2:** native short-press/long-press/repeat ownership only where product controls actually need long click; no global preview-key synthetic clicks;
- migrate representative deprecated Compose Test JUnit4 v1 journeys to v2 with explicit coroutine scheduling where needed;
- **D3:** independent focused/selected/playing/disabled cues + reduced-motion behavior;
- **D4:** 720p/1080p scrolling/reachability, dynamic-removal safe focus fallback and long Russian labels.

Android TV platform behavior remains authoritative. Focus scale is not globally forbidden: it is a valid platform cue when it does not destabilize dense neighboring geometry or queue under rapid D-pad input.

## 10. Lounge Light D5–D7

Only after interaction primitives and playback/Doctor contracts are stable:

- D5 — light theme/tokens, system typography, shell/rail, reduced motion;
- D6 — Channels, Search, Recent, Guide one real surface per reviewable package;
- D7 — Player, Doctor, Sources/settings.

Visual work styles accepted state/navigation contracts; it does not replace focus anchors, active catalog truth, Player/Back identity or feature ViewModels.

## 11. Dependency decisions — corrected 2026-08-08

Current stable stack remains the preferred product path:

- Compose BOM `2026.06.00`;
- Compose for TV `tv-material 1.1.0` / `tv-foundation 1.0.0`;
- Media3 `1.10.1`;
- Room3 `3.0.0`;
- AGP `9.3.0` / Gradle `9.5.0` / JDK 17 compatibility as currently configured.

### Room3 correction

Do **not** create or implement a `3.0.0 → 3.0.1` hardening task unless the official AndroidX Room3 release page actually publishes that version. The earlier addendum statement claiming a stable Room3 `3.0.1` release on 2026-07-29 was not supported by the current official release page and is superseded by this correction.

Dependency version and MuxTV Room schema version remain independent concepts. Room schema stays v10 until a real product migration reserves the next schema owner.

Do not upgrade Media3 to an RC merely because it exists; keep stable unless a concrete fix/feature justifies prerelease risk.

## 12. CI efficiency

Product DeviceMatrix currently runs repository `verify-local -Mode Full` before starting AVDs. A separate Self-hosted validation workflow can therefore duplicate Full host work on the same single Windows runner for product/catalog changes.

This is now recorded under #101. Do not remove coverage ad hoc. Any routing/suite split must prove:

- unchanged required host modules;
- unchanged Product API26/API36 coverage;
- corpus/measurement evidence still runs where relevant;
- lower queue-to-completion / runner wall-time over comparable runs.

## 13. Alpha hardening

Before an alpha-quality claim:

- R8/minification + keep-rule review;
- Baseline Profile module from real TV Critical User Journeys;
- Startup Profiles / DEX layout optimization where supported;
- TTID + TTFD;
- frame timing/jank;
- low-RAM/endurance/repeated-zapping/recreation tests;
- signed release artifact;
- SBOM/provenance;
- physical Android TV / Google TV;
- weak/constrained target;
- Fire TV/Fire OS;
- codec/HDR/audio claims limited to observed hardware.

## 14. Stop conditions

Stop and reassess if:

- source-head/evidence identity breaks;
- claim-eligible manual evidence runs on staged/unstaged tracked changes;
- dense D-pad focus queues geometry animation;
- a preview-key wrapper globally owns OK/Enter;
- Media3 internal retries plus app retries exceed the declared recovery budget;
- a foreign canonical channel can enter fallback;
- temporary fallback rewrites preference;
- a second player/retry owner appears outside the service;
- diagnostics require secrets/raw locators;
- two branches claim the same Room migration version;
- visual work rewrites accepted feature state machines;
- emulator evidence is presented as physical decoder/HDR/Fire OS proof;
- an unverified dependency version is added to the roadmap;
- Rust/UniFFI/libmpv/another engine is introduced without a measured residual problem and separate ADR.
