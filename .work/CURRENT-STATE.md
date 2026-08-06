---
status: accepted-baseline-with-active-drafts
last_reviewed: 2026-08-06
architecture_version: 5
implementation_source_commit: ec2b7743183b227ef54c16989d061ae5d4775dee
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый `main` уже покрывает безопасное добавление источника, immutable catalog/EPG revisions, Channels + Now/Next/Favorites/Recent, bounded Search, service-owned Media3 Player и централизованный Room v10 migration/schema contract.

Этот файл разделяет **принятый baseline** и **активные draft-ветки**. Наличие реализации в draft PR не означает acceptance до exact-head evidence и merge.

## Принятый baseline

- Репозиторий: `MuxTV/Muxtv`, default branch `main`, BSD 3-Clause.
- Accepted `main`: `ec2b7743183b227ef54c16989d061ae5d4775dee`.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: **v10**.
- Один process-owned `MediaSessionService` / `ExoPlayer`.
- Cross-surface active/profile-visible truth issue #114 — closed/accepted before the current Guide window work.
- Database migration/schema ownership issue #121 — closed by PR #124.

### PR #124 — current Room owner

PR #124 merged as `ec2b7743183b227ef54c16989d061ae5d4775dee` and owns the accepted database-chain contract:

- `CURRENT_DATABASE_VERSION = 10` is the `@Database` version owner;
- `CURRENT_DATABASE_MIGRATIONS` is the validated ordered production chain;
- `MuxTvDatabaseFactory` consumes that shared chain;
- generated current Room schema is structurally checked and must remain clean in git;
- no destructive fallback was introduced;
- accepted Room v10 schema SHA-256: `809c0bfa812e5a86a5a84d97fe4f48f1d9ac71e515c5745ef222f24689e926c4`;
- accepted Room identity: `f6625d546ddfbad62e4e33340b17f490`.

PR #124 exact-head acceptance recorded Full + Database DeviceMatrix success on API26/API36 with zero connected-suite failures/errors/skips.

## Принятые product capabilities

### Source/catalog/security

- Keystore-backed credential isolation и exact-origin HTTP approval;
- bounded streaming M3U ingest;
- immutable source revisions, atomic activation и previous-good preservation;
- durable source refresh lease/run-token ownership;
- secure remote onboarding и durable pending registry;
- typed playback catalog resolution;
- active/current-revision + selected-profile-visible membership contract for user-facing surfaces.

### EPG

- bounded secure XMLTV parsing;
- streaming plain/gzip/ZIP decoder;
- separate compressed/decoded byte ceilings;
- `Content-Encoding` validation before payload sniffing;
- immutable EPG revisions и durable refresh ownership;
- previous-good preservation after malformed/oversized refresh;
- deterministic channel matching;
- bounded Now/Next и programme-boundary invalidation.

### Daily-use TV

- Channels destination-scoped state and dedicated rows;
- deterministic D-pad graph;
- canonical Player → Back focus restoration and nearest-previous fallback;
- profile-scoped Favorites;
- Room v9 bounded Unicode Search using FTS4 `unicode61`;
- active-truth Search revalidation and bounded Search TV;
- Search → Player → Back query/canonical-focus continuity;
- service-owned `onRenderedFirstFrame()` success boundary;
- setup-generation + current-media identity protection;
- profile-scoped bounded Recent in Room v10;
- first-frame-only Recent writes with newer-wins/idempotent delivery and cap 50/profile;
- active/current-revision + non-hidden Recent projection.

## Активные draft packages — не считать принятыми

### P0 — PR #128 bounded Guide data

PR #128 is the canonical Guide data-layer branch rebuilt from accepted `main@ec2b774...` after #124.

Implemented in the draft:

- separate bounded `GuideWindowRepository` while existing Now/Next owner remains separate;
- keyset channel window, no OFFSET;
- bounded programme window with explicit channel/time/row ceilings;
- typed `READY / NO_GUIDE / SOURCE_CONFLICT`;
- deterministic open-ended programme handling;
- payload-free invalidation;
- no Room v11/schema change and no Guide UI.

Exact draft head: `985fbda8bd90ebde0f29fc1adc0632a8a05704a2`.

PR body records Self-hosted Full success and unchanged Room v10 export. DeviceMatrix acceptance remains mandatory before merge. While the self-hosted runner is unavailable, do not mutate this head merely to perform unrelated cleanup.

### P0 — PR #127 explicit playback transport (#108)

Draft implementation already defines one Media3 choke point for HLS/raw MPEG-TS/DASH/progressive/ambiguous transport selection. It is based on an older accepted main and must be refreshed/rebuilt after the current Guide data merge before final acceptance.

Do not close #108 until the refreshed exact head passes required validation and merges.

### P0/P1 — PR #129 bare-host source normalization (#116)

