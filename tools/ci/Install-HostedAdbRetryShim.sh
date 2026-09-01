#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
shim_dir="$repo_root/tools/ci/adb-shim"

if [[ -z "${GITHUB_PATH:-}" ]]; then
  echo "MuxTV hosted ADB shim: GITHUB_PATH is not available." >&2
  exit 1
fi
if [[ ! -x "$shim_dir/adb" ]]; then
  echo "MuxTV hosted ADB shim is missing or not executable: $shim_dir/adb" >&2
  exit 1
fi

printf '%s\n' "$shim_dir" >> "$GITHUB_PATH"
echo "MuxTV hosted ADB retry shim installed: $shim_dir"
