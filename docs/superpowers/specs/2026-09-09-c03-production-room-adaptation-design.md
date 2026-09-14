# C03 Production Room Adaptation Design

**Owner:** #364  
**Umbrella:** #348  
**Evidence:** #354 / PR #355  
**Evidence disposition:** `ADAPT`  
**Baseline:** `main@1a1af7b5f7d8d6f7d8255463bc4dc81ea4c5412a`

## 1. Objective

Adapt the accepted C03 concept to the production Room model without importing the benchmark-only SQLite schema and without weakening MuxTV publication safety.

The target contract is:

```text
stable logical identity
+ content hash
+ immutable payload reuse
+ revision membership/order
```

while preserving:

```text
immutable staging
→ guarded atomic publication
→ previous-good retention
→ refresh-owner / credential / generation safety
→ terminal cancellation
```

This design is intentionally limited to catalog persistence. It does not change Xtream behavior, playback, EPG policy, UI, or provider transport.

## 2. Current production constraints

At database version 10, `provider_channels` contains both revision membership (`sourceId`, `revisionNumber`) and the provider payload fields. `stream_variants` is keyed through the revision-scoped provider row. Provider search documents are keyed by and point to the revision-scoped `providerChannelId`.

Consequences:

1. Every successful revision physically inserts provider payload, stream payload, and provider search rows even when content is unchanged.
2. `providerChannelId` and `streamVariantId` are generated from `sourceId + revisionNumber + ordinal`, so they are physical revision-row identities rather than stable logical identities.
3. Active browse/search truth is selected by joining provider rows to `sources.activeRevision`.
4. Current activation already owns the correct safety boundary: guarded activation checks credential/run-token ownership, atomically changes `sources.activeRevision`, retains the prior active revision, and removes obsolete revision state.
5. Import cancellation already performs best-effort non-cancellable discard and rethrows cancellation as terminal.

Therefore the adaptation must separate reusable content from revision membership while leaving activation ownership conceptually unchanged.

## 3. Options considered

### A. Add hashes to current revision rows

Keep `provider_channels` and `stream_variants` revision-scoped and add content hashes.

Rejected as the production candidate because unchanged rows still need full revision-row insertion. Hashes would improve classification but not materially remove the write amplification demonstrated by C03.

### B. Immutable payload versions + revision memberships — selected

Store immutable provider/stream content independently of a revision. Every revision stores only membership/order plus references to immutable payload versions. Reuse existing payload/search payload versions when hashes match.

This is the smallest model that can express C03 write-elision while preserving immutable revision staging and previous-good references.

### C. Materialized active projection

Keep immutable payload/membership history but additionally rewrite a dedicated active projection on every publication.

Not selected initially. It reduces active read joins but recreates publication write amplification and adds another rollback/publication surface. It remains a fallback only if actual Room evidence shows a credible sustained active read/search regression that cannot be fixed by indexing/query shape.

## 4. Production candidate model

The names below are production design names, not benchmark table names.

### 4.1 `CatalogPayloadEntity`

Immutable content version for one staged catalog occurrence.

Conceptual fields:

```text
payloadId                PK, deterministic opaque SHA-256 id
sourceId                 FK sources.id
logicalChannelId         stable source-scoped logical identity
contentHash              SHA-256, versioned canonical payload digest
contentHashVersion       integer policy version
canonicalChannelId       FK canonical_channels.id
providerKey              existing normalized provider key
rawName
tvgId
tvgName
logoUrl
groupTitle
channelNumber
catchupMode
catchupSource
catchupDays
catchupCorrection
locator
userAgent
referrer
searchPayloadId          FK CatalogSearchPayloadEntity
```

Uniqueness contract:

```text
UNIQUE(sourceId, logicalChannelId, contentHash)
```

`payloadId` is derived from a domain-separated hash of the source-scoped logical identity and content hash. It is not derived from revision number, ordinal, URL, query token, credential, or secret.

A changed locator, token, user-agent, referrer, or any persisted provider field creates a new immutable payload version because those values participate in `contentHash`.

### 4.2 `CatalogSearchPayloadEntity`

Immutable provider-search vocabulary payload. It exists separately so locator-only/token churn does not require new FTS vocabulary rows.

Conceptual fields:

