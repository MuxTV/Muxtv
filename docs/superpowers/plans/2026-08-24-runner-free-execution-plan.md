# MuxTV Runner-Free Execution Plan — 2026-08-24

> This plan exists to keep useful work moving while the self-hosted Android runner is unavailable, without manufacturing unverified GREEN claims or triggering GitHub Actions.

## Goal

Complete every task whose correctness can be established through repository/GitHub/static design evidence alone, prepare deterministic inputs for later TDD, remove stale execution instructions, and stop exactly where the next claim requires an executable host/device RED/GREEN gate.

## Hard operational constraints

- Do not open/update PR heads merely to obtain CI while the runner/Actions budget is unavailable.
- Do not update `.github/workflows/**` in this workstream.
- Do not advance `.github/ui-characterization/run.request`.
- Do not modify PR #189 or #190 heads.
- Do not create any Android TV AVD.
- Repository-owned AVD identities remain exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- Do not claim compile/test/runtime/performance GREEN without an actual executable result.
- Production/configuration code that requires RED -> GREEN waits for an executable host unless a pure/static test can be run independently.

---

# RF0 — Live coordination and truth hygiene

## Purpose

Prevent stale GitHub/docs from sending future agents into already-closed architecture work or violating the two-AVD contract.

### Completed runner-free

- [x] sync durable `.work` snapshot to accepted `main@5aa9c108...` on the non-PR docs branch;
- [x] mark #175 single service-owned seek authority as accepted;
- [x] mark D0/#181 exact two-AVD contract as accepted;
- [x] rewrite #132 as performance/cache residual rather than seek-authority consolidation;
- [x] rewrite #27 measurement ownership/current two-AVD methodology;
- [x] rewrite #31 release/physical evidence contract;
- [x] rewrite #101 database suite selection to reuse canonical AVDs;
- [x] rewrite #93 Lounge design authority around U0/U1 evidence;
- [x] rewrite #33 UI tracking to defer structural correction to U0/U1;
- [x] rewrite #109 measurement-first buffer policy with no low-RAM AVD.

### Remaining static audit

- [ ] scan all open issue bodies for deprecated phrases: `old-edge fallback`, `current-low-ram`, dedicated `low-RAM`, `representative mainstream API`, `MuxTV_*` non-canonical AVD names;
- [ ] update only execution-authoritative issues where the stale text can cause a wrong implementation;
- [ ] do not rewrite historical closed evidence solely for terminology consistency.

Exit: no active execution source instructs an agent to create a third repository-owned AVD or re-open #175 architecture.

---

# RF1 — Dependency ownership split

## Purpose

Turn #190 into an explicit compatibility probe before it can become an accidental mega-PR.

### Completed runner-free

- [x] #146 owns Room3 3.0.1;
- [x] #197 owns Navigation3 1.1.6;
- [x] #198 owns Paging 3.5.1;
- [x] #199 owns Media3 1.11.0;
- [x] #200 owns Compose August 2026 / 1.12;
- [x] #192 owns Tracing 2.0;
- [x] #195 owns later Gradle parallel configuration-cache / Isolated Projects experiments;
- [x] add `docs/dependencies/2026-08-24-stack-split-matrix.md`.

### Still runner-free possible

- [ ] add dedicated AGP/Gradle toolchain owner only if #190 compatibility evidence later proves the combined toolchain needs a separate implementation issue; do not pre-create speculative work;
- [ ] keep Kotlin 2.4.10/KSP 2.3.10 unless a stable, justified upgrade is separately researched and compatible with the accepted toolchain.

Exit: every staged #190 component has an independent owner or explicit keep-current decision.

---

# RF2 — Compatibility corpus A1 data preparation

## Purpose

Prepare small, sanitized real-world-shaped correctness fixtures without touching parser behavior.

### Completed runner-free

- [x] create versioned TSV manifest format without a new test dependency;
- [x] create README/security/disposition contract;
- [x] add synthetic fixtures for:
  - basic `tvg-*`/group/channel metadata;
  - VLC user-agent/referrer options;
  - Kodi property user-agent/referrer options;
  - catch-up metadata;
  - `url-tvg` / `x-tvg-url` EPG header;
  - malformed `#EXTINF` followed by recoverable valid input;
  - deliberate synthetic secret-redaction probes;
- [x] use only `.invalid` hosts and explicit `TEST_*` markers;
- [x] statically verify the manifest/resources tree exists on the non-PR branch.

### Stop boundary

Do **not** add the JVM harness implementation until it can be executed as a real RED -> GREEN sequence. The prepared data is not executable support evidence yet.

Next executable step:

1. add manifest contract test that fails for missing/unread fixture/harness behavior;
2. observe RED;
3. implement minimal loader/assertions;
4. GREEN `:catalog:ingest:test`;
5. only then call A1 accepted behavior evidence.

---

# RF3 — Release evidence and support claims

## Purpose

Define what alpha evidence can and cannot prove before devices are connected.

### Completed runner-free

