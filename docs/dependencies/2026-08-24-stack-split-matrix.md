# MuxTV Android Stack Split Matrix — 2026-08-24

Status: planning/ownership contract. This document does not claim any staged dependency candidate has passed compilation or device validation.

## 1. Purpose

PR #190 is a **combined compatibility probe**, not a final merge unit. The goal is to discover cross-version incompatibilities efficiently when the self-hosted runner returns, then land accepted versions in isolated owner PRs.

No dependency package below should change the accepted U0/U1/M0 baseline before that stabilization sequence is complete.

## 2. Repository baseline and staged candidates

| Surface | Accepted `main@5aa9c108` | Staged candidate in #190 | Final owner |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.3.0 | 9.4.0 | toolchain split under #179; do not mix with library patches |
| Gradle wrapper | 9.5.0 | 9.7.1 | toolchain split; post-acceptance experiments #195 |
| Kotlin | 2.4.10 | keep 2.4.10 | no change in this train |
| KSP | 2.3.10 | keep 2.3.10 | no change in this train |
| Room3 | 3.0.0 | 3.0.1 | #146 |
| Navigation3 | 1.1.4 | 1.1.6 | #197 |
| Paging | 3.5.0 | 3.5.1 | #198 |
| Media3 | 1.10.1 | 1.11.0 | #199 |
| Compose BOM | 2026.06.00 | 2026.08.00 / Compose 1.12 line | #200 |
| AndroidX Tracing | 1.3.0 | 2.0.0 (separate observability train) | #192 |
| Benchmark | 1.5.0-alpha07 | 1.5.0-rc01 | #27/#31 tooling owner; keep independent from production dependencies |
| WorkManager | 2.11.2 | keep 2.11.2 | #191 uses existing stable callbacks; no RC adoption |
| OkHttp | 5.3.0 | keep 5.3.0 | #193 uses current EventListener API |

## 3. Stabilization barrier

Default merge ordering is:

```text
D0/#181 accepted
    ↓
U0/#189 runtime characterization
    ↓
U1 minimal UI correction
    ↓
M0/#178 measurement correctness
    ↓
freeze accepted stabilization baseline
    ↓
isolated dependency merge train
```

A dependency may bypass that ordering only to resolve a demonstrated blocker, and the exception must document why changing the baseline is safer than deferring the update.

## 4. Why each package is separate

### Room3 3.0.1 — #146

Risk surface: generated code, transactions, migration/schema tooling.

Acceptance owner must prove:

- no MuxTV schema-version change;
- exact generated schema parity/equivalence;
- migration/database tests;
- canonical API26/API36 database acceptance;
- no pool/FTS/schema tuning bundled with the patch.

Room connection-pool, FTS5 and `WITHOUT ROWID` experiments remain #196 and are blocked by #178.

### Navigation3 1.1.6 — #197

Risk surface: back-stack/saveable state, rapid Back/pop, TV focus restoration.

Acceptance owner must prove:

- rapid repeated Back/pop contract;
- destination/saveable state remains correct;
- current focus-restoration ownership remains intact;
- no U0/U1 geometry fix mixed into the version update.

### Paging 3.5.1 — #198

Risk surface: refresh key/anchor behavior, Channels/Search paging and focus anchoring.

Acceptance owner must prove:

- result semantics unchanged;
- focus/nearest-surviving-item behavior unchanged;
- no query/index/PagingConfig retuning.

### Media3 1.11.0 — #199

Risk surface: player/session/threading/codec behavior.

Acceptance owner must prove:

- one service-owned ExoPlayer/MediaSession remains authoritative;
- #175 seek mutation authority remains unique;
- first-frame/recovery/external playback/channel replacement remain correct;
- no PlayerPool/preload/buffer/cache change is bundled.

### Compose August 2026 / 1.12 — #200

Risk surface: layout/focus/runtime behavior that overlaps current U0/U1 characterization.

Acceptance owner must prove:

- accepted U1 geometry does not move unintentionally;
- Home/Channels/Guide/Search/Settings D-pad/focus regression is green;
- 720p/1080p display configurations reuse canonical API36;
- no experimental Styles/runtime flags or mass stability annotations are introduced.

### Tracing 2.0 — #192

This is an observability boundary, not part of the production dependency mega-bump.

Acceptance owner must prove:

- product result is unchanged if tracing is disabled/fails;
- secret-bearing data cannot enter trace attributes;
- trace taxonomy is bounded;
- production does not persist trace files by default.

### Benchmark 1.5 RC — #27/#31 tooling

Benchmark artifacts must remain test/tooling-only. A benchmark version must never become a production runtime dependency.

### AGP / Gradle toolchain

Toolchain adoption is separate from library adoption because it can change compilation, generated code, configuration cache, lint/R8 and wrapper behavior simultaneously.

#195 owns later experiments such as parallel configuration-cache and Isolated Projects; those are not automatically enabled by adopting Gradle 9.7.x.

## 5. Common acceptance contract

Every final dependency PR must provide:

1. one dependency owner/reason;
2. exact before/after resolved version;
3. no unrelated architecture/refactor work;
4. exact final source SHA;
5. host/build evidence appropriate to the changed surface;
6. canonical API26/API36 evidence where Android runtime risk requires it;
7. no additional AVD identity;
8. explicit classification of any generated/source diff;
9. a rollback boundary that does not require reverting unrelated updates.

## 6. Two-AVD rule

All Android acceptance uses only:

- `MuxTV_TV_OLD_API26`;
- `MuxTV_TV_CURRENT_API36`.

A dependency PR must not create its own AVD for “Navigation”, “Compose”, “Media3”, “Room”, “benchmark”, low-RAM, 720p or any other purpose.

## 7. What #190 is allowed to do when the runner returns

The combined probe may answer:

- does the full proposed stack resolve/compile together?;
- which version introduces a source/API/build incompatibility?;
- does Gradle/AGP interaction require wrapper/build-logic changes?;
- which final isolated package needs an adaptation before it is cut?

It must not be used to claim:

- each dependency is individually safe;
- UI/focus behavior is unchanged;
- Media3 runtime is qualified;
- Room schema behavior is unchanged;
- a combined mega-PR is acceptable for merge.

## 8. Result handling

If #190 is green:

- treat it as compatibility evidence only;
- cut/rebuild isolated owner branches from the accepted stabilization baseline;
- validate each final head independently.

If #190 fails:

- identify the smallest owning surface;
- do not fix unrelated packages in the same commit merely to make the bundle green;
- reproduce the failure in the isolated owner branch before adopting a compatibility change.

## 9. Explicit non-adoptions in this train

- no Kotlin RC upgrade;
- no WorkManager RC for schedule listeners;
- no Compose experimental Styles/SlotTable flags;
- no Media3 PlayerPool or second player;
- no Room pool/FTS5/`WITHOUT ROWID` optimization;
- no Gradle Isolated Projects/parallel configuration-cache by default;
- no SimpleCache/buffer tuning;
- no Rust/FFmpeg/libmpv expansion;
- no additional Android TV AVD.
