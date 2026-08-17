# Post-Stabilization Repository Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Advance the durable reviewed snapshot from pre-stabilization `main@faa179a` to the accepted post-EP-08/Lounge integration `main@18b520a` without turning `.work` back into dynamic GitHub state.

**Architecture:** Keep the schema-v2 split introduced by PR #172: `.work` stores only an explicitly reviewed ancestor snapshot, while exact branch/HEAD/dirty/PR state remains live Git/GitHub truth. This change only records accepted evidence and removes gaps that are now demonstrably closed.

**Tech Stack:** Markdown, YAML, PowerShell repository-truth contracts, Git/GitHub Actions.

## Global Constraints

- Preserve `status: reviewed_snapshot` and `live_state_authority: git` semantics.
- `implementation_source_commit` remains a compatibility alias of `reviewed_main_commit`.
- Do not persist dynamic claims such as open-PR counts or current branch names.
- Reviewed commit must exist and be an ancestor of every validation checkout.
- Do not change Android runtime, Gradle dependency, Room schema, Media3, UI, or release behavior.

---

### Task 1: Advance the reviewed snapshot

**Files:**
- Modify: `.work/meta/status.yaml`
- Modify: `.work/CURRENT-STATE.md`

**Interfaces:**
- Consumes: accepted `main@18b520a92836f9e61161dc9ce94e4fc7ded58b6b` and merged evidence from PRs #172, #173, #167 and #168.
- Produces: schema-v2 reviewed snapshot that identifies #132 as the next architecture slice.

- [ ] **Step 1: Update both snapshot commit aliases** to `18b520a92836f9e61161dc9ce94e4fc7ded58b6b` and keep them byte-identical.
- [ ] **Step 2: Record accepted stabilization evidence** for repository live-state separation, MediaSession callback correctness, EP-08 and Lounge integration.
- [ ] **Step 3: Remove only the two closed gaps** for EP-08 and Lounge; retain physical-device, lifecycle, Doctor, Baseline Profile and release gaps.
- [ ] **Step 4: Make #132 the first remaining stabilization step** without copying live GitHub coordination state into durable truth.

### Task 2: Verify and admit the control-plane checkpoint

**Files:**
- Test: `tools/ci/Test-RepositoryTruthContract.ps1`
- Test: `tools/ci/Test-RepositoryLiveStateContract.ps1`

**Interfaces:**
- Consumes: updated reviewed snapshot.
- Produces: exact-head CI evidence that the snapshot is internally consistent and an ancestor of the PR head.

- [ ] **Step 1: Run repository Fast/self-hosted validation** through the normal PR workflow.
- [ ] **Step 2: Require the truth/live-state contracts to pass** with relation `ancestor` or `exact`; `missing`/`diverged` is a hard failure.
- [ ] **Step 3: Merge only the exact green PR head** using expected-head protection.
