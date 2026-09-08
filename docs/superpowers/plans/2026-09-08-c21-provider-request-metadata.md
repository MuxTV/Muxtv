# C21 Provider Request Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement evidence-gated, bounded provider-neutral per-item HTTP request metadata from M3U syntax through persisted catalog resolution into exact Media3 manifest/segment requests.

**Architecture:** Normalize playlist syntax into immutable `ProviderRequestMetadata` in `core:model`, persist it on stream variants through Room v11, carry it through `ResolvedPlaybackRequest` and internal playback request objects, then project headers by request target in `player:media3`. Existing exact-origin, redirect, sensitive-header and single-player ownership remain authoritative.

**Tech Stack:** Kotlin 2.4.10, Room 3.0.0, Media3 1.11.0, OkHttp 5.5.0, MockWebServer3, kotlinx.serialization JSON 1.11.0, JUnit 4, Truth, Coroutines Test.

**Spec:** `docs/superpowers/specs/2026-09-08-c21-provider-request-metadata-design.md`

## Global Constraints

- Baseline is `MuxTV/Muxtv@9daac297ef4e2c7748e243fc3e912142bbbc6076`.
- Reference SHAs are OwnTV `b70af186731fa860da2b99aa05ead5d77f4da927` and OwnTV_Core `630e9c09c80345279e248c336657645300208c09`.
- No production parser behavior changes before a failing compatibility test is observed.
- `ProviderRequestMetadata` must be bounded, immutable and provider-neutral.
- Parser syntax (`#EXTVLCOPT`, `#EXTHTTP`, `#KODIPROP`, URL pipe) must not enter `player:media3`.
- Existing exact-origin cleartext approval, redirect policy and `SensitiveHeaderPolicy` remain authoritative.
- No raw locator/query/header values in logs, Doctor, Room diagnostics, traces, benchmark JSON, screenshots, UI semantics or `toString()`.
- One service-owned Media3 player/session remains authoritative.
- No new retry owner, network stack, player engine or persistent AVD.
- Correctness/security gates run before performance evidence is considered.
- Room schema change is isolated to v11 and migration-tested.

---

### Task 1: Define the bounded provider-neutral metadata model

**Files:**
- Create: `core/model/src/main/kotlin/app/muxtv/model/ProviderRequestMetadata.kt`
- Create: `core/model/src/test/kotlin/app/muxtv/model/ProviderRequestMetadataTest.kt`

**Interfaces:**
- Produces: `enum class ProviderRequestTarget { MANIFEST, SEGMENT, LICENSE }`
- Produces: `ProviderRequestHeaderPolicy.canonicalize(rawName: String): ProviderRequestHeaderDecision`
- Produces: `ProviderRequestMetadata(defaultHeaders, manifestHeaders, segmentHeaders, licenseHeaders)`
- Produces: `ProviderRequestMetadata.headersFor(target: ProviderRequestTarget): Map<String, String>`
- Produces: `ProviderRequestMetadata.EMPTY`

- [ ] **Step 1: Write failing header-policy tests.**

Tests must assert:

```kotlin
assertThat(ProviderRequestHeaderPolicy.canonicalize("user-agent"))
    .isEqualTo(ProviderRequestHeaderDecision.Accepted("User-Agent"))
assertThat(ProviderRequestHeaderPolicy.canonicalize("REFERRER"))
    .isEqualTo(ProviderRequestHeaderDecision.Accepted("Referer"))
assertThat(ProviderRequestHeaderPolicy.canonicalize("Host"))
    .isEqualTo(ProviderRequestHeaderDecision.Forbidden)
assertThat(ProviderRequestHeaderPolicy.canonicalize("X-Unbounded-Custom"))
    .isEqualTo(ProviderRequestHeaderDecision.Unsupported)
assertThat(ProviderRequestHeaderPolicy.canonicalize("bad:name"))
    .isEqualTo(ProviderRequestHeaderDecision.Malformed)
```

- [ ] **Step 2: Run `:core:model:test` and record the expected RED because the C21 model/policy does not exist.**

