# Windows Self-Hosted Clean Workspace Specification

**Status:** planned immediately after PR #42; no runtime product dependency.

## Problem

`actions/checkout` currently executes `git clean -ffdx` before repository steps can enable Windows long-path support. Kotlin-generated class names exceed the legacy path limit, checkout logs warnings, and some untracked build outputs may survive into the next run.

A validation run must never depend on whether a stale class/report/cache file happened to remain in the workspace.

## Selected change

In the permanent self-hosted workflow:

1. configure checkout with `clean: false`;
2. immediately configure repository-local `core.longpaths=true`;
3. execute explicit `git reset --hard HEAD`;
4. execute explicit `git clean -ffdx` and fail if cleanup fails;
5. record the cleanup command/output in evidence;
6. only then inspect the environment and run validation.

## Constraints

- do not modify BIOS, Windows features, global machine policy or the Android SDK;
- do not delete outside `GITHUB_WORKSPACE`;
- preserve pinned action commits;
- preserve Fast/Full/DeviceCurrent/DeviceMatrix behavior;
- do not hide cleanup failures with `continue-on-error`;
- keep Gradle caches outside the correctness boundary: stale cache may improve speed but must not preserve untracked build outputs.

## Acceptance

- two consecutive Full runs start from a workspace with no surviving untracked build directories;
- no `Filename too long` cleanup warning appears;
- explicit cleanup fails the job on an undeletable path;
- evidence includes Git version, long-path setting and clean status;
- harness/device behavior remains unchanged.
