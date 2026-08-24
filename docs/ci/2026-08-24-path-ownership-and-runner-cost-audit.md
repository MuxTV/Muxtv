# CI path ownership and runner-cost audit — 2026-08-24

Status: static audit only. **No workflow file is changed by this document.**

Reviewed accepted baseline: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Owners:

- #144 — whether a change should enter a device/expensive lane;
- #101 — which database-connected suites execute once the DB lane is admitted;
- #27/#31 — measurement/release evidence;
- #181 — exact two-AVD repository contract.

## Objective

Make runner allocation predictable and proportional to changed risk while preserving exact-head evidence and branch-protection compatibility.

This is especially important on the singleton self-hosted runner: unnecessary admitted jobs do not only cost compute time, they delay every genuinely device-bound task behind them.

## Hard invariant

Repository-owned Android TV AVD identities remain exactly:

- `MuxTV_TV_OLD_API26`;
- `MuxTV_TV_CURRENT_API36`.

CI routing optimization must reduce work by better admission/suite selection, **never by creating additional specialized AVDs or parallel emulator identities**.

---

## Finding 1 — every ordinary same-repo PR pays a self-hosted admission tax

Workflow: `.github/workflows/self-hosted-validation.yml`.

Current trigger:

```yaml
pull_request:
  branches:
    - main
```

There is no `paths` or `paths-ignore` filter.

Therefore any same-repository PR targeting `main` schedules the self-hosted validation job, including a docs-only/test-data-only PR. The PR job name remains `Full validation` for branch-protection compatibility, although ordinary PRs select `Fast` internally.

### Consequence

Opening a PR is itself a runner-affecting action even when its diff cannot affect Android/product behavior.

While the runner is unavailable, non-PR branches are the correct place for runner-free preparation. Do not open a PR merely to obtain review UI while this routing remains active.

### Future decision under #144

Reduce unnecessary allocation without breaking required-check identity. Candidate designs must be proven against branch protection before adoption. Options include:

- a cheap admission/classifier job that preserves the required check contract and allocates self-hosted work only for admitted risk;
- path-aware mode selection inside one stable workflow/check identity;
- an explicitly accepted docs/test-data skip contract if branch protection permits it.

Do not implement a hosted admission layer solely by assumption about quota/cost. The earlier U0 admission case solved a specific singleton-runner problem but does not automatically justify moving all CI to hosted runners.

---

## Finding 2 — Product Device Matrix is harness/tooling triggered, not general product-source triggered

Workflow: `.github/workflows/android-tv-product-device-matrix.yml`.

Automatic PR trigger paths currently include:

- the workflow itself;
- `tools/android/**`;
- `tools/measurements/**`;
- selected CI risk/evidence/preflight/reset scripts.

It does **not** automatically run merely because ordinary `app/tv`, feature or player production code changes.

### Consequence

Its name can be misleading if interpreted as “all product code always runs API26/API36”. In current routing it is primarily an Android harness/device infrastructure acceptance lane.

That is not inherently wrong, but documentation and #144 risk routing must remain explicit about what evidence another production PR still requires before merge/release.

### Cost

When admitted it occupies the device runner and sequentially executes the canonical API26/API36 matrix.

Do not weaken exact old/current correctness solely to save queue time; improve admission and suite ownership instead.

---

## Finding 3 — Database Matrix is broad by source path and expensive by execution

Workflow: `.github/workflows/database-migration-device-matrix.yml`.

Automatic PR paths:

- `catalog/importer/.../EpgRevisionImporter.kt`;
- all `core/database/src/main/kotlin/app/muxtv/database/**`;
- all DB androidTest files;
- all exported DB schemas.

### Consequence

Any production DAO/entity/repository change under the DB package admits the API26/API36 database device matrix, even if the changed code is a query/read-path correction rather than a migration.

That is a conservative correctness policy. The current problem is that the lane historically executes broader connected work than strictly migration/database-owned tests; #101 already owns suite narrowing after admission.

### Correct optimization split

- #144 decides whether a path/change class needs the device lane at all;
- #101 decides which connected suites run once admitted;
- neither issue creates a DB-specific emulator.

Do not conflate path admission and suite selection into one giant CI rewrite.

---

## Finding 4 — App TV lint and Media3 lint overlap on player/toolchain changes

### App TV lint

Automatic paths include:

- `app/tv/**`;
- `core/designsystem/**`;
- `core/ui/**`;
- `feature/**`;
- `player/api/**`;
- `player/media3/**`;
- Gradle/version/build-root surfaces.

### Media3 lint

Automatic paths include:

- `player/media3/**`;
- Gradle/version/build-root surfaces.

### Consequence

A Media3 or central Gradle change can schedule:

1. universal self-hosted validation;
2. App TV lint;
3. Media3 lint;
4. possibly other path-specific lanes.

This may be correct for high-risk toolchain/player changes, but the duplicated setup/checkout/runner preflight cost should be quantified before any workflow consolidation.

Do not merge lint ownership merely because the path sets overlap; separate lint reports may preserve attribution and required-check contracts.

---

## Finding 5 — Measurement variance smoke has a targeted trigger surface