- [ ] **Step 3: Implement the minimal canonical policy.**

Use the exact accepted canonical set:

```text
User-Agent, Referer, Origin, Authorization, Cookie,
X-Api-Key, X-Auth-Token, X-Access-Token
```

and forbidden set:

```text
Host, Content-Length, Connection, Transfer-Encoding, Range, Proxy-Authorization
```

Unknown syntactically valid names return `Unsupported`; malformed names return `Malformed`.

- [ ] **Step 4: Write failing metadata bounds/immutability/target tests.**

Cover:

```kotlin
val source = linkedMapOf("User-Agent" to "A")
val metadata = ProviderRequestMetadata(
    defaultHeaders = source,
    manifestHeaders = mapOf("User-Agent" to "Manifest"),
    segmentHeaders = mapOf("Origin" to "https://portal.invalid"),
    licenseHeaders = mapOf("Authorization" to "Bearer synthetic"),
)
source["User-Agent"] = "mutated"

assertThat(metadata.headersFor(ProviderRequestTarget.MANIFEST)).containsExactly(
    "User-Agent", "Manifest",
)
assertThat(metadata.headersFor(ProviderRequestTarget.SEGMENT)).containsExactly(
    "User-Agent", "A",
    "Origin", "https://portal.invalid",
)
assertThat(metadata.headersFor(ProviderRequestTarget.LICENSE)).containsExactly(
    "Authorization", "Bearer synthetic",
)
```

Also assert max 32 accepted entries across scopes, max name 64, max value 8192, and CR/LF/NUL rejection.

- [ ] **Step 5: Implement immutable snapshots, validation and `headersFor`.**

LICENSE must not inherit DEFAULT headers. MANIFEST and SEGMENT merge DEFAULT then target override in insertion-stable maps.

- [ ] **Step 6: Add redaction test.**

`ProviderRequestMetadata.toString()` must expose only scope counts and never contain synthetic secret values or header names.

- [ ] **Step 7: Run `:core:model:test` and require GREEN before proceeding.**

- [ ] **Step 8: Commit Task 1.**

Commit message:

```text
feat(c21): add bounded provider request metadata model
```

### Task 2: Add RED compatibility fixtures before parser behavior

**Files:**
- Modify: `catalog/ingest/build.gradle.kts`
- Modify: `catalog/ingest/src/test/resources/compatibility/m3u/manifest-v1.tsv`
- Modify: `catalog/ingest/src/test/kotlin/app/muxtv/catalog/ingest/M3uCompatibilityCorpusTest.kt`
- Create fixtures under: `catalog/ingest/src/test/resources/compatibility/m3u/request-metadata/`

**Interfaces:**
- Consumes: `ProviderRequestMetadata`, `ProviderRequestTarget`, `ProviderRequestHeaderPolicy`
- Expected future parser output: `M3uEntry.requestMetadata: ProviderRequestMetadata`

- [ ] **Step 1: Add `implementation(project(":core:model"))` to `catalog:ingest`.**

This is a domain dependency only; `core:model` has no Android/Room/Media3 dependency.

- [ ] **Step 2: Extend the corpus manifest with sanitized fixture IDs.**

Add exactly these entries:

```text
request-ua-only
request-referer-only
request-origin-only
request-multiple-headers
request-duplicate-case-precedence
request-ext-http
request-kodi-stream-headers
request-kodi-manifest-headers
request-url-pipe
request-malformed-header
request-forbidden-header
request-secret-redaction
```

All fixture network hosts must end in `.invalid`. Secret probes use conspicuous test strings such as `TEST_C21_SECRET`.

- [ ] **Step 3: Create the fixture contents.**

