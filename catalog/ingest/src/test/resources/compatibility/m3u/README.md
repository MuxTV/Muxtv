# M3U Compatibility Corpus

This directory is the small correctness/compatibility corpus owned by issue #186.
It is deliberately separate from the 1k/10k/50k performance corpus owned by #27.

## Contract

`M3uCompatibilityCorpusTest` loads `manifest-v1.tsv` and executes every declared fixture through the production `StreamingM3uParser` using normal parser limits. The harness verifies manifest/fixture one-to-one ownership, deterministic semantic outcomes, support disposition vocabulary, synthetic-only network hosts, and redaction probes.

No production parser behavior is changed by the A1 corpus.

## Rules

- All hosts, tokens, names and credentials are synthetic.
- Fixtures never perform network access; they are byte inputs to `StreamingM3uParser` only.
- No private provider, playlist, account, token, Cookie or Authorization value may be committed.
- `SUPPORTED` means the fixture semantics are intentionally expected through the declared parser boundary; it does not imply end-to-end product support.
- `IGNORED_SAFE` means accepted input whose ignored fields must not affect identity/security/playback.
- `REJECTED` means intentionally rejected input with a stable typed failure/warning family.
- `NOT_IMPLEMENTED` documents known syntax/capability that MuxTV does not advertise as supported.
- A production parser change requires a genuine failing fixture/contract first.

## Manifest format

`manifest-v1.tsv` is UTF-8 tab-separated data. Its first line is the stable schema marker `# schema_version=1`, followed by one header row. This intentionally avoids adding a JSON/parser dependency merely for test metadata.

Columns:

1. `id`
2. `path`
3. `category`
4. `disposition`
5. `expected_entries`
6. `expected_skipped`
7. `expected_warnings`
8. `safe_expectation`
9. `redaction_probe`