```text
searchPayloadId          PK, deterministic opaque SHA-256 id
searchContentHash        SHA-256 over provider-search-visible fields
searchHashVersion        integer policy version
canonicalChannelId       FK canonical_channels.id
rawName                  non-blank
groupTitle               nullable
channelNumber            nullable
```

Search content covers exactly the provider fields currently producing provider search documents: raw name, group title, and channel number, plus canonical identity needed to scope the document. It excludes locator, credentials, request tokens, and playback-only headers.

The generic canonical/overlay/EPG search document model remains authoritative for those document kinds. The production candidate may either publish provider FTS rows from this immutable payload table or add an immutable provider-search reference to the existing search document table. The evidence implementation must choose the lower-complexity Room shape and measure it; it must not duplicate canonical/overlay/EPG search policy.

### 4.3 `SourceRevisionMembershipEntity`

Immutable ordered membership of a source revision.

Conceptual fields:

```text
sourceId                 FK sources.id
revisionNumber           FK source_revisions(sourceId, revisionNumber)
ordinal                  positive import order
logicalChannelId         stable source-scoped logical identity
payloadId                FK CatalogPayloadEntity

PRIMARY KEY(sourceId, revisionNumber, ordinal)
```

Required indexes:

```text
(sourceId, revisionNumber)
(sourceId, revisionNumber, logicalChannelId)
(payloadId)
```

Every accepted occurrence has one membership row. Membership is intentionally rewritten for every revision because order and presence are revision facts. This preserves reorder and duplicate multiplicity without mutating reusable payloads.

## 5. Stable logical identity

### 5.1 Logical identity source

The current safe provider-key policy remains the input authority:

1. provider stable id when present;
2. normalized `tvgId`;
3. normalized name + group + channel number fallback.

A production `logicalChannelId` is source-scoped and domain-separated:

```text
SHA-256("catalog-logical-v1" || sourceId || providerKey)
```

The secret-bearing locator/query/token is never an identity input.

This deliberately differs from the current revision-row `providerChannelId`. Existing `providerChannelId` byte compatibility is not redefined as logical identity; it remains a legacy physical-row contract until migration is adopted.

### 5.2 Duplicates and collisions

`logicalChannelId` is not required to be unique within a revision. Multiple memberships may share one logical identity.

That property handles real playlist duplicates without inventing a URL-derived discriminator:

- exact duplicate occurrences can reference the same immutable payload while retaining multiplicity through separate membership ordinals;
- entries with the same provider key but different content can share one logical identity and reference different immutable payload versions in the same revision;
- reorder changes only membership order;
- a hash collision is treated as an integrity failure if the persisted payload does not equal the requested canonical payload for an existing `(logicalChannelId, contentHash)` row.

No conflict policy silently overwrites an immutable payload.

## 6. Content hashing

`contentHash` is a versioned SHA-256 digest over an unambiguous canonical serialization of every persisted payload field whose change must become visible after publication.

The serialization is length-delimited/tagged, not string concatenation with an ambiguous separator. Null and empty are distinct.

Included fields include:

- provider key;
- canonical channel id;
- raw/tvg names and tvg id;
- logo/group/number;
- catch-up metadata;
- locator;
- user-agent;
- referrer.

Consequences:

- `delta-0`: same logical identity + same hash → reuse payload;
- metadata change: same logical identity + new hash → new payload;
- token/locator churn: same logical identity + new hash → new payload;
- reorder: same identities/hashes → only new memberships;
- removal: omitted membership, payload retained only if an active/retained/staging revision still references it.

Only digests/counts may appear in evidence. Raw locator/query/token values remain excluded from reports/logs/traces.

## 7. Staging algorithm

The importer remains bounded and streaming. It does not load the previous revision or whole incoming catalog into memory.

For each staging batch:

1. derive existing canonical/provider key using current importer normalization;
2. derive `logicalChannelId`;
3. derive versioned `contentHash` and `searchContentHash`;
4. `INSERT OR IGNORE` immutable search payload by content address;
5. verify an ignored existing row is byte-equivalent to the requested immutable search payload;
6. `INSERT OR IGNORE` immutable catalog payload by content address;
7. verify an ignored existing row is byte-equivalent to the requested immutable payload;
8. `INSERT` membership for `(sourceId, revisionNumber, ordinal)`;
9. do not mutate `sources.activeRevision` or any row that changes visible active truth.

