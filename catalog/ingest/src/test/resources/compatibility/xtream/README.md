# Sanitized Xtream compatibility corpus

This corpus is correctness evidence for the bounded Xtream protocol normalization owned by `catalog:ingest`. It contains synthetic `.invalid` hosts and deliberately fake credential probes only; real provider payloads, playlists, tokens and accounts must never be committed here.

`manifest-v1.tsv` is the executable inventory. Dispositions mean:

- `SUPPORTED` — the normalized behavior is part of the current contract;
- `IGNORED_SAFE` — the input may be present but is intentionally excluded from normalized semantics;
- `REJECTED` — the structural input fails closed through a typed secret-safe failure;
- `NOT_IMPLEMENTED` — syntax/metadata is characterized but the downstream product semantic is explicitly not claimed.

The archive fixture now marks normalized `tv_archive` / `tv_archive_duration` semantics as `SUPPORTED`: those fields feed the bounded generic catch-up metadata used by the Xtream archive resolver. This corpus does not claim alternative Xtream timeshift dialect compatibility; credential-bearing transport materialization remains owned and tested in `catalog:refresh`.

Large-array incremental behavior and hard item/field bounds are generated in the JVM test instead of storing a large fixture in Git.
