# Summer 2026 execution addendum

**Date:** 2026-08-08  
**Accepted baseline:** `main@5bb6ee1f754785b2b236d6dcb52fd4458780e758`  
**Parent plan:** `docs/superpowers/plans/2026-08-08-post-guide-evidence-and-playback-recovery.md`

This addendum records execution facts and official-documentation decisions that changed after the parent plan was authored. It does not replace the parent plan; it advances its Phase 0 state and tightens later implementation contracts.

## 1. Accepted state after #136/#137

PR #137 is accepted and merged as `5bb6ee1f754785b2b236d6dcb52fd4458780e758`.

Its source head passed all required evidence lanes before merge:

- Self-hosted Full validation — success;
- Database old-edge/current Android TV matrix — success;
- Android TV product old-edge/current matrix — success;
- Measurement variance smoke — success;
- unresolved review threads — 0.

The repository now has an executable exact-source provenance contract: evidence-producing PR workflows explicitly checkout the source head they claim and fail closed when `git rev-parse HEAD` differs from `SourceCommit`.

All active PRs created before this baseline must be restacked/reconciled before their runs are treated as exact-source-head acceptance.

## 2. Immediate execution sequence

### E1 — Finish #111 D1 / PR #138

TDD state already observed:

- RED source head: `89e428fb4dadfb75671be172e76cf6adadd72ca6`;
- RED Full run: `31216247036`;
- exact failures:
  - `dense focus preserves geometry and full content visibility`;
  - `repeated dpad focus has no geometric transition delay`.

The RED branch was restacked onto `main@5bb6ee1...` while preserving RED ancestry.

Minimal GREEN changes only the contract values:

- focus scale `1.06 -> 1.0`;
- unfocused alpha `0.84 -> 1.0`;
- dense focus geometry duration `140 ms -> 0 ms`;
- focused alpha remains `1.0`;
- outline remains `3dp`;
- screen transition budget remains `240 ms`.

Do not broad-redesign the theme in this package.

After GREEN:

1. remove dead focus-scale animation code as a behavior-preserving refactor;
2. rerun exact-source Full and Product matrix;
3. only then consider D1 complete.

### E2 — Restack and close repository truth PR #135

- include the accepted #137 workflow tree;
- update point-in-time truth from `286ece...` to `5bb6ee1...`;
- mark #136/#137 completed rather than active;
- record #138 D1 as active parallel design work;
- run fresh exact-source Full acceptance.

### E3 — Restack #133 / provider readiness

No broad redesign is needed. Preserve the implemented invariants:

- primary previous-good catalog remains usable;
- secondary enrichment is independent;
- `Cancelled` / `Superseded` are explicit;
- successful primary/secondary attempts must target accepted active revision truth.

Required acceptance after restack:

- Full host;
- Product API26;
- Product API36;
- exact `HEAD == SourceCommit`;
- issue #112 acceptance reconciliation.

### E4 — Restack #134 / measurement series

Preserve fail-closed evidence ownership and rerun exact-source host/variance checks.

Then produce sequential baseline evidence on accepted `main`:

- 5 x `medium-10k`;
- 5 x `large-50k`;
- one controlled runner class;
- fixed seed;
- identical corpus hash/bytes/expected counts across repetitions;
- analyzer provenance reviewed before thresholds are introduced.

### E5 — #30A pure recovery policy

Implement before Media3 runtime changes and without Room/UI changes.

Contracts:

- same canonical channel only;
- preferred variant first;
- deterministic remaining order;
- no duplicate candidate attempt within one recovery generation;
- explicit max candidate attempts;
- explicit total wall-clock recovery budget;
- terminal vs retryable failure families;
- cancellation/supersession invalidates the generation;
- temporary fallback success never persists a new preferred variant;
- first rendered frame remains the only playback-success boundary.

### E6 — #30B Media3 runtime with two-layer retry accounting

The process-owned `MediaSessionService` / single `ExoPlayer` remains the only runtime owner.

Media3 already has loader retry/fallback behavior through `LoadErrorHandlingPolicy`. MuxTV same-channel variants are a higher-level catalog concept and must not be implemented as Media3 resource fallback.

Required rule:

`total recovery budget >= all Media3 loader retry time + all MuxTV candidate-switch time`

Implementation consequences:

- only one active MuxTV recovery generation;
- internal Media3 loader retries must be bounded/understood;
- variant advancement occurs only after a candidate reaches a terminal failure according to the app policy;
- do not let default loader retries multiplied by N candidates create an unbounded user-visible wait;
- if a custom `LoadErrorHandlingPolicy` is introduced, it must be narrowly scoped, tested and counted inside the same recovery deadline;
- do not use Media3 resource `FallbackSelection` to cross the catalog/credential boundary between MuxTV variants.

