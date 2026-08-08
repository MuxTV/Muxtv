---
status: accepted
last_reviewed: 2026-08-08
architecture_version: 6
implementation_source_commit: e9dd0336716e27e9b51f4eb10da82169112e71d1
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый Android TV контур покрывает secure source onboarding, bounded M3U/XMLTV ingest, immutable catalog/EPG revisions, Channels/Favorites/Search/Recent/Guide, process-owned Media3 Player, exact first-rendered-frame success boundary, deterministic old-edge/current Android TV validation и fail-closed evidence provenance.

Текущий принятый `main` — `e9dd0336716e27e9b51f4eb10da82169112e71d1` (PR #143 / issue #139). Это baseline после завершения exact source-head provenance, deterministic focused M3U evidence lane и tracked-worktree provenance для claim-eligible manual measurements.

Текущий critical path:

```text
#30A / PR #145 pure bounded same-channel recovery
→ #30B Media3 runtime recovery + typed observations
→ #30C durable redacted diagnostics only if required
→ #30D TV Doctor Lite
→ #111 D2-D4 interaction/accessibility completion
→ Lounge Light D5-D7
→ #31 alpha hardening + physical-device evidence
```

CI reliability/routing (#141/#144) и Room3 patch hardening (#146) идут параллельно и не должны захватывать playback ownership.

## Принятая база

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Accepted main: `e9dd0336716e27e9b51f4eb10da82169112e71d1`.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: **v10**.
- Room3 library: **3.0.0**; patch update to 3.0.1 tracked separately in #146 without schema bump.
- Media3: **1.10.1**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Self-hosted Windows X64 runner; API26 + API36 TV matrices выполняются последовательно.
- Alternative player engine, Rust/UniFFI, libmpv и bundled SQLite не являются current dependencies.

## Что принято после предыдущего truth baseline

### Provider readiness / measurement / design

- PR #133 / issue #112 — provider-readiness invariants: cancelled/superseded attempts сохраняют previous-good active truth.
- PR #134 / issue #27 — deterministic focused M3U corpus/series harness для `small-1k`, `medium-10k`, `large-50k`.
- PR #138 / issue #111 D1 — immediate dense focus: stable geometry/full visibility/0 ms repeated-focus geometry motion; dead animation machinery удалена.

### Evidence integrity

- PR #142 / issue #140 — accepted-main focused evidence workflow для 5×10k + 5×50k.
- PR #143 / issue #139 — tracked-worktree provenance для claim-eligible manual series:
  - staged tracked drift rejected;
  - unstaged tracked drift rejected;
  - untracked `.work/evidence/**` allowed;
  - non-Git root fails closed;
  - check runs after exact source-head assertion and before evidence creation;
  - exploratory `<5` series remain usable on experimental tracked trees.

## Последняя принятая measurement acceptance

Accepted-main workflow:

- run: `31254022042`;
- source: `main@e9dd0336716e27e9b51f4eb10da82169112e71d1`;
- result: **success**;
- `medium-10k`: 5 sequential repetitions;
- `large-50k`: 5 sequential repetitions;
- artifact: `focused-m3u-evidence-31254022042-1`;
- artifact id: `9021482310`;
- artifact SHA-256: `ae7973542757c1f94844a4ba92daf22ad2dbcd3978108c1f224ccf21e0a4a0d4`.

Observed host interpretation remains descriptive:

- CPU parse scaling does not currently justify a Rust/native parser rewrite;
- allocation growth is closer to corpus growth and makes low-RAM/end-to-end retained-memory evidence more interesting than parser replacement;
- no performance regression threshold is accepted from this one controlled runner/dataset.

## Активная реализация

### P0 — #30A pure bounded playback recovery / PR #145

Draft PR #145 current head: `4c0074bb5417da261561250a75328cf9739eb9ab`.

Pure policy currently implements:

- preferred same-channel candidate first;
- deterministic stable remainder;
- duplicate variant identity suppression;
- foreign canonical-channel rejection;
- explicit positive `maxAttempts` and candidate cap;
- explicit positive `maxRecoveryDurationMillis`;
- deadline-aware candidate lookup;
- pure `TRY_NEXT_CANDIDATE` / `STOP_RECOVERY` disposition;
- stale/superseded recovery generation inertness;
- successful fallback does not mutate stored preferred variant.

Boundary:

```text
PlaybackRecoveryPolicy
  CanonicalChannelId + StreamVariantId + explicit budget/generation only
        ↓
PlaybackCatalog.resolveVariant(...)
        ↓
locator / headers / access metadata
        ↓
Media3 runtime (#30B)
```

`PlayableVariant`, raw locator, user-agent, referrer, credentials, Media3/Android/Room/UI state не входят в pure policy.

TDD initial RED был наблюдён на source head `37c1158ea74abd4db4f7716b184c804b6118ce2f`, Full run `31254880823`: отсутствовали `PlaybackRecoveryPlan`, `PlaybackRecoveryCandidate`, `PlaybackRecoveryBudget`. Дальнейшие contracts добавлялись rolling RED→GREEN slices.

Current-head verification (`4c0074b...`):

- Self-hosted Full run `31258501384` — **success**;
- artifact `self-hosted-validation-31258501384-1`, id `9021984903`;
- artifact SHA-256 `2acd95e877699383759b669daf2bd954775248465533a636b23b165e8210d42f1`;
- Product run `31258501378`: substantive API26/API36 product matrix step — **success**;
- workflow marked failure only because subsequent `Upload product matrix evidence` failed; this is #141 infrastructure/publication debt, not product regression.

### P0-next — #30B Media3 runtime recovery

After #30A acceptance:

- keep one `MediaSessionService` / one `ExoPlayer` owner;
- distinguish Media3 internal loader retry from MuxTV catalog candidate switching;
- count both inside one total user-visible recovery deadline;
- map contextual typed/sanitized runtime observation to `TRY_NEXT_CANDIDATE` / `STOP_RECOVERY`;
- do not use coarse `PlaybackError.retryable` alone as same-channel-switch policy;
- do not globally hardcode a transport status such as HTTP 401 to `STOP_RECOVERY`, because another candidate may have a different access boundary;
- Activity/ViewModel/WorkManager must not become competing retry owners.

### #30C / #30D

- #30C durable diagnostics is conditional: add persistence only if runtime evidence shows durability is required and a Room schema owner is free.
- #30D TV Doctor consumes typed sanitized observations and must not expose raw locators/headers/credentials.

## Parallel work

### TV design/accessibility (#111)

D1 accepted. Remaining:

- D2: native OK/Enter semantics, long-press only where real consumer needs it, repeat/held-key ownership, no global preview-key click synthesis;
- D3: independent focused/selected/playing/disabled semantics + reduced-motion;
- D4: 720p/1080p dialog scrolling, first/last action reachability, modal focus containment and native Back behavior.

D5-D7 Lounge Light waits for stable interaction/recovery contracts.

### CI artifact reliability (#141)

Repeated evidence publication failures remain infrastructure risk. Product run `31258501378` is the latest example: API26/API36 product matrix passed, then artifact upload failed. Artifact publication remains mandatory; target is bounded retry without recomputing a successful long matrix.

### CI routing (#144)

Current broad path filters unnecessarily wake device matrices for JVM-only/infra-only changes. PR #145 is concrete evidence: pure `player/api` policy work triggered Product API26/API36. Routing must be narrowed without weakening required runtime coverage and should include before/after runner wall-time evidence.

### Room3 dependency patch (#146)

Update `3.0.0 → 3.0.1` in an isolated dependency-only PR:

- no Room schema version change;
- no entity/DAO/migration redesign;
- verify no mixed Room3 artifacts;
- database unit/migration + API26/API36 database matrix;
- prefer before next real Room-owned #30C or #100 if practical.

### Other open hardening

- #118 — no refresh before user unlock + idempotent WorkManager init after unlock;
- #113 — portable non-secret backup envelope + integrity digest;
- #101 — Product/Database connected-suite selector with before/after runtime evidence;
- #100 — conditional source `ETag`/`Last-Modified` and correct `304 Not Modified` with coordinated Room schema ownership;
- #39/#40 — user recovery docs and release/app-store checklist.

## Порядок следующих работ

1. Finish #145 final review/required gates and merge #30A.
2. Synchronize repository truth (#147).
3. Implement #30B runtime recovery against accepted pure policy.
4. Land #144 earlier only if current device-routing overhead materially blocks RED/GREEN throughput.
5. Land #146 before the next Room-owned change when practical.
6. Add #30C only if durability is demonstrated; then #30D TV Doctor.
7. Complete #111 D2-D4, then Lounge Light D5-D7.
8. Extend #27/#31 performance evidence to constrained Android TV end-to-end ingest → staging → Room transaction → activation → retained heap/GC.
9. Before alpha: R8, Baseline/Startup Profiles, endurance, signed artifacts/SBOM, physical Android/Google TV/Fire TV codec/HDR/audio/network evidence.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй playback engine остаются deferred. Kotlin/Room/Media3 — preferred path, пока reproducible #27/#31/physical-device evidence не докажет конкретный residual hotspot или compatibility gap, достаточный для отдельного ADR.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, TV focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal throttling или реальное сетевое поведение. Physical Android/Google TV и Fire TV evidence остаётся обязательным до alpha compatibility claims.
