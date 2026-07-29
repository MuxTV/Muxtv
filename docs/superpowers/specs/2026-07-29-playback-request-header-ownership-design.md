# Playback request header ownership design note

**Status:** follow-up identified during PR #56 review; implementation belongs to a separate compatibility-focused package.

## Problem

`PlaybackRequest` and `PlaybackSessionRequest` validate the supplied `requestHeaders: Map<String, String>` in their constructors, but data-class properties retain the caller-provided map reference. A mutable map can therefore be changed after validation, changing request equality, bundle serialization, transport input and diagnostic header counts without passing through constructor checks.

## Required behavior

- a constructed request owns an immutable snapshot of header names and values;
- mutation of the caller's original map cannot change the request;
- `copy(...)`, equality, hash code, Bundle round-trip and `PlaybackRequest.toPlaybackSessionRequest()` remain deterministic;
- insertion order does not change semantic equality or profile identity;
- header-count/size/injection limits are still applied before the snapshot becomes observable;
- no header names or values enter diagnostics;
- no new public state owner or transport abstraction is introduced.

## Compatibility constraint

Both types are data classes. Replacing the primary-constructor property with a body property would change generated `copy`, `componentN`, equality and hash-code behavior. The implementation must therefore inspect all construction, destructuring and `copy` usages before selecting one of:

1. an immutable header value object used by both request types;
2. a factory plus staged deprecation of direct map construction;
3. conversion to explicit regular classes with source-compatible copy/component functions.

A superficial `requestHeaders.toMap()` call in `init` is insufficient because the generated data-class property has already captured the original reference.

## TDD acceptance

- mutate a `LinkedHashMap` after request construction; the request remains unchanged;
- mutate the source map after `toPlaybackSessionRequest()`; both source and session snapshots remain unchanged;
- Bundle encoding and decoding use the snapshot;
- copy with replacement headers creates an independent snapshot;
- equality/hash code remain stable after caller-map mutation;
- CR/LF injection remains rejected;
- existing legacy construction and redacted `toString()` contracts remain valid.
