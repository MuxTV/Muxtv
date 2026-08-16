# Repository Truth Live-State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Устранить класс дефектов, при котором `.work` snapshot является валидным ancestor, но агент ошибочно воспринимает его как точный текущий Git/GitHub state.

**Architecture:** Разделить durable reviewed snapshot и live repository state. `.work/meta/status.yaml` и `.work/CURRENT-STATE.md` описывают явно датированный/зафиксированный reviewed snapshot; `tools/ci/Get-RepositoryLiveState.ps1` вычисляет точный HEAD/branch/dirty/snapshot relation из Git при каждом запуске. Contract tests запрещают называть snapshot динамическим current main и проверяют exact/ancestor/diverged behavior на временном Git-репозитории.

**Tech Stack:** PowerShell 7, Git CLI, existing `.work` YAML/Markdown metadata, existing `tools/verify-local.ps1` CI entrypoint.

## Global Constraints

- Не обращаться к GitHub API из `Get-RepositoryLiveState.ps1`; script должен работать offline в локальном checkout и CI.
- Не хранить `open PR count`, конкретный список open PR или иной изменяемый без commit GitHub state в durable `.work` truth.
- `git rev-parse HEAD` — единственный authority для exact live commit.
- Reviewed snapshot SHA обязан существовать и быть `exact`, `ancestor` или явно `diverged`; молчаливого fallback нет.
- Не менять product/runtime Android code в этом PR.
- Не менять architecture version 2.
- Не ослаблять shallow-clone recovery существующего truth contract.

---

### Task 1: Add RED live-state regression contract

**Files:**
- Create: `tools/ci/Test-RepositoryLiveStateContract.ps1`
- Modify later: `tools/verify-local.ps1`
- Production file intentionally absent during RED: `tools/ci/Get-RepositoryLiveState.ps1`

**Interfaces:**
- Consumes: Git CLI and a temporary repository created under `.work/contract-tests/repository-live-state`.
- Produces: contract assertions for JSON fields `head`, `branch`, `dirty`, `reviewedSnapshot`, `snapshotRelation`, `commitsAheadOfSnapshot`.

- [ ] **Step 1: Write the failing test**

The test creates a temporary repository with two commits. Commit A is written as `truth_snapshot.reviewed_main_commit`; HEAD is commit B. It invokes `Get-RepositoryLiveState.ps1 -RepositoryRoot <temp>` and asserts:

```powershell
if ($state.head -cne $headB) { throw "Live state did not report exact HEAD." }
if ($state.reviewedSnapshot -cne $headA) { throw "Live state lost reviewed snapshot identity." }
if ($state.snapshotRelation -cne "ancestor") { throw "Expected ancestor snapshot relation." }
if ([int]$state.commitsAheadOfSnapshot -ne 1) { throw "Expected one commit of snapshot drift." }
if ($state.dirty) { throw "Fresh contract repo must be clean." }
```

Then dirty one tracked file and assert `dirty=true` without changing `head`.

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
pwsh -NoProfile -File .\tools\ci\Test-RepositoryLiveStateContract.ps1
```

Expected: FAIL because `tools/ci/Get-RepositoryLiveState.ps1` does not exist.

- [ ] **Step 3: Commit RED only**

```bash
git add tools/ci/Test-RepositoryLiveStateContract.ps1
git commit -m "test(repo): reproduce repository snapshot drift"
```

### Task 2: Implement the live-state reader

**Files:**
- Create: `tools/ci/Get-RepositoryLiveState.ps1`
- Test: `tools/ci/Test-RepositoryLiveStateContract.ps1`

**Interfaces:**
- Parameters: `[string]$RepositoryRoot` defaulting to repository root; `[switch]$AsJson`.
- Output object fields:
  - `head: string`
  - `branch: string`
  - `dirty: bool`
  - `reviewedSnapshot: string`
  - `snapshotRelation: "exact" | "ancestor" | "diverged" | "missing"`
  - `commitsAheadOfSnapshot: int | null`

- [ ] **Step 1: Parse one explicit snapshot field**

Read `.work/meta/status.yaml` and require exactly one:

```yaml
truth_snapshot:
  reviewed_main_commit: <40 hex>