Representative required examples:

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="c21.ext-http",C21 EXTHTTP
#EXTHTTP:{"User-Agent":"C21-Agent/1","Origin":"https://portal.invalid","Authorization":"Bearer TEST_C21_SECRET"}
https://streams.invalid/live/master.m3u8
```

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="c21.kodi",C21 Kodi
#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=C21-Segment%2F1&Origin=https%3A%2F%2Fportal.invalid
#KODIPROP:inputstream.adaptive.manifest_headers=User-Agent=C21-Manifest%2F1
https://streams.invalid/live/master.m3u8
```

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="c21.pipe",C21 Pipe
#EXTVLCOPT:http-user-agent=C21-Directive/1
https://streams.invalid/live/master.m3u8|USER-AGENT=C21-Pipe%2F1&Referer=https%3A%2F%2Fportal.invalid%2F
```

Malformed/forbidden fixtures must not contain real hosts or credentials.

- [ ] **Step 4: Extend corpus semantic assertions to require `entry.requestMetadata`.**

Assertions must cover target-specific `headersFor`, duplicate/case canonicalization, pipe suffix locator stripping, and secret-safe `toString()`.

- [ ] **Step 5: Run `:catalog:ingest:test` and record RED.**

Expected failure reason must be the missing `M3uEntry.requestMetadata`/unsupported syntax, not a broken fixture path or manifest declaration.

- [ ] **Step 6: Commit the RED-only fixture/test state.**

Commit message:

```text
test(c21): define request metadata compatibility corpus
```

Do not add parser production support in this commit.

### Task 3: Implement bounded M3U request-metadata parsing

**Files:**
- Modify: `catalog/ingest/src/main/kotlin/app/muxtv/catalog/ingest/StreamingM3uParser.kt`
- Test: `catalog/ingest/src/test/kotlin/app/muxtv/catalog/ingest/M3uCompatibilityCorpusTest.kt`
- Test: `catalog/ingest/src/test/kotlin/app/muxtv/catalog/ingest/StreamingM3uParserTest.kt`

**Interfaces:**
- Produces: `M3uEntry.requestMetadata: ProviderRequestMetadata`
- Produces secret-free warning kinds for malformed/forbidden metadata.

- [ ] **Step 1: Add a pending request-metadata accumulator scoped to one `#EXTINF` entry.**

Use separate linked maps for DEFAULT, MANIFEST and SEGMENT. Do not retain raw directive text after normalization.

- [ ] **Step 2: Map existing EXTINF UA/referrer aliases into DEFAULT metadata while preserving existing `M3uEntry.userAgent`/`referrer` values as compatibility projections.**

- [ ] **Step 3: Extend `#EXTVLCOPT` parsing.**

Only `http-<allowed>` keys are request metadata. Canonicalize through `ProviderRequestHeaderPolicy`. Unsupported/forbidden/malformed names are ignored with secret-free warning classification.

- [ ] **Step 4: Implement bounded `#EXTHTTP` JSON parsing.**

Use the module's existing kotlinx.serialization JSON dependency. Accept only a JSON object of string scalar values. Never include raw JSON/value content in warnings/exceptions.

- [ ] **Step 5: Implement Kodi scoped header properties.**

`stream_headers` / `stream_header` populate SEGMENT; `manifest_headers` populates MANIFEST. Parse ampersand pairs and percent-decode values without throwing on malformed escapes.

- [ ] **Step 6: Implement recognized URL-pipe suffix parsing.**

Strip `|...` from locator only when it structurally represents header pairs. Accepted suffix headers populate DEFAULT and are applied after directives. A non-header literal pipe remains in the locator.

- [ ] **Step 7: Enforce hard request-metadata bounds.**

Add explicit `M3uLimitReason` values if needed so >32 accepted scoped entries or oversized accepted values cannot accumulate silently.

- [ ] **Step 8: Run `:catalog:ingest:test` and require GREEN for the new corpus and existing parser suite.**

- [ ] **Step 9: Commit Task 3.**

Commit message:

```text
feat(c21): parse bounded M3U request metadata
```

### Task 4: Propagate metadata through importer and immutable staging

**Files:**
- Modify: `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogImportEntry.kt`
- Modify: `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/M3uCatalogImportAdapter.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/SourceRevisionStore.kt`
- Modify focused importer tests that construct `CatalogImportEntry` / `StagedCatalogEntry`
- Create/modify a focused importer contract test proving M3U metadata reaches staging.

