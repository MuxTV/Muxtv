---
status: accepted
last_reviewed: 2026-07-19
owners: [data, security, profiles, release]
---

# Backup and restore specification

## 1. Цель

Backup переносит пользовательскую конфигурацию и персональный каталог между установками/версиями без предположения, что replaceable provider/EPG caches должны копироваться целиком. Restore is staged, previewable and atomic.

## 2. Формат

Versioned archive:

```text
manifest.json
installation.json
sources.json
canonical-channels.json
profiles.json
profile-overlays.json
epg-bindings.json
settings.json
mutation-state.json
checksums.json
secrets.enc              optional explicit section
```

Archive uses deterministic UTF-8 serialization and bounded compression. Every section has schema/version and checksum. Unknown optional sections may be skipped; unknown required major schema rejects restore.

## 3. Manifest

```json
{
  "format": "muxtv-backup",
  "schemaVersion": 1,
  "createdAt": "...",
  "appVersion": "...",
  "databaseSchemaVersion": 1,
  "installationId": "opaque",
  "sections": [],
  "containsSecrets": false,
  "encryption": null
}
```

Manifest contains no source password/token.

## 4. Included by default

- source names/kinds/endpoints with sensitive query/userinfo redacted or represented by credential placeholders;
- source refresh/network/display settings;
- canonical channels, aliases/tombstones only where needed to preserve user identity;
- all user-created profiles and primary profile data;
- favorites/history policy according to user-selected privacy option;
- custom groups/order/numbers/hidden/display overlays;
- manual/confirmed EPG bindings and aliases;
- profile audio/subtitle/UI/accessibility/playback preferences;
- extension descriptors/grants without extension secrets/binaries;
- relevant mutation/negative-link decisions;
- app settings and schema metadata.

Replaceable default exclusions:

- full M3U/XMLTV downloaded payloads;
- full EPG programme cache;
- artwork/media cache;
- temporary files/logs/probes;
- downloaded APK;
- native crash dumps;
- credentials/secrets.

## 5. Secret export

Secrets are excluded by default. Optional explicit secret export requires:

- separate warning and password entry/confirmation;
- authenticated encryption (AEAD);
- memory-hard/adaptive password KDF with parameters stored in envelope;
- random salt/nonce;
- no password persisted;
- no plaintext temp file;
- encrypted section independent checksum/auth tag;
- import rate limits and generic authentication failure;
- redaction from logs/screenshots where appropriate;
- user can export configuration without secrets instead.

Exact algorithm/library selected by security ADR at implementation time using current Android/cryptography guidance. Do not invent custom crypto.

## 6. Stable references

Backup uses internal stable IDs plus semantic fallback keys/provenance. Restore may encounter different installation IDs/source IDs.

Mapping order:

- existing exact imported ID when restoring same installation and safe;
- source semantic identity confirmed by user;
- canonical alias/mutation map;
- provider/external IDs and normalized proposal;
- unresolved reference retained for review.

Missing channel/source reference does not discard overlay immediately; it becomes unresolved with retention and can reattach after source refresh.

## 7. Primary profile rules

Target installation always retains exactly one primary profile.

Restore modes:

### Replace user configuration

- target primary identity survives;
- imported primary data merges/replaces target primary according to preview;
- imported additional profiles remain additional;
- name conflict does not imply identity conflict.

### Merge

- imported primary offered as merge into current primary or new additional profile;
- cannot create second primary;
- additional profile collisions resolved by stable ID/history and user preview;
- no demographic/profile type inference.

### Import selected profile

- one imported profile becomes additional unless explicitly merged into target primary;
- installation-scoped sources required by overlays can be selected/imported separately.

## 8. Export consistency

Backup reads a consistent logical snapshot:

- short DB transaction/checkpoint captures revision pointers and relevant rows;
- long serialization/compression works from snapshot/temp representation, not a long write lock;
- source/EPG refresh may continue before/after safe boundary;
- active playback need not stop;
- operation cancellable with temp cleanup;
- checksum generation streaming.

## 9. Restore pipeline

```text
select archive
 → bounded archive validation
 → manifest/schema/checksum verification
 → optional decrypt secret section
 → parse to staging models
 → compatibility/migration transform
 → reference resolution
 → conflict/impact report
 → user selection/confirmation
 → local recovery checkpoint
 → one atomic logical commit or bounded staged transaction plan
 → post-commit source refresh/index rebuild
 → verification report
```

Never apply while parsing. Corrupt/unsupported archive leaves active data unchanged.

## 10. Conflict preview

Preview includes:

- profiles to add/merge/replace;
- source endpoints/credentials status;
- favorites/groups/order/history counts;
- channel/EPG unresolved references;
- extension grants requiring reconfirmation;
- policies/PIN behavior;
- settings that differ;
- estimated source refresh/network requirement;
- data that cannot be imported.

Primary action visible/reachable with D-pad. User can inspect/choose sections.

## 11. Restore policy

- credentials never overwrite existing credentials silently;
- extension binary is never imported from backup;
- extension grants may require reconfirmation on package/signature/device mismatch;
- source network/cleartext/LAN permissions require review when destination changes;
- imported pinned variant becomes unresolved if variant absent;
- manual split/reject decisions preserved when identities map;
- history may be omitted by privacy choice;
- unsupported future schema rejected with clear version requirement;
- forward migration transforms tested and deterministic;
- destructive replace has checkpoint/undo path.

## 12. Migration

Backup schema evolves independently from Room schema.

- reader supports current and documented prior major/minor versions;
- each transform has golden fixtures;
- unknown optional fields preserved/skipped per schema contract;
- removed concepts map explicitly;
- no «best effort» silent data loss;
- app downgrade cannot be assumed to read newer backup;
- release notes state minimum reader version.

## 13. Storage and SAF

- export/import through Storage Access Framework;
- app-private temp with free-space precheck/quota;
- no `MANAGE_EXTERNAL_STORAGE` requirement;
- user chooses destination/document provider;
- cleanup after success/cancel/process death;
- filenames sanitized and non-authoritative;
- content URI permissions handled for operation duration/persisted only if needed.

## 14. Security

- archive untrusted;
- path traversal/nested archive/decompression limits;
- JSON/text/object count/depth/size limits;
- no executable code/scripts/binaries loaded;
- IDs/strings validated;
- no external URL fetch during parse/preview;
- source refresh occurs only after commit/confirmation under normal network policy;
- password/secret buffers minimized/cleared where practical;
- diagnostic report redacts secrets and local paths.

## 15. Tests

- deterministic export/golden manifest;
- checksum corruption/partial archive/wrong format;
- zip slip/bomb/deep JSON/oversized section;
- no-secret default export canary scan;
- encrypted secret correct/wrong password/tamper;
- primary profile invariant across replace/merge/profile-only modes;
- additional profile name collisions;
- installation source/profile overlay mapping;
- unresolved channel reconnect after source refresh;
- migration from every supported backup schema;
- process death/cancel/disk full;
- extension grant reconfirmation;
- DB remains unchanged after invalid/cancelled restore;
- visible D-pad reachable confirm/cancel actions.

## 16. Acceptance criteria

- default backup contains no credentials/canary secrets;
- invalid archive cannot partially modify installation;
- restore never creates second primary profile;
- user can import profile without deleting shared sources;
- source/profile/overlay relationships survive mapping or remain explicitly unresolved;
- current data has checkpoint before destructive replace;
- no all-files permission needed;
- archive from every supported version has automated golden restore test;
- result report explains imported/skipped/unresolved items.