The stage transaction remains batch-bounded. The expected delta-0 write floor is therefore approximately one membership per incoming entry plus revision metadata, not a full payload rewrite.

## 8. Atomic publication and ownership

`activateIfRefreshOwnerMatches` remains the publication authority.

Before any active pointer switch, the same transaction must verify:

```text
source.credentialRef == expectedCredentialRef
AND source_refresh_states contains exactly one RUNNING owner for expectedRunToken
AND candidate revision is still STAGING
```

If ownership is stale:

1. candidate staging membership is discarded transactionally;
2. candidate revision cannot become active;
3. result is `Superseded`;
4. previous active/retained truth is unchanged.

Successful publication keeps the current sequence conceptually:

1. count/validate candidate memberships;
2. record previous active revision;
3. mark current active revision `RETAINED`;
4. mark candidate `ACTIVE`;
5. atomically set `sources.activeRevision`;
6. publish canonical display/search metadata derived only from active membership/payload truth;
7. remove revision memberships/revision metadata older than active + previous-good;
8. return `Activated`.

No caller obtains a weaker activation method as part of C03. Existing unguarded activation remains only where current local/import ownership already permits it.

## 9. Previous-good retention

The retention invariant remains exactly two published catalog revisions per source:

```text
ACTIVE current
RETAINED previous-good
```

plus any currently valid STAGING revision.

Payload rows are independent of status and may be referenced by any of those revisions. Removing an older revision removes only its memberships first. A payload/search payload is orphaned only when no membership references it.

This means delta-0 active and retained revisions can share the same physical payload versions while still being two complete immutable logical revisions.

## 10. Failure, cancellation, and stale staging

### Failed/partial refresh

A failure before activation deletes the candidate revision memberships and STAGING revision metadata. It never changes `sources.activeRevision`.

### Cancellation

The existing importer contract remains authoritative:

- cancellation is rethrown;
- a best-effort discard runs in `NonCancellable`;
- cancelled staging can never later activate because publication requires the revision to still be STAGING and the current refresh owner to match.

### Stale generation / owner

Guarded activation returns `Superseded` and transactionally discards the stale candidate membership. It never publishes.

## 11. Cleanup and orphan compaction

Publication correctness must not depend on aggressive physical compaction.

Two cleanup layers are defined:

1. **Synchronous revision cleanup:** delete memberships and revision metadata older than ACTIVE + RETAINED. This bounds authoritative revision membership growth.
2. **Bounded orphan compaction:** delete catalog payload/search payload rows with no membership references, in bounded batches after successful publication/discard or via the existing cleanup lane.

Orphan compaction may be retryable because orphan payload is unreachable and therefore not active truth. It must never delete a payload referenced by ACTIVE, RETAINED, or STAGING membership.

Evidence must measure:

- orphan count before/after cleanup;
- rows removed per bounded batch;
- cleanup wall time;
- database/WAL effects;
- storage after repeated revisions.

## 12. Active read path

The production-equivalent candidate read shape is:

```text
sources
→ active revision membership
→ immutable catalog payload
→ canonical channel / overlays
```

Example predicate shape:

```sql
JOIN source_revision_memberships AS membership
  ON membership.sourceId = sources.id
 AND membership.revisionNumber = sources.activeRevision
JOIN catalog_payloads AS payload
  ON payload.payloadId = membership.payloadId
```

Browse and playback projections must preserve their existing result semantics, aggregation, favorite/hidden handling, and deterministic ordering.

The new membership join is an explicit cost of write-elision. It is not accepted on reasoning alone; active browse/playback query latency and query plans must be measured against A.

## 13. Active search path

Provider FTS vocabulary can contain payloads referenced by retained/staging revisions, but publication visibility remains membership-gated.

A provider search hit is eligible only when an ACTIVE membership reaches a catalog payload that references the hit search payload and the canonical channel agrees.

Canonical, overlay, and EPG search semantics are unchanged.

`observeChanges()` continues to observe publication surfaces, not staging payload tables, so staging writes cannot invalidate public Search truth.

Candidate evidence must separately measure:

- provider hit candidate resolution;
- summary materialization;
- existing broad/selective search scenarios;
- query plans/index usage.

## 14. Storage-growth model

