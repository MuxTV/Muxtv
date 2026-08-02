# Versioned EPG Matching Provenance Implementation Plan

**Issue:** #82  
**Stack base:** PR #80 exact head `6f05b0db27e8b8d564caffab43d372c197c157fd`

## Goal

Make derived EPG matches explicitly versioned and cheaply self-healing so a future normalization/matching-policy change cannot leave structurally current but semantically stale rows in Guide/NowNext.

## Invariants

- Producer provenance remains `(epgSourceId, epgRevisionNumber, providerSourceId, catalogRevisionNumber)`.
- Matching policy provenance is separate from `reasonCode`.
- Current policy is a stable integer constant; incrementing it is an explicit semantic event.
- Room v7 rows are never silently relabeled current during migration.
- Guide/NowNext consume only current-policy rows.
- HTTP 304 may perform a cheap freshness check, but must not trigger a full rematch when derived state is current.
- Missing/legacy derived state after 304 is rebuilt from previous-good producer revisions.
- Successful application startup performs one stale-aware repair pass so a schema/policy upgrade does not wait for a later network refresh.
- Startup repair is best-effort for ordinary derived-state failures and must not prevent cleanup/scheduler reconciliation.
- Cancellation remains authoritative; ordinary derived-state failures remain best-effort after durable refresh publication.
- Snapshot → compute outside transaction → replace-if-current publication remains unchanged.
- No fuzzy/manual/ML matching, new scheduler or second state framework.

## Implemented

- [x] Add `CURRENT_EPG_MATCH_POLICY_VERSION = 1` and explicit legacy/unversioned `0`.
- [x] Persist `matchPolicyVersion` on `epg_channel_matches`.
- [x] Default newly constructed match entities to the current policy while keeping the database column migration default at `0`.
- [x] Add cheap freshness coverage check for current producer tuple + current policy.
- [x] Add `EpgMatchingStore.reconcileIfStale` with explicit `Current` result.
- [x] Add one startup `reconcileAllIfStale` pass over active linked EPG relations immediately after database initialization, with per-source best effort and authoritative cancellation.
- [x] Keep provider-source fanout authoritative after a newly published catalog revision; freshness skipping is used where it has value instead of changing existing fanout semantics.
- [x] Filter Guide match counts, programme candidates and invalidation-version projection to the current policy.
- [x] Change successful EPG `NotModified` publication to call the cheap stale-aware reconciliation path, allowing migration repair without rematching every 304.
- [x] Add Room `MIGRATION_7_8` with `matchPolicyVersion INTEGER NOT NULL DEFAULT 0`.
- [x] Advance `MuxTvDatabase` to v8 and install `MIGRATION_7_8` in the production factory.
- [x] Add policy freshness/rebuild tests.
- [x] Add a reader contract proving legacy-policy rows remain outside Guide projection until rebuilt.
- [x] Add current-schema contract for the provenance column/default.
- [x] Add direct v7→v8 migration contract proving pre-versioned rows become policy `0` rather than policy `1`.
- [x] Update refresh publication contract so 304 performs the stale-aware callback while stale/superseded completion still does not reconcile.

## Remaining before merge

- [ ] Compile all affected modules against the exact stacked head.
- [ ] Obtain Room-generated `core/database/schemas/app.muxtv.database.MuxTvDatabase/8.json`; do not hand-edit an identity hash/schema file.
- [ ] Add/enable full `MigrationTestHelper.runMigrationsAndValidate(version = 8, migrations = listOf(MIGRATION_7_8))` using the generated schema.
- [ ] Run database migration coverage on old-edge API26 and current API36 exact head.
- [ ] Run focused matching/guide/sync tests and full validation.
- [ ] Re-review the SQL freshness query and Guide filters from the generated schema/diff.
- [ ] After PR #80 merges, rebuild/retarget this branch onto new `main` so the PR contains only v8 policy provenance.
- [ ] Merge only after exact-head evidence is green; then close #82 through the PR.

## Follow-up allocation work after #82

Once the policy version is persisted, allocation-only changes can safely optimize matching without changing semantics:

1. replace per-evidence-key `MutableSet` allocation with single-candidate storage and allocate a collision set only on the second distinct canonical ID;
2. accumulate matched/ambiguous/unresolved counters while building entities instead of three additional full-list passes;
3. simplify bounded Now/Next temporary Sequence/grouping work where measurement shows value.

Any future normalization/ladder change must bump `CURRENT_EPG_MATCH_POLICY_VERSION` and add corresponding compatibility/rebuild evidence.
