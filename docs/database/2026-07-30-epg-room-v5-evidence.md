# EPG Room v5 evidence

This record covers the immutable EPG revision storage package for issue #28.

## Reviewed implementation

- Room database version 5 with `epg_sources`, `epg_revisions`, `epg_channels` and `epg_programmes`.
- Explicit `MIGRATION_4_5` preserving existing profile, source and catalog rows.
- Staging rows remain invisible until one atomic activation transaction updates revision states and the source active pointer.
- Exactly current and previous-good EPG revisions are retained after later activations.
- Empty or unresolved-only guide revisions cannot replace the active guide.
- Failed or cancelled imports discard only their staging revision.
- Active programme queries enforce bounded channel count, time window and result count.
- Public import/source diagnostics redact source identifiers, access references and programme content.

## Exact-head validation

Head before durable schema publication: `0cb2d28adf53f8c33737a6dd9d4d705e4f63cdd4`.

- Full validation run `30585271353`: passed.
- Database migration device matrix run `30585271308`: passed sequentially on the repository old-edge and current Android TV profiles.
- Generated Room schema version: `5`.
- Generated schema identity hash: `cba988c0c6c42a281425a79fbf3903b8`.
- Generated schema contains 16 entities, including all four EPG tables.

The generated `5.json` is committed as repository truth before merge. Network acquisition, bounded compressed decoding, refresh orchestration, channel matching and Guide/Search UI remain separate follow-up packages.