**Interfaces:**
- `CatalogImportEntry.requestMetadata: ProviderRequestMetadata`
- `StagedCatalogEntry.requestMetadata: ProviderRequestMetadata`

- [ ] **Step 1: Write a failing importer contract test.**

Use a synthetic `M3uEntry`/feed carrying DEFAULT + MANIFEST + SEGMENT metadata and assert the `StagedCatalogEntry` seen by a recording `SourceRevisionStore` contains equal immutable metadata.

- [ ] **Step 2: Run focused `:catalog:importer:test` and record RED.**

- [ ] **Step 3: Add metadata fields with `ProviderRequestMetadata.EMPTY` defaults where constructor compatibility is required.**

- [ ] **Step 4: Map `M3uEntry.requestMetadata` through `M3uCatalogImportAdapter` and revision staging.**

No syntax-specific strings are introduced outside ingest.

- [ ] **Step 5: Run importer tests and require GREEN.**

- [ ] **Step 6: Commit Task 4.**

Commit message:

```text
feat(c21): carry request metadata through catalog staging
```

### Task 5: Add Room v11 persistence and legacy fallback

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/StreamVariantEntity.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomSourceRevisionStore.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/ProviderRequestMetadataStorage.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/RequestMetadataMigration.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/CurrentDatabaseMigrations.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/PlaybackCatalogDao.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/RequestMetadataMigration10To11ContractTest.kt`
- Modify/Create: focused `PlaybackCatalogTest` request-metadata assertions
- Add generated schema: `core/database/schemas/app.muxtv.database.MuxTvDatabase/11.json`

**Interfaces:**
- Persisted columns: `requestHeadersDefault`, `requestHeadersManifest`, `requestHeadersSegment`, `requestHeadersLicense`
- Produces: database-owned deterministic header-map encode/decode helpers.

- [ ] **Step 1: Write failing pure codec tests for the database-owned line encoding.**

Each canonical map encodes as stable `Key: Value` lines; empty map encodes null; malformed stored lines decode as failure/empty metadata without exposing raw values.

- [ ] **Step 2: Write failing v10→v11 migration test.**

Migration SQL must add the four nullable TEXT columns to `stream_variants` and leave all existing rows/legacy UA/referrer untouched.

- [ ] **Step 3: Write failing playback-catalog persistence test.**

Stage/activate a variant with DEFAULT + MANIFEST + SEGMENT metadata, resolve it, and assert the resulting normalized metadata is equal. Add a second legacy-only row and assert UA/referrer become DEFAULT metadata when all v11 columns are null.

- [ ] **Step 4: Run the focused database host/device test surfaces and record RED before production schema changes.**

- [ ] **Step 5: Implement deterministic storage codec.**

Never log or include encoded values in exception messages or `toString()`.

- [ ] **Step 6: Add v11 columns and migration.**

Update `CURRENT_DATABASE_VERSION` to 11 and append `MIGRATION_10_11` to `CURRENT_DATABASE_MIGRATIONS`.

Migration SQL:

```sql
ALTER TABLE `stream_variants` ADD COLUMN `requestHeadersDefault` TEXT;
ALTER TABLE `stream_variants` ADD COLUMN `requestHeadersManifest` TEXT;
ALTER TABLE `stream_variants` ADD COLUMN `requestHeadersSegment` TEXT;
ALTER TABLE `stream_variants` ADD COLUMN `requestHeadersLicense` TEXT;
```

- [ ] **Step 7: Persist encoded scope maps in `RoomSourceRevisionStore`.**

Continue populating `userAgent` / `referrer` for backward compatibility from DEFAULT metadata where present.

- [ ] **Step 8: Select/decode v11 columns in `PlaybackCatalogDao` and `RoomPlaybackCatalog`.**

If all four columns are null, synthesize DEFAULT metadata from legacy UA/referrer. If any v11 column is present, the v11 metadata is authoritative.

- [ ] **Step 9: Generate and commit Room v11 schema and run `verifyCurrentRoomSchema`.**

- [ ] **Step 10: Run migration/database tests and require GREEN.**

- [ ] **Step 11: Commit Task 5.**

Commit message:

```text
feat(c21): persist request metadata in Room v11
```

### Task 6: Carry normalized metadata into resolved and internal playback requests

**Files:**
- Modify: `catalog/api/src/main/kotlin/app/muxtv/catalog/PlaybackCatalog.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomPlaybackCatalog.kt`
- Modify: `player/api/build.gradle.kts`
- Modify: `player/api/src/main/kotlin/app/muxtv/player/PlaybackModels.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSessionRequest.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Modify focused tests for `ResolvedPlaybackRequest`, `PlaybackRequest`, `PlaybackSessionRequest`, recovery/install.