### E7 — #30C diagnostics and Room schema ownership

Before any schema migration:

- reserve the next Room schema owner;
- coordinate #30C with #100;
- upgrade Room dependency separately from schema evolution.

Durable records remain typed, bounded and redacted. Never store raw locator, Authorization/Cookie, unrestricted headers or raw exception messages that can embed URLs/tokens.

### E8 — #30D TV Doctor Lite

Doctor is presentation over typed observations, not a second network/player engine.

Required TV acceptance:

- D-pad reachable;
- no focus traps;
- 720p long content scrollable;
- 1080p layout verified;
- Back predictable;
- export explicit and redacted;
- failed export cannot alter playback/catalog state.

### E9 — Lounge Light D5-D7

Only after interaction primitives and playback/Doctor contracts are stable:

- D5: light tokens/theme, system typography, shell/rail, reduced motion;
- D6: Channels, Search, Recent, Guide one surface per reviewable package;
- D7: Player, Doctor, Sources/settings.

Visual work styles existing state machines; it does not replace focus anchors, active-catalog truth, Player/Back identity or feature ViewModels.

### E10 — Alpha hardening

Before any alpha-quality claim:

- R8/minification and keep-rule analysis;
- Baseline Profile module and CUJs;
- Startup Profiles / DEX layout optimization;
- TTID + TTFD measurement;
- frame timing/jank measurement;
- low-RAM/endurance/zapping/recreation tests;
- signed release artifact;
- SBOM/provenance;
- physical Android TV / Google TV;
- weak/constrained target;
- Fire TV/Fire OS;
- codec/HDR/audio claims limited to observed hardware.

## 3. Summer 2026 dependency decisions

### Keep current stable versions

Current repository versions already match the summer-2026 stable AndroidX line for the main UI/player stack:

- Compose BOM `2026.06.00`;
- Compose for TV `tv-material 1.1.0` / `tv-foundation 1.0.0`;
- Media3 `1.10.1`;
- AGP `9.3.0` with Gradle `9.5.0` / JDK 17 compatibility.

Do not upgrade Media3 to the 1.11 RC line merely because it exists. Use stable `1.10.1` until a concrete fix/feature justifies prerelease risk.

### Room3 patch hardening

Repository currently uses Room3 `3.0.0`; stable `3.0.1` was released on 2026-07-29 with bug fixes including transaction-related behavior.

Create a separate dependency-only hardening PR after the current active branch queue is reconciled:

1. RED/guard: record current dependency and run database host/migration baseline;
2. bump `room3 = 3.0.1` only;
3. run Room unit tests;
4. verify generated schema parity is unchanged unless generation legitimately differs;
5. run old-edge/current Database migration matrix;
6. do not claim a schema version bump: dependency version and MuxTV Room schema version are independent.

## 4. Official Android guidance translated into repository rules

### Compose for TV

Leanback UI is deprecated; continue Compose for TV. Do not introduce a Leanback/View fallback architecture.

### Focus/input

Use default Compose focus search whenever it is sufficient. Use destination-scoped `FocusRequester`, `focusProperties`, focus groups or targeted `onPreviewKeyEvent` only where the default is demonstrably wrong. Preview handlers return `false` for keys they do not own. Do not manufacture global DPAD_CENTER/ENTER clicks.

### Media playback

Keep `Player` + `MediaSession` in `MediaSessionService`; Activity/ViewModel remain UI/controllers, not playback owners. One playback service owns recovery generation and player lifecycle.

### Performance

Do not add Baseline Profiles as an unmeasured file drop. Define real TV Critical User Journeys, generate profiles, benchmark enabled vs disabled, and measure on physical hardware. For Compose asynchronous destinations, explicitly report fully drawn state when the content required for interaction is ready.

## 5. Stop conditions

Stop and reassess if:

- source-head evidence identity breaks again;
- D-pad focus queues geometry animation;
- a preview-key handler globally owns OK/Enter;
- Media3 internal retries plus app retries exceed the declared recovery budget;
- a candidate from another canonical channel can enter fallback;
- temporary fallback rewrites preference;
- a second player/retry owner appears outside the service;
- diagnostics require secrets/raw locators;
- two branches claim the same Room migration version;
- visual work rewrites accepted feature state machines;
- emulator evidence is presented as physical decoder/HDR/Fire OS proof;
- a dependency upgrade is mixed with unrelated product behavior without a measured need.
