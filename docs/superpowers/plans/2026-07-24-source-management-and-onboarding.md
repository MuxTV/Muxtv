# Source Management and Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a TV user inspect and operate existing IPTV sources safely, then add a new remote M3U source without exposing URL credentials or replacing the previous-good active catalog until preview/import succeeds.

**Architecture:** PR #18 introduces one Room-backed `SourceRefreshOverview` projection and a D-pad-first Sources screen over `SourceRefreshStore` and `SourceRefreshScheduler`. PR #19 adds a separate credential-aware onboarding coordinator that writes secrets only through `CredentialStore`, performs bounded remote validation/import, and persists only opaque credential references in source metadata.

**Tech Stack:** Kotlin 2.4, Android API 26+, Room 3, WorkManager 2.11, Compose for TV, Hilt, coroutines, existing secure credentials/network/import pipeline.

## Global Constraints

- Never return or display raw source locators, URL user-info, cookies, Authorization values, tokens, User-Agent values or Referrer values from source-management read models.
- Existing active revisions remain usable while refresh or onboarding fails.
- UI mutations must be bounded and idempotent; one source action at a time per source.
- Minimum periodic interval remains 15 minutes.
- `unmeteredOnly` maps to WorkManager `NetworkType.UNMETERED`; charging remains an independent constraint.
- Removing a scheduling policy must not delete the source, credentials or active catalog.
- Deleting a source is deferred until credential cleanup, overlay behavior and confirmation semantics are specified together.

---

## PR #18 — Existing Source Management

### Task 1: Add secret-free source overview projection

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshStore.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomSourceRefreshStore.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/SourceRefreshStoreTest.kt`

**Produces:**
```kotlin
fun SourceRefreshStore.observeOverviews(): Flow<List<SourceRefreshOverview>>
```

`SourceRefreshOverview` contains source ID/name, whether an opaque credential reference exists, active revision number, optional policy, and optional typed refresh status. It contains no credential reference value.

- [ ] Add one left-join Room query across `sources`, `source_refresh_policies`, and `source_refresh_states`.
- [ ] Map missing policy to `null` and missing state to `IDLE`/no timestamps.
- [ ] Verify the projection does not expose `credentialRef` text.
- [ ] Verify policy and completion changes invalidate the observed Flow.

### Task 2: Add Sources feature module

**Files:**
- Create: `feature/sources/build.gradle.kts`
- Create: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourcesRoute.kt`
- Modify: `settings.gradle.kts`
- Modify: `app/tv/build.gradle.kts`

**Behavior:**
- loading, empty, failed and content states;
- source name, active revision and translated typed status;
- no raw technical exception messages;
- stable source IDs as lazy-list keys.

### Task 3: Add scheduling controls

**Interfaces:**
- Consume: `SourceRefreshScheduler.refreshNow`, `updatePolicy`, and `removePolicy`.
- Produce: manual refresh, enabled toggle, interval cycle, unmetered toggle, charging toggle, and policy reset.

- [ ] Default an absent policy to disabled, 60 minutes, connected network and no charging requirement.
- [ ] Offer interval cycle `15 → 60 → 360 → 1440 → 15` minutes.
- [ ] Disable mutation controls while the same source mutation is in flight.
- [ ] Keep manual refresh available independently from periodic policy.
- [ ] Translate `NEEDS_AUTH`, `FAILED`, `CANCELLED`, `RUNNING`, and `SUCCEEDED` without showing family/code internals by default.

### Task 4: Wire Navigation3

- [ ] Add top-level `Sources` destination labeled `Источники`.
- [ ] Inject/use existing Hilt `SourceRefreshStore` and `SourceRefreshScheduler` from `MainActivity`.
- [ ] Preserve Channels/Player back stack behavior.

### Task 5: Tests and completion

- [ ] Room integration test for overview Flow and credential redaction.
- [ ] Compose tests for empty/content and disabled mutation controls.
- [ ] Exact-head Full once implementation stabilizes.
- [ ] API 26/API 36 device matrix before ready-for-review.

---

## PR #19 — Secure Remote M3U Onboarding

### Task 6: Introduce onboarding coordinator

**Produces:**
```kotlin
interface RemoteSourceOnboarding {
    suspend fun validate(input: RemoteSourceInput): RemoteSourcePreviewResult
    suspend fun activate(previewToken: String, sourceName: String): RemoteSourceActivationResult
    suspend fun cancel(previewToken: String)
}
```

- [ ] Normalize and validate scheme/host without logging URL contents.
- [ ] Reject URL user-info unless explicitly migrated into protected credential fields.
- [ ] Store sensitive locator/auth material only in `CredentialStore` under a temporary opaque ID.
- [ ] Perform bounded fetch and streaming M3U preview using the existing secure network/import components.
- [ ] Return counts/warnings and sanitized host/scheme only; never return the full locator.
- [ ] Activate atomically, then persist source metadata with opaque credential reference.
- [ ] Delete temporary credential material on cancel or failed activation.

### Task 7: TV onboarding wizard

- [ ] Step 1: source name.
- [ ] Step 2: locator/auth entry with password masking and QR handoff placeholder.
- [ ] Step 3: preview counts and typed warnings.
- [ ] Step 4: activate and open Channels.
- [ ] D-pad Back cancels temporary state safely.

---

## After PR #19

1. XMLTV secure fetch, streaming parse and atomic EPG revision.
2. Now/Next rows in Channels and player overlay.
3. Preferred variant persistence and previous/next zapping.
4. Typed playback recovery, bounded fallback and TV Doctor Lite.
5. Exact duplicate Smart Channels with manual merge/split journal.