**Interfaces:**
- `ResolvedPlaybackRequest.requestMetadata: ProviderRequestMetadata`
- `PlaybackRequest.requestMetadata: ProviderRequestMetadata`
- `PlaybackSessionRequest.requestMetadata: ProviderRequestMetadata`
- Legacy `.requestHeaders` remains a DEFAULT compatibility projection during C21.

- [ ] **Step 1: Write failing compatibility/redaction tests.**

Construct requests using the old `requestHeaders` argument and assert equivalent DEFAULT metadata. Construct candidate production requests from metadata and assert legacy `requestHeaders == metadata.defaultHeaders`.

- [ ] **Step 2: Assert `ResolvedPlaybackRequest.toString()` contains only metadata counts, never header names or synthetic values.**

This test must fail against the baseline because the current implementation prints header names.

- [ ] **Step 3: Run focused catalog/player host tests and record RED.**

- [ ] **Step 4: Add `api(project(":core:model"))` to `player:api`.**

- [ ] **Step 5: Extend request classes with immutable metadata while preserving source compatibility.**

Do not create two mutable truths. Production construction derives legacy DEFAULT map from metadata; old callers synthesize metadata from their existing map.

- [ ] **Step 6: Update `RoomPlaybackCatalog.toRequest` and service `install` to carry `requestMetadata`.**

The MediaSession setup Bundle remains unchanged because resolution occurs inside `MuxTvPlaybackService`.

- [ ] **Step 7: Replace header-name output in `ResolvedPlaybackRequest.toString()` with safe scope counts.**

- [ ] **Step 8: Run focused tests and require GREEN.**

- [ ] **Step 9: Commit Task 6.**

Commit message:

```text
feat(c21): resolve provider request metadata to playback
```

### Task 7: Add exact manifest/segment Media3 request adapters

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackMediaSourceFactory.kt`
- Modify: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackMediaSourceFactoryTest.kt`
- Add focused helper/test fixtures in the same androidTest package if needed.

**Interfaces:**
- Consumes: `PlaybackSessionRequest.requestMetadata`
- Consumes: `ProviderRequestMetadata.headersFor(MANIFEST|SEGMENT)`
- Existing OkHttp redirect/security interceptors remain downstream.

- [ ] **Step 1: Write RED HLS MockWebServer tests.**

Serve deterministic HLS:

```text
/master.m3u8 -> references /segment.ts
/segment.ts -> small synthetic body
```

Request metadata:

```text
DEFAULT: Referer=https://portal.invalid/
MANIFEST: User-Agent=C21-Manifest/1
SEGMENT: User-Agent=C21-Segment/1, Origin=https://portal.invalid
```

Assert:

- `/master.m3u8` receives Referer + manifest UA and no segment-only Origin;
- `/segment.ts` receives Referer + segment UA + Origin.

- [ ] **Step 2: Write RED A→B isolation test using two HLS requests.**

A carries Authorization/Cookie/scope overrides. B carries only its own UA. B manifest and segment must contain none of A's provider metadata.

- [ ] **Step 3: Write RED redirect tests.**

Same-origin redirect preserves intended target metadata. Cross-origin redirect/subresource strips MuxTV-sensitive headers according to existing network policy.

- [ ] **Step 4: Run focused `PlaybackMediaSourceFactoryTest` on the canonical connected test lane and record RED.**