- [x] #31 support-claim taxonomy;
- [x] `docs/release/2026-08-24-alpha-evidence-and-support-claim-matrix.md`;
- [x] distinguish virtual API correctness from physical device compatibility;
- [x] define required provenance fields;
- [x] define 720p/1080p/compact-stress classification;
- [x] define current TV / constrained TV / Fire TV / codec-HDR / audio-route physical evidence classes;
- [x] define real Macrobenchmark/Baseline Profile CUJs;
- [x] define R8 Analyzer/SBOM/signing evidence boundary.

### Still runner-free possible

- [ ] inventory existing release/signing/SBOM scripts and document the exact missing commands/artifacts without changing workflows;
- [ ] inventory current Baseline Profile/Macrobenchmark journey coverage and produce a gap table against the release CUJ matrix;
- [ ] inventory current R8/ProGuard files and classify broad keep rules for later analyzer review; do not delete rules without executable release evidence.

---

# RF4 — Observability architecture preparation

## Completed runner-free

- [x] #191 WorkManager typed failure boundary;
- [x] #192 secret-safe Tracing 2.0 boundary;
- [x] #193 bounded OkHttp phase-timing boundary;
- [x] design rejects a generic raw telemetry event bus;
- [x] WorkManager initialization diagnostics explicitly avoid Room dependency;
- [x] playback network telemetry explicitly avoids per-segment durable flood;
- [x] implementation plan contains intended files/tests and RED/GREEN boundaries.

### Stop boundary

Do not add production WorkManager/Tracing/OkHttp Kotlin code until the corresponding first RED can execute. These are cross-cutting runtime boundaries and compile-only guessing would be lower confidence than waiting for a real host gate.

---

# RF5 — U0/U1 preparation without contaminating evidence

## Allowed runner-free

- [x] preserve immutable A/B/C refs and current source-fact hypothesis;
- [x] preserve the +50dp shared-shell reservation hypothesis as a hypothesis, not a runtime conclusion;
- [x] keep U1 design direction limited to separating visual rail width from stable content reservation if U0 confirms H1;
- [ ] prepare a U1 regression-test specification in prose only if new static analysis reveals missing assertions.

## Forbidden before U0 runtime

- [ ] no Compose production geometry changes;
- [ ] no token rollback;
- [ ] no per-destination padding compensation;
- [ ] no merge/closure decision on #180 based on static evidence alone;
- [ ] no Compose dependency merge (#200) that moves the baseline before U0/U1.

---

# RF6 — Measurement/performance preparation

## Allowed runner-free

- [x] #27 measurement taxonomy/provenance contract;
- [x] #196 Room pool/FTS5/`WITHOUT ROWID` experiment gate;
- [x] #109 measurement-first buffer-policy contract;
- [x] #132 post-#175 seek/rebuffer/cache residual scope;
- [ ] statically inventory heavy DB query owners and map them to future `EXPLAIN QUERY PLAN` cases;
- [ ] statically inventory current M3U parser allocation hotspots as hypotheses only;
- [ ] prepare metric field schemas/taxonomy where no runtime behavior changes.

## Forbidden without valid measurement

- no Room pool count changes;
- no FTS5 migration;
- no `WITHOUT ROWID` conversion;
- no M3U parser optimization claimed as beneficial;
- no LoadControl/back-buffer/cache threshold changes;
- no Compose performance mass-refactor.

---

# RF7 — CI/toolchain work

## Allowed runner-free

- [ ] inspect workflow/path ownership statically and document obsolete trigger assumptions;
- [ ] inspect Android toolchain resolution code and prepare a pinning decision record;
- [ ] define provisioning manifest schema only after the real runner's installed versions are known from accepted evidence.

## Forbidden now

- no workflow commits;
- no Actions reruns;
- no hosted-runner workaround;
- no branch/PR write that exists only to trigger CI;
- no invented Android command-line-tools/emulator version pin without inventory evidence.

---

# Priority order while runner remains unavailable

1. RF0 active-issue stale-instruction scan.
2. RF3 release script/Baseline Profile/R8 static inventory.
3. RF6 DB-query and parser-hotspot inventory as measurement hypotheses.
4. RF7 toolchain/workflow static audit only if it reveals a real blocker.
5. Stop before production code where the first meaningful correctness signal requires Gradle/Android execution.

Do not use runner downtime as justification to expand alpha product scope into Xtream, catch-up, DVR, VOD, multiview, alternate playback engines or speculative provider architecture.

---

# Resume sequence when executable host/device validation returns

1. freeze and run #189 U0 exact-head evidence;
2. implement U1 RED -> minimal GREEN;
3. restack/complete #178 M0;
4. use #190 only as combined stack compatibility probe;
5. cut isolated dependency owners (#146/#197/#198/#199/#200/toolchain);
6. start #191 first real RED -> GREEN, then #192/#193;
7. execute #186 A1 harness RED -> GREEN;
8. gather valid #27/#31 measurement/release evidence before runtime tuning.

This ordering preserves causal attribution and prevents runner downtime from becoming a source of speculative product changes.
