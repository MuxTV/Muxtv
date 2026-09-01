#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <evidence-dir> <entrypoint> [post-entrypoint]" >&2
  exit 64
fi

evidence_dir="$1"
entrypoint="$2"
post_entrypoint="${3:-}"

validate_entrypoint() {
  local value="$1"
  if [[ ! "$value" =~ ^[A-Za-z0-9._-]+\.sh$ ]]; then
    echo "Hosted Android entrypoint has an invalid file name." >&2
    exit 64
  fi
  if [[ ! -f "./tools/ci/$value" ]]; then
    echo "Hosted Android entrypoint is unavailable: tools/ci/$value" >&2
    exit 66
  fi
}

validate_entrypoint "$entrypoint"
if [[ -n "$post_entrypoint" ]]; then
  validate_entrypoint "$post_entrypoint"
fi

mkdir -p "$evidence_dir"
marker="$evidence_dir/mux-tv-script-started.marker"
printf 'started=true\nentrypoint=%s\n' "$entrypoint" > "$marker"

bash "./tools/ci/$entrypoint"
if [[ -n "$post_entrypoint" ]]; then
  bash "./tools/ci/$post_entrypoint"
fi