- [ ] **Step 5: Implement HLS target-aware `HlsDataSourceFactory`.**

Map Media3 manifest data type to MANIFEST and media/chunk data to SEGMENT. Build request-local `OkHttpDataSource` instances using the existing `MuxTvHttpClients.playbackFor` client.

- [ ] **Step 6: Implement DASH separate manifest/chunk factories.**

Use a MANIFEST data source for MPD and a SEGMENT data source for `DefaultDashChunkSource.Factory`.

- [ ] **Step 7: Keep progressive/raw TS on SEGMENT headers and AUTO on conservative DEFAULT behavior.**

Do not parse or infer M3U/Kodi syntax here.

- [ ] **Step 8: Run Media3 component tests and require GREEN.**

- [ ] **Step 9: Commit Task 7.**

Commit message:

```text
feat(c21): scope Media3 request metadata by target
```

### Task 8: Run A/B/C correctness evidence and redaction audit

**Files:**
- Add C21-compatible scenario/corpus descriptor under existing `benchmark/competitive/` structure if C01 has landed and exposes a stable contract; otherwise keep C21 evidence in issue/test artifacts without inventing a parallel framework.
- Modify: `docs/superpowers/specs/2026-09-08-c21-provider-request-metadata-design.md` only if measured implementation materially deviates from accepted design.

**Interfaces:**
- A: baseline MuxTV SHA `9daac297...`
- B: exact candidate head
- C: OwnTV_Core SHA `630e9c09...`

- [ ] **Step 1: Run the sanitized parser corpus against A and record capability failures as expected outcomes, not test-framework failures.**

A must demonstrate the known gap for `#EXTHTTP`, URL pipe, Origin and scoped Kodi headers.

- [ ] **Step 2: Run the same syntax corpus against B.**

Require all accepted C21 cases to satisfy the correctness oracle.

- [ ] **Step 3: Run/derive C reference behavior from the pinned OwnTV_Core parser on the same sanitized syntax inputs.**

Record only normalized header names/counts/digests and disposition outcomes. Do not persist raw secret values.

- [ ] **Step 4: Run redaction scans/tests.**

Probe synthetic secret strings across:

- parser/model/request `toString()`;
- Doctor/diagnostic test projections touched by C21;
- trace/evidence outputs;
- benchmark/report artifact if present.

Any raw secret match is a correctness failure.

- [ ] **Step 5: Record final disposition.**

Use **ADAPT** only when B passes all correctness/security gates. Otherwise use REJECT or DEFER with evidence.

### Task 9: Exact-head qualification and focused PR

**Files:**
- No unrelated production files.
- Update issue #352 and parent #348 after evidence is final.

**Interfaces:**
- Exact candidate HEAD is the only acceptable completion evidence.

- [ ] **Step 1: Run fresh host validation for the exact candidate head.**

At minimum include relevant pure/unit tests, Android unit tests, lint, instrumentation-test compilation, Room schema verification and release/debug assembly through the repository's existing validation lane.

- [ ] **Step 2: Run the relevant database migration/device evidence on the canonical API26/API36 strategy.**

No third persistent AVD.

- [ ] **Step 3: Run focused Media3/MockWebServer instrumentation for exact-head request propagation.**

- [ ] **Step 4: Inspect every failed job/log rather than rerunning blindly.**

Fix product/test defects through TDD; rerun only the affected failed job/run when appropriate.

- [ ] **Step 5: Open or update one focused PR from `feature/c21-provider-request-metadata` to `main`.**

PR body must link #352, #348, #184 and #186 and include exact A/B/C/redaction evidence.

- [ ] **Step 6: Do not mark the PR ready or claim completion until exact-head required checks are green.**

- [ ] **Step 7: Post the mandatory final-result comment to #352.**

Include decision, exact SHAs, corpus, correctness result, guardrails, PR and implementation deviations.

- [ ] **Step 8: Update #348 C21 status/link only after the child final comment exists.**

- [ ] **Step 9: If merged, record merge SHA in #352/#348 and close #352 with the accepted disposition.**