For `N` entries and two retained published revisions:

- membership upper bound is approximately `2N` plus one bounded staging revision;
- unchanged payload storage approaches `N` rather than `2N`;
- changed content creates new immutable payload versions until older memberships are removed and compaction reclaims them;
- repeated delta-0 refreshes must not grow payload/search payload counts after compaction;
- repeated token churn may create `N` new catalog payloads per revision, but search payload rows should remain reusable when searchable metadata is unchanged;
- storage remains bounded by retained/staging reachability plus bounded orphan backlog.

The repeated-revision evidence lane must prove this instead of inferring it from one refresh.

## 15. Migration and rollback

No production schema migration is implemented in the first contract/design phase.

If Room evidence proves the candidate worthwhile, schema version `10 → 11` is required because version 10 cannot represent reusable payload independent of revision membership.

Migration requirements:

1. preserve `sources.activeRevision` exactly;
2. preserve ACTIVE and RETAINED revision status/statistics;
3. transform every existing provider/stream occurrence into a deterministic payload + membership;
4. preserve duplicates and order deterministically using the legacy revision-row ordering contract available to the migration;
5. migrate provider search vocabulary without exposing raw locator/token data;
6. preserve incomplete STAGING rows as non-active staging or discard them through an explicitly tested recovery policy; they must never become active by migration;
7. run foreign-key/integrity checks;
8. produce the exported Room schema.

Binary downgrade from schema 11 to a schema-10 application is not an accepted rollback mechanism. Production rollback is a forward-fix application that understands schema 11. Destructive downgrade is prohibited.

The canonical API26/API36 migration workflow is mandatory before adoption.

## 16. A/B production evidence

### Variant A

Current production Room revision materialization on schema 10.

### Variant B

Production-equivalent Room candidate using the entities, DAO transaction ownership, active browse/search query shapes, retention, and cleanup semantics described here.

The benchmark-only `c03_b_*` SQLite tables from #355 are not used as B.

### Required scenarios

- `delta-0`;
- `delta-1`;
- `delta-10`;
- `delta-100`;
- `reorder`;
- `remove-10`;
- `token-churn`;
- failed/partial refresh;
- stale generation / run owner;
- cancellation.

Add repeated-revision storage/cleanup cycles because #364 explicitly requires bounded growth.

### Required measurements

Correctness first:

- active digest and count;
- logical identity preservation;
- duplicate multiplicity/order;
- previous-good digest/count;
- stale owner rejection;
- cancellation/failure non-publication;
- redaction.

Then performance/physical evidence:

- membership writes;
- catalog payload writes/reuse;
- search payload/document writes/reuse;
- revision/source metadata writes;
- WAL peak/growth;
- DB/file growth after checkpoint;
- stage transaction distribution;
- publication transaction distribution;
- active browse/playback latency;
- search candidate/summary latency;
- cleanup duration/rows;
- Java heap/GC/peak memory where the existing device harness can measure reliably.

Regular evidence uses at least five measured database iterations. 50k remains deep/manual unless the governing benchmark policy changes.

## 17. Adoption gate

B may proceed to production only if all of the following are true:

1. Every correctness/safety invariant is GREEN.
2. Delta-0/1 show material catalog payload and WAL/write reduction in actual Room.
3. Token churn preserves logical identity while producing content changes.
4. Active browse/search has no unacceptable credible regression; a sustained >10% regression requires investigation or rejection of the query shape rather than being hidden by write wins.
5. Repeated revisions demonstrate bounded storage after cleanup.
6. Cleanup is bounded and never removes active/retained/staging reachable payload.
7. Migration 10→11 is GREEN on canonical API26 and API36.
8. Exact-head Hosted CI, Hosted Validation, database migration matrix, and focused C03 production evidence are GREEN.

If the candidate cannot meet these gates without disproportionate schema/read complexity, #364 disposition becomes `DEFER` or `REJECT`; the prior benchmark `ADAPT` decision is not an obligation to ship.

## 18. Scope exclusions

This work does not:

- copy OwnTV mutable in-place persistence;
- weaken refresh ownership or generation checks;
- use locator/query/token/credentials as logical identity;
- change Xtream behavior;
- change playback/player policy;
- change EPG matching policy;
- change UI behavior;
- claim benchmark-only activation timings as production results.