Draft is intentionally test-first. Production normalization must not be added until the test-only head demonstrates the expected RED when execution is available. Preserve existing HTTP approval/origin/redirect security semantics; never add HTTPS→HTTP fallback.

### P1 — issue #118 user-unlocked startup lifecycle

Current accepted `main` still has no explicit app-owned user-unlocked gate for credential-encrypted startup work. The dedicated branch `work/user-unlocked-startup-gate-118` is prepared independently from accepted `main` while the runner is unavailable.

Required contract:

- zero Room/Keystore/DataStore-dependent startup before `UserManager.isUserUnlocked`;
- CE dependencies are lazy until unlock eligibility;
- one dynamic `ACTION_USER_UNLOCKED` subscription when needed;
- post-registration state recheck closes the missed-broadcast race;
- startup and listener cleanup are idempotent;
- no manifest boot receiver, no `directBootAware`, no device-protected migration and no WorkManager replacement.

This remains **draft/unaccepted** until exact-head tests and device lifecycle validation run.

## Текущий критический путь

```text
#128 bounded Guide database windows
→ Guide TV route/state/grid (#29 remainder)
→ refreshed #127 explicit transport (#108)
→ bounded fallback / TV Doctor (#30)
→ source lifecycle hardening (#116/#118/#100 as ownership permits)
→ Lounge UI packages (#33/#93)
→ alpha hardening (#31)
```

Self-hosted CI is an acceptance/evidence lane, not the product architecture. When the runner is offline, implementation should continue only on independent packages whose correctness can be reasoned/test-authored without invalidating already accumulated exact-head evidence.

## Параллельные hardening packages

### Issue #100 — conditional M3U refresh

Future Room schema owner after the current database/Guide ownership window is free. Add source validators and `304 Not Modified` without creating a competing migration chain.

### Issue #101 — CI Phase 2

Measured Product/Database suite split prototype exists, but CI restructuring is lower priority than the product closure train. Reapply the small diff on a fresh accepted main later; do not create a second emulator lifecycle.

### Issue #111 — TV interaction contracts

Owns shared long-press/scroll-reachability/focus-contrast contracts and the gradual Compose Test JUnit4 v1→v2 helper migration. Preserve D-pad/focus semantics; no blind search/replace.

### Issue #27 — evidence lane

Existing current-normal / old-edge-normal / current-low-ram profiles are not enough for structural optimization claims. Remaining work includes deterministic 10k/50k corpus manifests/digests and repeated distributions before buffer/native/Rust/FFmpeg decisions.

## Что можно делать при выключенном self-hosted runner

Safe offline work:

1. independent fresh-main implementation with small ownership surface, e.g. #118;
2. pure JVM test contracts and deterministic fixtures;
3. documentation/truth-sync/ADR/plan updates grounded in accepted main;
4. static DI/security/privacy/ownership review;
5. prepare draft PRs explicitly marked validation-pending;
6. deterministic perf corpus generation and manifest tooling that does not change runtime behavior;
7. review/cleanup of stale issue ownership without merging unverified runtime changes.

Do not do while evidence is unavailable:

- merge runtime/Room/playback changes merely because they look correct;
- mutate #128/#127 heads for unrelated cleanup and invalidate exact-head evidence;
- claim RED/GREEN that was not executed;
- consume a new Room schema version in a competing branch;
- perform broad dependency/Gradle/CI rewrites together with product code;
- adopt Rust/UniFFI/libmpv/bundled SQLite/FFmpeg without #27/#31 evidence.

## Порядок следующих работ

1. preserve #128 head and finish its exact API26/API36 acceptance when the runner returns;
2. in parallel, prepare #118 user-unlocked lifecycle gate on accepted main without merging;
3. merge #128 only after exact evidence and final review;
4. implement/accept Guide TV route from the bounded repository contract;
5. rebuild/refresh #127 on the resulting accepted main and close #108 only after acceptance;
6. obtain actual RED and finish #129, then close #116 after security regression acceptance;
7. implement #30 bounded playback fallback/TV Doctor;
8. finish #118 reboot/unlock/package-update evidence;
9. let #100 own the next Room schema change when the schema window is free;
10. finish #27 deterministic large corpus/repeated evidence;
11. #33/#93 Lounge packages over real Search/Recent/Guide;
12. #31 release hardening and physical-device matrix.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv and a second playback engine are not current dependencies. Kotlin/Room/Media3 remain the preferred path until repeated #27/#31 evidence demonstrates a concrete residual hotspot or compatibility gap sufficient for a separate ADR.

## Evidence limits

Emulator API26/current gates validate Android API, Room/migration, lifecycle plumbing, TV focus, MediaSession and database contracts. They do not prove vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal behavior or real network variability. Physical Android/Google TV and Fire TV evidence remains mandatory before alpha compatibility claims.
