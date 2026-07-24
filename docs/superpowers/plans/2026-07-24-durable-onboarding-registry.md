# Durable Remote Source Preparation Registry Plan

## Goal

Make remote-source preparation recoverable across Activity recreation and process death without persisting raw locators, header values, or credentials outside `CredentialStore`.

## Architecture

- `catalog:refresh` remains the secure prepare/activate/cancel domain boundary.
- `core:database` owns a Room v4 table containing only opaque preparation ID, sanitized scheme/host, and timestamps.
- new `catalog:onboarding` module decorates `RemoteSourceOnboarding` with registry persistence and bounded expiry cleanup.
- `app:tv` starts expiry cleanup after database initialization and before normal source schedule reconciliation.

## Stored fields

```text
preparationId        canonical UUID / opaque CredentialId
scheme               http or https
host                 normalized host only
createdAtEpochMillis creation time
expiresAtEpochMillis cleanup eligibility time
```

Forbidden fields: full locator, query string, URL user-info, User-Agent/Referrer values, header values, source name, exception text.

## Lifecycle

### Prepare

1. delegate to secure domain preparation;
2. after encrypted credential storage succeeds, persist registry row;
3. if registry persistence fails, cancel the prepared token immediately;
4. return a typed persistence failure without secret text.

### Activate

1. delegate to domain activation;
2. remove registry row after successful activation;
3. remove row after a fully cleaned failure;
4. keep row when source or credential cleanup is incomplete so startup cleanup can retry.

### Cancel

1. delegate to domain cancellation;
2. remove registry row after `Removed` or `NotFound`;
3. retain row for cleanup failures or retained metadata.

### Startup expiry cleanup

1. load at most 50 expired rows ordered oldest-first;
2. parse each opaque token;
3. call the same domain `cancel` path;
4. delete registry row only after safe completion;
5. never loop without a bound during application startup.

## Database changes

- schema version 3 → 4;
- additive `pending_source_preparations` table;
- primary key `preparationId`;
- index on `expiresAtEpochMillis`;
- no foreign key to `sources`, because preparation may precede source creation.

## Verification

- migration 3→4 creates the expected table and index;
- stored rows contain no locator/query/header values;
- prepare rollback calls domain cancel when persistence fails;
- activation success removes registry row;
- fully cleaned failure removes row;
- incomplete cleanup retains row;
- expiry cleanup is bounded and idempotent;
- app Hilt graph and startup path compile;
- exact-head Full after PR #19 merge, DeviceMatrix with the complete onboarding UI.
