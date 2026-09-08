# C21 Slice A Implementation Plan

Owner: #352
PR: #357

## Goal

Finish only the provider request-metadata model and sanitized parser normalization. Keep importer, persistence, resolved playback propagation, and player/network adapters outside this PR.

## TDD sequence

1. Model contract
   - RED: missing `ProviderRequestMetadata`, target projection and bounded header policy.
   - GREEN: immutable model, canonicalization, bounds, secret-safe diagnostics.

2. Parser corpus RED
   - extend the existing sanitized compatibility manifest;
   - add synthetic fixtures for `#EXTHTTP`, Kodi header-list properties, URL pipe metadata, duplicate precedence, reset/no-leakage and invalid metadata;
   - extend existing VLC/Kodi fixtures to assert provider-neutral metadata;
   - prove the RED is caused by missing parser normalization surface/behavior.

3. Parser GREEN
   - add the minimal `core:model` dependency to `catalog:ingest`;
   - add provider-neutral metadata to `M3uEntry` while preserving existing user-agent/referrer fields during Slice A;
   - normalize EXTINF request attributes, `#EXTVLCOPT`, `#EXTHTTP`, supported Kodi request-header properties and recognized URL pipe suffixes;
   - use typed secret-free warning kinds for malformed/rejected metadata;
   - preserve pending-entry ownership and reset semantics.

4. Qualification
   - run the full Hosted Validation on the exact final head;
   - inspect the PR diff for accidental importer/database/player changes;
   - verify no change to `SensitiveHeaderPolicy`, exact-origin policy, redirect behavior or cross-origin stripping;
   - inspect diagnostics and corpus for redaction leaks;
   - update #357 and #352 with exact-head evidence.

5. Disposition
   - keep the overall C21 result pending in #352;
   - mark #357 ready only when Slice A is independently GREEN and focused.
