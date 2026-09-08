# C18 Catch-up Dialect Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic sanitized catch-up compatibility corpus and evidence-gated M3U dialect resolver improvements without changing MuxTV's service-owned playback, semantic seek authority, or credential-safe Xtream architecture.

**Architecture:** Keep `PlaybackIntent`, `ResolvedPlaybackTimeline`, `PlaybackArchiveResolver`, service-owned recovery and Xtream opaque provider references unchanged. Add resolver-owned M3U dialect classification/template materialization behind existing `M3uPlaybackArchiveResolver`, with every supported dialect represented by a secret-safe fixture and a normalized/sanitized golden descriptor. OwnTV/OwnTV_Core is an oracle only where semantics are comparable.

**Tech Stack:** Kotlin, JUnit4, Truth, Android/JVM library tests, existing catalog/refresh and player/api contracts.

**Spec:** #351, parent #348, related #184/#186/#132, `.work/specifications/m3u-ingestion.md`, `.work/quality/benchmark-methodology.md`.

## Global Constraints

- Baseline MuxTV SHA: `9daac297ef4e2c7748e243fc3e912142bbbc6076`.
- OwnTV SHA: `b70af186731fa860da2b99aa05ead5d77f4da927`.
- OwnTV_Core SHA: `630e9c09c80345279e248c336657645300208c09`.
- Correctness precedes performance.
- No local DVR/timeshift storage.
- No second player, retry owner, or semantic seek owner.
- No real provider host, credential, token, or generated secret-bearing URL in fixtures/reports.
- Corpus is deterministic, `.invalid`-only, bounded, JVM-only and performs no network I/O.
- Unknown catch-up placeholders fail catch-up closed while live playback remains unaffected.

---

### Task 1: Freeze the C18 corpus and observe RED

**Files:**
- Create: `catalog/refresh/src/test/resources/compatibility/catchup/manifest-v1.tsv`
- Create: `catalog/refresh/src/test/resources/compatibility/catchup/cases/*.case`
- Create: `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/CatchupDialectCompatibilityCorpusTest.kt`

**Interfaces:**
- Consumes: existing `M3uCatchupTransportResolver`, `M3uCatchupMetadata`, `PlaybackIntent`, `PlaybackArchiveResolution`.
- Produces: versioned case fixtures and a test-side `SanitizedPlaybackDescriptor` used only as a correctness oracle.

- [ ] **Step 1: Add a manifest compatible with #186 corpus rules.**

The header is:

```text
# schema_version=1
id\tpath\tcategory\tdisposition\texpected_a\texpected_b\texpected_c\tsafe_expectation\treference
```

Cases cover `append/{utc}`, `${start}`, `{lutc}`, duration, offset, date tokens, existing-query append, shift/timeshift, Flussonic HLS/TS, xc/xtream, malformed/unknown tokens, correction and retention. Xtream native path/PHP-alternate rows may be reference-only where the current resolver interface cannot safely express a response-aware alternate.

- [ ] **Step 2: Add `.case` fixtures.**

Each case is key/value text with only `.invalid` hosts and synthetic probes. Expected values are descriptor fields (`READY`/typed failure, transport shape, timeline/granularity/media offset); no expected generated locator is stored.

- [ ] **Step 3: Add the corpus test against current production code.**

The test must:

```kotlin
val result = M3uCatchupTransportResolver(nowEpochMillis = { fixture.nowMs })
    .resolve(fixture.intent(), fixture.liveUrl, fixture.metadata())
val descriptor = sanitize(result)
assertThat(descriptor).isEqualTo(fixture.expectedBDescriptor())
```

It also validates manifest completeness, `.invalid` hosts, bounded case sizes, explicit support disposition vocabulary and that diagnostics never contain the synthetic redaction probe.

- [ ] **Step 4: Run RED.**

Run:

```text
./gradlew :catalog:refresh:testDebugUnitTest --tests app.muxtv.catalog.refresh.CatchupDialectCompatibilityCorpusTest
```

Expected on baseline A: failure for at least `${start}`, `{lutc}`/duration/date-token materialization, existing-query join, shift/timeshift, Flussonic and xc/xtream cases. The baseline append `{utc}` and existing typed retention/correction cases remain green.

- [ ] **Step 5: Record the failing run on draft PR evidence.**

The RED run must be a real GitHub Actions/host-validation result from the test-only commit, not an inferred failure.

---

### Task 2: Implement the minimal proven M3U dialect materializer

