# Bounded XMLTV streaming parser implementation plan

## Status

- **Issue:** #28
- **Package:** parser foundation only
- **Production module:** `catalog:ingest`
- **Storage/UI changes:** excluded
- **Compression/fetch changes:** excluded

## Goal

Add a secure, cancellation-aware, bounded streaming XMLTV parser that emits normalized channel/programme records through a caller-owned suspend sink without constructing a document-wide DOM or retaining the full guide.

## Non-goals

This package does not add:

- Room schema v5 or EPG revision tables;
- EPG source credentials/fetch/decompression;
- matching against catalog channels;
- WorkManager refresh;
- Guide/now-next/Search UI;
- gzip/zip handling;
- fuzzy matching;
- programme deduplication across revisions;
- external DTD/schema validation;
- an alternate XML library.

Those remain separate reviewable packages after the parser contract is stable.

## Security boundary

XMLTV is untrusted input. The parser must:

1. enable `XMLConstants.FEATURE_SECURE_PROCESSING`;
2. reject DOCTYPE declarations;
3. disable external general entities;
4. disable external parameter entities;
5. disable external DTD loading;
6. deny `XMLConstants.ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` when supported;
7. install an entity resolver that never performs external resolution;
8. avoid schema/XInclude processing;
9. enforce repository-owned byte/depth/element/text/record/collection limits independent of parser defaults;
10. never include raw XML, title, description, external channel ID, icon URL or source path in diagnostics.

SAX feature/property support differs between JDK and Android implementations. Required security properties are configured fail-closed where portable; implementation-specific defense-in-depth features may be attempted but must not weaken the mandatory resolver/DOCTYPE/limits boundary.

## Public parser contract

### `XmltvParseLimits`

Bounded immutable values:

- `maxInputBytes`;
- `maxDepth`;
- `maxElements`;
- `maxTextCharactersPerElement`;
- `maxChannels`;
- `maxProgrammes`;
- `maxDisplayNamesPerChannel`;
- `maxIconsPerRecord`;
- `maxCategoriesPerProgramme`;
- `maxCreditsPerProgramme`;
- `maxStringCharacters`.

Defaults target realistic TV guides but remain below unbounded heap-risk levels. Tests use smaller limits.

### `XmltvParseSink`

Suspend callbacks:

- `onChannel(XmltvChannel)`;
- `onProgramme(XmltvProgramme)`;
- `onWarning(XmltvWarning)`.

The parser flushes a record at its closing element and does not retain previously emitted records. The caller owns batching/staging.

### Channel subset

- required `channel@id`;
- multiple `display-name` values with optional `lang`;
- bounded icons (`src`, optional width/height);
- optional bounded `url` values;
- duplicate/conflict policy is not performed by the parser.

### Programme subset

- required `programme@channel` and `programme@start`;
- optional `stop`, `pdc-start`, `vps-start`;
- title, sub-title and description with language;
- bounded categories, keywords, countries and URLs;
- bounded icons;
- episode numbers with system;
- simple credits with role/name;
- boolean presence flags for `previously-shown`, `premiere`, `last-chance` and `new`;
- unsupported elements are skipped structurally while still contributing to depth/element limits.

## Time normalization

`XmltvTimestampParser` supports the common XMLTV form:

```text
YYYYMMDDhhmmss ±HHMM
```

and deterministic shortened precision of 4, 6, 8, 10, 12 or 14 digits. Missing components are inferred to the earliest representable value and an explicit precision/provenance flag is retained.

Rules:

- explicit numeric offset is converted to `Instant`;
- absent offset produces a typed unresolved timestamp rather than silently assuming UTC;
- invalid date/time/offset yields a typed invalid-timestamp warning and rejects that programme;
- `stop < start` rejects the programme with a typed warning;
- missing stop remains absent; inference from the next programme belongs to the revision/staging layer.

## Failure model

Fatal parse failures:

- malformed XML;
- forbidden DOCTYPE/external entity;
- input byte limit;
- nesting limit;
- element count limit;
- text limit;
- channel/programme count limit;
- unsupported secure-parser configuration;
- sink failure (propagated without wrapping);
- cancellation (propagated unchanged).

Record-level warnings:

- channel missing ID;
- programme missing channel/start;
- invalid timestamp;
- stop before start;
- collection item dropped by per-record limit;
- empty required title/channel display name where applicable.

Fatal exceptions expose only reason codes and safe numeric location/count metadata.

## Input byte bound

Wrap the caller-owned `InputStream` in a counting stream that throws before bytes consumed exceed `maxInputBytes`. The parser must not close the caller-owned stream.

## Cancellation

SAX callbacks are synchronous. The handler checks the current coroutine job at bounded event intervals and before every sink callback. Suspend sink calls are bridged without changing dispatcher ownership. Cancellation must propagate as `CancellationException`, not a malformed-input result.

## TDD sequence

### RED 1 — public contract and streaming behavior

- parse two channels and programmes into a recording sink;
- multiple localized names/categories remain ordered;
- records are emitted at closing elements;
- diagnostics are redacted.

### RED 2 — time normalization

- explicit offset → exact `Instant`;
- shortened precision carries inferred precision;
- no offset remains unresolved;
- malformed timestamp rejects only the programme;
- stop-before-start warning.

### RED 3 — security

- DOCTYPE is rejected;
- external entity resolver is never allowed to fetch a URL;
- raw entity/system ID does not appear in exception text;
- malformed XML is typed/redacted.

### RED 4 — bounds and cancellation

- byte, depth, element, text, channel and programme limits;
- per-record collection limits;
- caller stream remains open;
- sink exception propagates;
- coroutine cancellation propagates.

### GREEN

Implement the smallest SAX-based production parser satisfying the contracts. Do not add Room, network or UI abstractions.

## Verification

Focused:

```powershell
.\gradlew.bat :catalog:ingest:test --no-daemon --stacktrace --console=plain
```

Repository:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

No DeviceCurrent/DeviceMatrix is required for the opening pure parser package. A later Android-runtime compatibility check may be added before merge if JAXP behavior differs from the JDK test runtime.

## Follow-up packages

1. bounded gzip/single-payload zip decode and EPG fetch contracts;
2. Room schema v5 immutable EPG revisions/staging/activation;
3. source refresh and previous-good retention;
4. deterministic channel matching;
5. now/next projections;
6. Guide/Search/Favorites/Recent.
