# Portable Backup Envelope Design

## Status

Design for issue #113, Package A. This package defines the portable non-secret backup format, untrusted-input validation, integrity fingerprinting and pre-apply preview. It intentionally does **not** implement Android TV storage transport, SAF/Drive integration, Room mutation or portable secret encryption.

## Goal

Create a versioned, bounded and diagnostic-safe backup envelope that can carry portable MuxTV product state without exporting credentials or access locators, and make every restored source explicitly require access re-entry before it can become healthy.

## Context and accepted data boundaries

The accepted Room v10 schema stores profile identity/name/primary/archive state in `ProfileEntity`; source identity/name plus `credentialRef` and active revision in `SourceEntity`; per-profile channel overlays in `UserChannelOverlayEntity`; and recent playback identity/timestamps in `RecentChannelEntity`.

Package A deliberately copies only data classified portable:

- profile id, name, primary flag and archive timestamp;
- source id and display name as a recovery stub;
- channel overlay profile/channel identity plus favorite/custom-name/channel-number/hidden state;
- recent profile/channel identity plus last-successful-playback timestamp.

It deliberately excludes:

- `credentialRef`;
- stream/source URL or locator;
- Authorization/Cookie/arbitrary request headers;
- active source revision and refresh state;
- Keystore ciphertext or key material;
- EPG payloads, catalog revisions and derived search data.

A source decoded from a portable backup is always represented as `REAUTH_REQUIRED`. Re-entering access information is a separate recovery action and is the selected v1 cross-device secret model. No source is silently restored as healthy.

## Architecture

Create a standalone pure Kotlin module `:core:backup`. The module depends only on `kotlinx-serialization-json` for bounded JSON parsing/tree handling and JDK SHA-256. It does not depend on Room, Android, credentials, network, WorkManager, catalog refresh or UI.

The public domain is split into three responsibilities:

1. `PortableBackupModels.kt` — validated portable state and typed rejection/result models.
2. `PortableBackupCodec.kt` — canonical v1 encoder plus fail-closed decoder for untrusted bytes.
3. `BackupRestorePreview.kt` — pure comparison against an existing local-id summary; it reports counts, required re-authentication and id conflicts without mutating state.

## Wire format v1

The canonical document is a compact UTF-8 JSON object:

```json
{
  "formatVersion": 1,
  "createdAtEpochMillis": 1786000000000,
  "dataSchemaVersion": 10,
  "payload": {
    "profiles": [],
    "sources": [],
    "channelOverlays": [],
    "recentChannels": []
  },
  "integrity": {
    "algorithm": "SHA-256",
    "documentSha256": "64-lowercase-hex"
  }
}
```

`documentSha256` is SHA-256 over a canonical UTF-8 encoding of the same document **without** the `integrity` object. Canonical output uses fixed field order, compact JSON, stable list order and JSON string escaping.

After successful structural validation and digest verification, decode re-encodes the parsed snapshot and requires the original bytes to match the canonical v1 bytes exactly. This deliberately rejects duplicate JSON object keys, alternate field order, insignificant whitespace, alternate primitive spellings and escape variants that could otherwise be interpreted differently by different parsers. The app-generated backup is the canonical interchange representation; v1 is not a permissive hand-authored JSON format.

JSON primitive types are also exact: quoted numeric/boolean strings are not coerced into numbers/booleans.

This digest detects truncation/corruption and unexpected changes when the digest is not recomputed. It is **not** authentication against an attacker able to rewrite the document and recompute SHA-256. Package A therefore must not describe it as a signature/MAC. A future authenticated portable secret envelope requires its own threat model and is outside this package.

## Hard limits

The parser rejects input before JSON decode when the raw byte array exceeds 2 MiB.

After bounded decode it enforces:

- profiles: at most 16;
- sources: at most 128;
- channel overlays: at most 5,000;
- recent entries: at most 800 total and at most 50 per profile;
- ids: 1..128 characters, no leading/trailing whitespace;
- profile/source/custom display names: 1..160 characters where non-null;
- `dataSchemaVersion > 0`;
- timestamps non-negative;
- channel number null or non-negative;
- at most one primary non-archived profile;
- every overlay/recent profile id exists in the payload profile set;
- duplicate profile ids, source ids, overlay `(profileId, canonicalChannelId)` or recent `(profileId, canonicalChannelId)` identities are rejected.

The limits are input-safety ceilings for format v1, not claims about normal product scale.

## Fail-closed decode contract

Decode returns a typed `PortableBackupDecodeResult` and never mutates storage.

Rejection reasons:

- `OVERSIZED` — raw bytes exceed the pre-decode limit;
- `MALFORMED` — invalid/truncated JSON, wrong primitive type, missing required field or non-canonical/ambiguous v1 representation;
- `UNKNOWN_FIELD` — any unrecognized key at any v1 object level;
- `UNSUPPORTED_VERSION` — format version is not exactly 1;
- `INTEGRITY_MISMATCH` — digest algorithm/value is invalid or digest does not match canonical unsigned content;
- `LIMIT_EXCEEDED` — count/string/per-profile safety ceiling is exceeded;
- `INVALID_DATA` — semantic invariant or cross-reference is invalid;
- `DUPLICATE_IDENTITY` — a portable entity identity that must be unique occurs more than once.

Unknown fields fail closed in v1 rather than being silently ignored. A future format version must be explicitly supported before import.

## Preview and conflict policy

`BackupRestorePreviewer.preview(document, existingState)` produces only a summary:

- profile/source/overlay/recent counts;
- number of sources requiring re-authentication;
- profile-id conflicts;
- source-id conflicts;
- `requiresExplicitConflictDecision`.

Package A does not define overwrite/merge execution. If a portable id already exists locally, the preview reports a conflict and the future applier must require an explicit policy/choice. There is no silent overwrite path.

`toString()` implementations are payload-free: no profile/source names, ids, channel ids or custom labels are emitted.

## Testing strategy

Tests are authored before production types/code. While the self-hosted runner is unavailable, this preserves test-first history but does not constitute an executed RED/GREEN cycle.

Contract coverage:

- deterministic canonical encode and decode round-trip;
- encoded v1 never contains credential/access fields because the model cannot represent them;
- all decoded sources are `REAUTH_REQUIRED`;
- tampered canonical content with old digest is rejected;
- truncated/malformed JSON is rejected;
- >2 MiB input is rejected before parse;
- unknown fields and unsupported versions fail closed;
- quoted numeric/boolean primitives are rejected instead of coerced;
- duplicate JSON keys/non-canonical representations are rejected after canonical re-encode;
- duplicate/cross-reference/count-limit violations are rejected;
- recent entries are capped at 50 per profile;
- preview reports conflicts instead of overwriting;
- diagnostics do not expose portable payload text.

## Package A completion boundary

Package A is ready for acceptance only after fresh exact-head `:core:backup:test` plus repository compile evidence. Issue #113 remains open after Package A because these requirements still need later packages:

- at least one TV-operable storage/recovery path independent of a touch-only system file picker;
- atomic Room apply/rollback/retry behavior;
- onboarding/first-run restore UI;
- same-device end-to-end export/import journey;
- any future portable authenticated secret mechanism, if chosen, with an explicit threat model.