**Files:**
- Create: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/M3uCatchupDialect.kt`
- Modify: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/M3uCatchupResolver.kt`
- Modify: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/M3uCatchupTransportResolver.kt`
- Modify: focused existing M3U catch-up contract tests only when the prior contract intentionally changes.

**Interfaces:**
- Produces: internal dialect classification and bounded token substitution used only by M3U archive resolution.
- Preserves: public `PlaybackArchiveResolution`, `PlaybackIntent`, `ResolvedPlaybackTimeline`, `M3uPlaybackArchiveResolver` API.

- [ ] **Step 1: Make resolver admission accept only proven shapes.**

Rules:

```text
append                  -> nonblank template required
shift/timeshift         -> no template; standard utc/lutc query
flussonic*              -> no template; CatchupProgram only
xc/xtream               -> no template; CatchupProgram only
anything else           -> UnsupportedMode
known mode, bad shape   -> typed unavailable without template echo
```

Mode matching is normalized with `trim().lowercase(Locale.ROOT)`.

- [ ] **Step 2: Add a bounded whitelist template compiler for append.**

Accept both `${name}` and `{name}`. Proven token families:

```text
start/utc/timestamp/start-timestamp/utcstart
end/utcend/end-timestamp/stop
lutc/now/timenow/currenttime
duration
offset
Y m d H M S
```

Unknown or malformed braces return `InvalidMetadata`; no best-effort pass-through. Limit template length to the accepted M3U attribute bound and token count to the parser attribute-count bound.

- [ ] **Step 3: Preserve MuxTV correction and retention semantics.**

Absolute provider start/end tokens use the corrected provider clock; `duration` stays programme duration; `offset` is relative to the semantic requested position; `lutc` is resolver `now`. Materialized transport start must still be within retention after correction and granularity flooring.

- [ ] **Step 4: Implement transport forms.**

```text
append         -> query-aware join onto live locator
shift          -> append utc/lutc query
flussonic HLS  -> sibling timeshift_abs-<start>.m3u8, preserve existing query
flussonic TS   -> sibling archive-<start>-<duration>.ts, preserve existing query
xc/xtream      -> streaming/timeshift.php form from a recognized M3U Xtream live path
```

`xc/xtream` uses minute-granularity start formatting and a duration that covers the entire requested programme from the minute-aligned transport start.

- [ ] **Step 5: Run GREEN and focused regressions.**

Run:

```text
./gradlew :catalog:refresh:testDebugUnitTest --tests app.muxtv.catalog.refresh.CatchupDialectCompatibilityCorpusTest --tests app.muxtv.catalog.refresh.M3uCatchupResolverContractTest --tests app.muxtv.catalog.refresh.M3uCatchupTransportResolverContractTest --tests app.muxtv.catalog.refresh.M3uPlaybackArchiveResolverContractTest
```

Expected: all selected tests pass and no diagnostics expose fixture probes.

---

### Task 3: Verify native Xtream and classify PHP alternate without adding a retry owner

**Files:**
- Modify or add focused tests under `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/`.
- Do not change the `PlaybackReferenceResolver` result shape unless evidence demonstrates a safe architecture-preserving integration.

**Interfaces:**
- Consumes: `XtreamPlaybackArchiveResolver`, `XtreamPlaybackReferenceResolver`, stored `archiveTimeZoneId`.
- Produces: conformance evidence for path-style timeshift/timezone and an explicit disposition for PHP alternate.

- [ ] **Step 1: Assert current native Xtream path behavior.**

Keep minute flooring, initial media residual, rounded-up duration coverage, retention checks and opaque archive reference behavior.

- [ ] **Step 2: Assert timezone materialization.**

A synthetic credential source with a non-UTC archive timezone must format the same archive instant on that provider clock while keeping credentials absent from `PlaybackArchiveResolution`/diagnostics.

- [ ] **Step 3: Classify OwnTV PHP alternate.**

OwnTV's path-to-`streaming/timeshift.php` alternate is recorded as C reference behavior. If MuxTV has no response-aware alternate-candidate seam that preserves service-owned recovery, mark B `DEFER` rather than adding a second retry owner inside a resolver.

- [ ] **Step 4: Run Xtream contracts.**

```text
./gradlew :catalog:refresh:testDebugUnitTest --tests app.muxtv.catalog.refresh.XtreamPlaybackArchiveResolverContractTest --tests app.muxtv.catalog.refresh.XtreamPlaybackReferenceResolverContractTest --tests app.muxtv.catalog.refresh.XtreamSourceAccessTimeZoneContractTest
```

---

### Task 4: Lifecycle/generation guardrail and evidence

**Files:**
- Reuse existing player/service tests where they already assert stale-generation cancellation; add a focused regression only if C18 touches that seam.
- Create: `.work/reviews/c18-catchup-dialect-compatibility.md`

- [ ] **Step 1: Verify no new playback authority was introduced.**

Search/check that C18 production changes are confined to `catalog/refresh` resolver/materialization; no player/session/service/UI ownership change is permitted.

- [ ] **Step 2: Run repository validation appropriate to a JVM resolver change.**

```text
pwsh -NoProfile -File ./tools/verify-local.ps1 -Mode Fast -SourceBranch c18-catchup-dialect-compat -SourceCommit <candidate-head> -NoDaemon
```

The execution environment records the exact candidate SHA. Device/emulator performance claims are not made for this task.

- [ ] **Step 3: Decide whether resolver overhead is material.**

Only add a resolver benchmark if the correctness implementation introduces a nontrivial hot-path cost (for example repeated unbounded scans/allocations). Otherwise record `not material; no performance gate` because locator resolution is once-per-play and dominated by provider/network/player startup.

- [ ] **Step 4: Write A/B/C report.**

The report contains case counts and sanitized descriptor outcomes only. It names all non-comparable/deferred C behavior and the exact source/candidate/reference SHAs.

---

### Task 5: PR and parent disposition

- [ ] **Step 1: Update #351 Results/Decision with exact candidate SHA and test evidence.**
- [ ] **Step 2: Open PR from `c18-catchup-dialect-compat` to `main`, linking #351 and #348.**
- [ ] **Step 3: Run/inspect PR checks; fix only demonstrated failures.**
- [ ] **Step 4: Comment final C18 disposition on #348.**

Expected disposition if all guardrails hold: **ADAPT** — adopt the proven dialect coverage while retaining MuxTV's stricter unknown-token failure, normalized timeline, retention/correction safety and service-owned recovery architecture.