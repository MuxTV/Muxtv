# Bounded Guide Data Design

## Objective

Provide a database-backed TV Guide viewport without loading the full catalog or full EPG into memory. The package owns only the bounded data contract; Guide destination state, Compose grid, D-pad navigation and Player/Back restoration are separate follow-up work.

## Existing boundaries

- `EpgGuideRepository` remains the Now/Next owner.
- Accepted Playback, Search, Recent and Now/Next reads already enforce active source revision and selected-profile visibility.
- `EpgGuideDao` already independently revalidates that a matched canonical identity has an active stream variant.
- Room remains schema v10; no table or index change is required.

## New repository boundary

Add a separate `GuideWindowRepository` rather than widening `EpgGuideRepository` into unrelated viewport concerns.

```kotlin
interface GuideWindowRepository {
    suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow
    suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow
    fun observeDataChanges(): Flow<Unit>
}
```

`RoomEpgGuideRepository` implements both interfaces so both paths consume the same DAO and invalidation source without introducing a second owner.

## Channel keyset window

### Query

```kotlin
class GuideChannelWindowQuery(
    val profileId: String,
    val after: GuideChannelCursor? = null,
    val limit: Int = 30,
)
```

- `limit` is 1..50.
- The DAO requests `limit + 1`; the repository returns at most `limit`.
- `isTruncated` is true only when the extra row exists.
- `nextCursor` is non-null only when `isTruncated` is true.
- No exact total is inferred from the row count.

### Stable order

The sort tuple is:

1. explicit profile channel number present before missing;
2. numeric profile channel number ascending;
3. effective display name `COLLATE NOCASE`;
4. canonical channel ID `COLLATE BINARY`.

The cursor stores the exact tuple required to continue:

```kotlin
data class GuideChannelCursor(
    val channelNumber: Int?,
    val displayName: String,
    val canonicalChannelId: String,
)
```

A stale cursor may produce an empty or shorter continuation after catalog/profile changes, but must never reveal hidden, staged or previous-revision channels.

### Row payload

Reuse `PlayableChannelSummary`. Locator, headers, credential references and provider identifiers are excluded.

## Programme time window

### Query

```kotlin
class GuideProgrammeWindowQuery(
    val profileId: String,
    canonicalChannelIds: List<String>,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val limit: Int = 1_000,
)
```

- copies the channel ID list;
- maximum 50 distinct, nonblank IDs;
- `fromEpochMillis >= 0`;
- `toEpochMillis > fromEpochMillis`;
- maximum requested span is 12 hours;
- `limit` is 1..2,000;
- DAO requests `limit + 1` for explicit global truncation.

### Effective end and overlap

A programme has an effective end only when:

1. its explicit stop is greater than start; or
2. for an open-ended programme, the next programme start for the same EPG source/revision/external channel is greater than start.

An item overlaps the viewport when:

```text
start < windowEnd
AND effectiveEnd > windowStart
```

An open-ended programme without a following programme is not treated as infinite and is excluded, matching accepted Now/Next behavior.

### Membership and states

Inside one Room transaction:

1. resolve current match counts for requested canonical IDs using the accepted active/profile-visible predicate;
2. query programmes only for IDs with exactly one current match;
3. produce one result for every requested ID, preserving input order:
   - `READY` with zero or more cells;
   - `NO_GUIDE` with no cells;
   - `SOURCE_CONFLICT` with no cells.

Multiple active playback variants do not multiply EPG match counts.

### Stable programme identity

```kotlin
data class GuideProgrammeKey(
    val epgSourceId: String,
    val epgRevisionNumber: Long,
    val sequenceNumber: Long,
)
```

Rows are ordered by canonical ID, start time, EPG source ID, revision and sequence. Programme title is permitted in the UI model but redacted from `toString()`.

## Result models

```kotlin
data class GuideChannelWindow(
    val channels: List<PlayableChannelSummary>,
    val nextCursor: GuideChannelCursor?,
    val isTruncated: Boolean,
)

data class ChannelGuideProgrammeWindow(
    val canonicalChannelId: String,
    val state: GuideProjectionState,
    val programmes: List<GuideProgrammeCell>,
)

data class GuideProgrammeWindow(
    val channels: List<ChannelGuideProgrammeWindow>,
    val isTruncated: Boolean,
)
```

Non-`READY` channel results must not carry programme payload.

## Error and privacy contract

Invalid bounds fail at API construction with `IllegalArgumentException`. Repository/DAO failures propagate to the future Guide state owner as typed loading failures; this package does not introduce retries. `toString()` methods redact profile IDs, canonical IDs, display names and programme titles and never expose playback access data.

## Acceptance

- API contract tests for bounds, defensive copies, cursor/result invariants and redaction.
- Room integration tests for deterministic keyset pages, hidden/staged/stale exclusion, revision swap, conflict, overlap, open-ended effective end and explicit truncation.
- Existing Now/Next tests remain green.
- No Room v11 or full-guide materialization.
- Exact-head Full plus old-edge/current database acceptance before merge.
