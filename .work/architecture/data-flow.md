---
status: accepted
last_reviewed: 2026-07-19
---

# Потоки данных каталога и EPG

## 1. Source import/refresh

```text
User/scheduled refresh request
  → source lease/unique work
  → bounded fetch to app-private temporary storage
  → secure decompression/decoding
  → streaming parser batches
  → immutable SourceRevision staging rows
  → syntax/security/resource validation
  → previous-revision reconciliation and diff
  → normalization and canonical membership proposals
  → EPG match proposals for unconfirmed bindings
  → guardrails/preview when suspicious
  → short atomic database commit
  → activeRevisionId switch
  → post-commit search/image/probe/cleanup jobs
```

Normative algorithm: `architecture/source-refresh.md`.

## 2. Guarantees

- active catalog/guide remains readable until successful commit;
- cancellation/process death/parser/DB failure cannot publish a partial revision;
- source URL, signed token and credentials are not stable channel identity;
- raw provider metadata and normalized values are both retained with provenance;
- provider refresh cannot delete profile overlays through cascade;
- source count/identity churn guardrails reject suspicious revisions;
- automatic merge/fix has evidence, confidence, algorithm version, preview and undo where impactful;
- manual EPG/profile decisions survive refresh;
- post-commit failure does not invalidate committed catalog and remains retryable.

## 3. Identity reconciliation

Within the same source, old/new provider entries use strongest compatible evidence:

1. stable provider-native identity;
2. prior variant fingerprint excluding volatile token/query values;
3. `tvg-id` plus source/context;
4. stable programme/stream identifier;
5. normalized metadata candidate with confidence and conflict rules.

Fuzzy/uncertain match becomes proposal. It does not silently transfer user data below accepted confidence.

Cross-source Smart Channel matching is a separate pipeline and never conflated with same-source revision reconciliation.

## 4. Canonical channel lifecycle

`CanonicalChannel` is installation-scoped and source-independent.

```text
ProviderChannel memberships
        ↓
CanonicalChannel
        ├─ StreamVariants
        ├─ global suggested/confirmed EPG binding
        └─ Profile-specific overlays and optional EPG override
```

Removing one source retires only its provider entries/variants. Canonical channel may remain as tombstone/unresolved object while profile favorite/history/overlay/manual binding exists. Physical removal follows retention and explicit rules in `architecture/domain-model.md`.

## 5. Profile read composition

UI query composes:

```text
active installation catalog
+ canonical metadata priority/provenance
+ current ProfileOverlay
+ current ProfilePolicy visibility
+ current EPG interval
+ current health/playback state
= ProfileChannelView
```

Profile switching changes overlays/policies/preferences but does not duplicate or refresh sources/base EPG.

## 6. EPG flow

```text
EpgSource fetch/decode/secure XML parse
  → EpgRevision staging
  → channel/programme normalization
  → timezone/conflict/dedup validation
  → atomic active revision commit
  → binding candidate evaluation
  → interval/query indexes
  → retention cleanup
```

- exact external timestamp offset has priority;
- missing timezone remains unresolved until source setting/confirmation;
- programmes stored as Instants with original/provenance metadata;
- EPG grid queries bounded channel/time intervals and lazily extends;
- retention uses policy plus byte/record caps and preserves referenced catch-up/recording items;
- manual bindings never overwritten automatically.

## 7. Playback flow

```text
Profile selects CanonicalChannel
 → use case loads allowed/ranked StreamVariants
 → PlaybackOrchestrator resolves volatile locator
 → Media3 adapter prepares/renders
 → events map to stable playback state/errors
 → observed session updates device-scoped health evidence
 → failure may trigger bounded retry/re-resolution/failover
```

Player never queries M3U/Room DAO directly. Playback session holds IDs/preferences snapshot and short-lived resolved request only.

## 8. TV Doctor flow

```text
Manual/passive audit request
 → ProbeScheduler resource checks
 → L0/L1/L2/L3/L4 evidence
 → health/findings with confidence/provenance
 → user-visible summary
 → selected mutation preview
 → atomic DoctorMutationSet
 → inverse journal for undo
```

Background audit does not open many decoders and is suspended/deferred when it could harm playback/device/provider.

## 9. Local control flow

```text
TV opens pairing screen
 → one-time token/QR
 → phone connects and TV confirms
 → short-lived capability session
 → bounded DTO/use-case request
 → normal source/catalog/profile pipelines
 → live progress/result
 → expiry/revoke
```

Phone server/API never bypasses application ports or receives existing plaintext credentials.

## 10. Backup flow

```text
Snapshot request
 → consistent logical read/checkpoint
 → versioned manifest and entity sections
 → stable ID/reference validation
 → checksums
 → optional explicit encrypted secret section
 → app-private temporary archive
 → user-selected export destination
```

Baseline backup includes:

- installation/source configuration without secrets by default;
- canonical channels, aliases/mutation state needed for restore;
- profiles and profile overlays/policies;
- EPG bindings, not necessarily full replaceable programme cache;
- user settings;
- schema/app/version/provenance/checksums.

Restore always parses/validates to staging, presents impact/conflicts and commits atomically. Primary profile identity and source/profile scope follow `.work/specifications/profiles.md`. Details require separate backup specification before Phase 02 implementation.

## 11. Secrets and diagnostics

Secrets move only through credential store references. They are excluded from domain events, normal DB exports, logs, crash reports, EPG/logo clients and cross-origin redirects. Explicit encrypted secret export, if implemented, requires authenticated encryption, password KDF parameters, independent threat review and user warning.