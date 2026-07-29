# Playback Request Header Ownership Plan

> **Execution:** separate follow-up branch after PR #56. Do not implement inside the measurement package.

## Goal

Make `PlaybackRequest` and `PlaybackSessionRequest` own stable immutable header snapshots while preserving source-level constructor, `copy`, destructuring, equality, hash and Bundle behavior.

## Task 1 — usage and compatibility inventory

- locate all direct constructors, `copy`, destructuring and equality usages;
- record the generated data-class surface that existing code relies on;
- reject solutions that rename constructor parameters or silently remove `copy`/`componentN` behavior.

## Task 2 — RED ownership contracts

- construct from a mutable `LinkedHashMap`, mutate the source, require request headers unchanged;
- require equality/hash stability after source mutation;
- require `toPlaybackSessionRequest()` to create an independent stable snapshot;
- require Bundle round-trip to use the snapshot;
- require `copy(requestHeaders = mutableMap)` to snapshot replacement headers;
- preserve CR/LF injection rejection and redacted diagnostics.

## Task 3 — minimal compatibility implementation

Preferred direction after inventory:

- replace the data classes with explicit immutable classes only if manual source-compatible `copy` and `componentN` functions cover actual usage;
- snapshot with an insertion-order-independent immutable map representation;
- implement value equality/hash over the snapshot;
- keep all existing validation limits;
- avoid a new transport/state abstraction.

If direct data-class semantics are broadly consumed, introduce a bounded immutable header value type and staged factory migration instead of a repository-wide rewrite.

## Task 4 — verification

- focused API/Media3 unit tests;
- Full validation;
- DeviceCurrent only if Bundle/request Android behavior changes beyond pure JVM contracts;
- review diagnostics and diff;
- merge independently from measurement/variance work.