```

Do not add a YAML dependency; follow the repository's existing regex-based contract style.

- [ ] **Step 2: Resolve exact Git state**

Use checked exit codes for:

```powershell
git -C $RepositoryRoot rev-parse HEAD
git -C $RepositoryRoot branch --show-current
git -C $RepositoryRoot status --porcelain=v1
git -C $RepositoryRoot cat-file -e "$reviewedSnapshot^{commit}"
git -C $RepositoryRoot merge-base --is-ancestor $reviewedSnapshot HEAD
git -C $RepositoryRoot rev-list --count "$reviewedSnapshot..HEAD"
```

Detached HEAD must be represented as `DETACHED`, not `unknown`.

- [ ] **Step 3: Implement relation semantics**

```text
HEAD == snapshot                      -> exact
snapshot exists && ancestor of HEAD   -> ancestor
snapshot exists && not ancestor       -> diverged
snapshot missing                      -> missing
```

Only `exact` and `ancestor` may have an integer `commitsAheadOfSnapshot`.

- [ ] **Step 4: Run RED test and verify GREEN**

```powershell
pwsh -NoProfile -File .\tools\ci\Test-RepositoryLiveStateContract.ps1
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/ci/Get-RepositoryLiveState.ps1 tools/ci/Test-RepositoryLiveStateContract.ps1
git commit -m "feat(repo): expose exact live repository state"
```

### Task 3: Make durable truth explicitly snapshot-based

**Files:**
- Modify: `.work/meta/status.yaml`
- Modify: `.work/CURRENT-STATE.md`
- Test/validator: `tools/ci/Test-RepositoryTruthContract.ps1`

**Interfaces:**
- Produces the canonical durable field `truth_snapshot.reviewed_main_commit`.
- `CURRENT-STATE.md` frontmatter consumes the same SHA via `reviewed_main_commit`.

- [ ] **Step 1: Add failing truth assertions before metadata changes**

Extend `Test-RepositoryTruthContract.ps1` to require:

```powershell
$statusSnapshot = Get-SingleMatch $status '^\s+reviewed_main_commit:\s*([0-9a-f]{40})\s*$' "status reviewed_main_commit"
$currentSnapshot = Get-SingleMatch $currentState '^reviewed_main_commit:\s*([0-9a-f]{40})\s*$' "current-state reviewed_main_commit"
if ($statusSnapshot -cne $currentSnapshot) {
    throw "CURRENT-STATE and status.yaml disagree on reviewed snapshot commit."
}
```

Also reject dynamic GitHub coordination claims in durable current-state text:

```powershell
$forbiddenDynamicClaims = @(
    'Открытых PR на принятой базе нет',
    'accepted open PR count',
    'open PRs: none'
)
```

The exact implementation may use case-insensitive literal checks, not a broad regex that rejects historical discussion.

- [ ] **Step 2: Run repository truth contract and verify RED**

```powershell
pwsh -NoProfile -File .\tools\ci\Test-RepositoryTruthContract.ps1
```

Expected: FAIL because current metadata has no explicit reviewed snapshot field and contains a durable open-PR assertion.

- [ ] **Step 3: Update status metadata**

Add:

```yaml
truth_snapshot:
  reviewed_main_commit: faa179a1301ab9b0977cc8991aee803b647ba7ba
  reviewed_main_short: faa179a
  reviewed_through_pr: 171
  live_git_authority: git
  live_github_authority: github_api
```

Existing historical evidence/artifact records remain intact. Existing commit fields may remain temporarily for compatibility but their role strings must say `reviewed snapshot` rather than `latest/current branch head` where applicable.

- [ ] **Step 4: Rewrite CURRENT-STATE frontmatter and classification**

Required frontmatter:

```yaml
status: reviewed_snapshot
last_reviewed: 2026-08-16
architecture_version: 2
reviewed_main_commit: faa179a1301ab9b0977cc8991aee803b647ba7ba
live_state_authority: git
```

The first section must state that the snapshot is reviewed through `main@faa179a` / PR #171 and that live branch/HEAD/PR state is obtained at execution time. Remove the statement that there are no open PRs.

- [ ] **Step 5: Run truth + live-state contracts**

```powershell
pwsh -NoProfile -File .\tools\ci\Test-RepositoryTruthContract.ps1
pwsh -NoProfile -File .\tools\ci\Test-RepositoryLiveStateContract.ps1
```

Expected: PASS.

### Task 4: Integrate the regression into normal validation

**Files:**
- Modify: `tools/verify-local.ps1`
- Test: existing Fast/Full workflow execution.

**Interfaces:**
- `verify-local.ps1` must run `Test-RepositoryLiveStateContract.ps1` for every non-`DeviceOnly` mode immediately after the existing repository truth contract.

- [ ] **Step 1: Add the contract invocation**

```powershell
& (Join-Path $repositoryRoot "tools\ci\Test-RepositoryTruthContract.ps1") -RepositoryRoot $repositoryRoot
& (Join-Path $repositoryRoot "tools\ci\Test-RepositoryLiveStateContract.ps1") -RepositoryRoot $repositoryRoot
```

Keep shallow-clone validation in Full.

- [ ] **Step 2: Run Fast**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
```

Expected: exit 0 and both repository contracts pass before Gradle validation.

- [ ] **Step 3: Run Full**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Expected: exit 0 including shallow-clone truth validation.

- [ ] **Step 4: Commit**

```bash
git add .work/meta/status.yaml .work/CURRENT-STATE.md tools/ci/Test-RepositoryTruthContract.ps1 tools/verify-local.ps1
git commit -m "fix(repo): separate reviewed snapshot from live state"
```

### Task 5: Exact-head CI verification and PR review

**Files:** no new product files.

- [ ] **Step 1: Verify branch diff**

Expected changed scope only:

```text
.work/CURRENT-STATE.md
.work/meta/status.yaml
docs/superpowers/plans/2026-08-16-*.md
tools/ci/Get-RepositoryLiveState.ps1
tools/ci/Test-RepositoryLiveStateContract.ps1
tools/ci/Test-RepositoryTruthContract.ps1
tools/verify-local.ps1
```

- [ ] **Step 2: Confirm exact-head workflow conclusions**

Required:

```text
Self-hosted validation / Full validation: success
```

Android TV device execution is not required solely for this repository-control-plane change unless risk routing selects it.

- [ ] **Step 3: Review the generated live state**

Run:

```powershell
pwsh -NoProfile -File .\tools\ci\Get-RepositoryLiveState.ps1 -AsJson
```

Confirm that `head` equals the exact checked-out branch head and `reviewedSnapshot` remains `faa179a...`; the distinction is intentional.

- [ ] **Step 4: Merge only after fresh evidence**

Do not call the control-plane defect fixed until the exact branch head has a fresh green validation run.