Workflow: `.github/workflows/measurement-variance-smoke.yml`.

Automatic paths currently include:

- measurement core main/tests;
- `tools/measurements/**`;
- `Invoke-CatalogDatabaseMeasurement.ps1`;
- `Invoke-PlayerProxyMeasurement.ps1`.

It runs on canonical API36 only.

### Known routing debt

#178/M0 already identifies that the variance lane must react to the catalog database measurement runner/tests that define the published result boundary. Until #178 is accepted, do not treat new DB measurement numbers as authoritative.

### Policy

Measurement tooling changes belong in this lane because they can invalidate the evidence generator itself. Ordinary product DB/feature changes should not wake variance merely because they might affect performance; measurement acceptance should be explicit when a performance claim is made.

---

## Finding 6 — heavy evidence workflows are already manual-only

The following are `workflow_dispatch` only:

### Manual heavy M3U stress evidence

- self-hosted host runner;
- claim-eligible run requires accepted `refs/heads/main`;
- heavy deterministic 5x10k + 5x50k stress.

This is an appropriate model: correctness fixtures can remain cheap JVM tests, while expensive scale evidence is intentionally invoked for an accepted source SHA.

### Benchmark foundation dry run

- self-hosted device runner;
- JMH allocation smoke + Macrobenchmark dry run;
- API36 benchmark identity/configuration, not a third AVD.

### Integration acceptance gate

- self-hosted device runner;
- Full host validation;
- canonical API26/API36 DeviceMatrix;
- exact selected SHA.

### Conclusion

Do **not** auto-enable these heavy workflows on every PR in the name of “more CI”. Their manual accepted-SHA nature is useful cost control.

---

## Current path-to-cost matrix

This table describes observed trigger topology, not guaranteed total jobs after GitHub event evaluation.

| Change class | Universal self-hosted | App lint | Media3 lint | DB matrix | Product device matrix | Variance | Heavy manual |
| --- | --- | --- | --- | --- | --- | --- | --- |
| docs-only PR | yes | no | no | no | no | no | manual only |
| M3U fixture/test resource | yes | usually no | no | no | no | no | manual stress only |
| `app/tv/**` | yes | yes | no | no | no by path | no | manual |
| `feature/**` | yes | yes | no | no | no by path | no | manual |
| `player/media3/**` | yes | yes | yes | no | no by path | no | manual |
| central Gradle/version | yes | yes | yes | no unless DB path | no by current product-matrix path | no | manual |
| DB production package | yes | usually no unless shared build path | no | yes API26+36 | no by DB path alone | no | manual |
| `tools/android/**` | yes | no unless other path | no | no | yes API26+36 | maybe, for listed measurement scripts | manual |
| `tools/measurements/**` | yes | no | no | no | yes | yes API36 | manual |
| measurement core | yes | no | no | no | no unless tools path | yes API36 | manual |

Because GitHub `pull_request.paths` uses the cumulative PR diff, once an expensive path remains in a PR's diff, later commits do not make that workflow irrelevant merely because the last commit is docs-only.

---

## Target admission model after runner availability

Do not implement all of this at once. Use independent contracts.

### Layer A — stable PR check identity

Preserve required-check compatibility first. Determine which check names branch protection actually requires before changing workflow topology.

### Layer B — deterministic risk classifier

Classify the cumulative PR changed-path set into explicit risk facts, for example:

- `host_fast_required`;
- `android_lint_required`;
- `media3_lint_required`;
- `database_device_required`;
- `device_current_required`;
- `device_matrix_required`;
- `measurement_variance_required`.

The classifier must be repository-tested and fail closed for unknown high-risk build/runtime surfaces.

### Layer C — suite selection

Once admitted, execute only suites owned by that lane. #101 is the concrete DB example.

### Layer D — heavyweight accepted-SHA evidence

Keep integration, benchmark and large M3U stress explicit/manual unless release policy separately promotes one to a gate with measured cost justification.

---

## Candidate low-risk savings, ordered

1. Finish #101 suite narrowing; it reduces work without weakening DB admission.
2. Finish #178 risk routing for measurement generator changes.
3. Under #144, add static tests for current path-to-lane ownership before modifying routing.
4. Measure actual queue + execution wall time per lane over several equivalent runs.
5. Only then decide whether universal PR self-hosted validation needs an admission split.
6. Audit duplicate lint/setup cost after dependency/toolchain stabilization.

Do not optimize CI based only on YAML line count.

## Evidence to collect when runner returns

For each automatic lane capture over multiple comparable PR/run classes:

- queue delay;
- runner allocation duration;
- checkout/preflight/setup duration;
- Gradle configuration duration/cache reuse;
- actual test/lint/device duration;
- artifact publication duration/failure family;
- percentage of runs that discover a failure unique to that lane;
- changed-path/risk classification.

This supports an evidence-based cost/coverage matrix rather than simply deleting slow checks.

## Stop condition while Actions are unavailable

- do not modify `.github/workflows/**`;
- do not open a PR solely for these docs, because universal self-hosted validation would be scheduled;
- do not rerun existing Actions;
- do not change #189/#190;
- do not move the U0 marker.

The next implementation step is a tested static routing contract when an executable host gate is available.