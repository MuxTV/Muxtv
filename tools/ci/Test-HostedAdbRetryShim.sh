#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
shim="$repo_root/tools/ci/adb-shim/adb"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
count_file="$tmp/count"
fake_adb="$tmp/fake-adb"

cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count=0
if [[ -f "$COUNT_FILE" ]]; then
  count="$(cat "$COUNT_FILE")"
fi
count=$((count + 1))
printf '%s' "$count" > "$COUNT_FILE"

case "${FAKE_ADB_MODE:-transport}" in
  permanent)
    echo "permission denied" >&2
    exit 1
    ;;
  generic255)
    exit 255
    ;;
  silent255)
    if (( count < 3 )); then
      exit 255
    fi
    printf 'ok\n'
    ;;
  transport)
    if (( count < 3 )); then
      echo "adb: device 'emulator-5554' not found" >&2
      exit 255
    fi
    printf '1\n'
    ;;
  *)
    echo "Unknown fake adb mode." >&2
    exit 2
    ;;
esac
EOF
chmod +x "$fake_adb"

stdout_file="$tmp/transport.stdout"
stderr_file="$tmp/transport.stderr"
COUNT_FILE="$count_file" \
MUXTV_REAL_ADB="$fake_adb" \
MUXTV_ADB_RETRY_ATTEMPTS=4 \
MUXTV_ADB_RETRY_DELAY_SECONDS=0 \
  "$shim" -s emulator-5554 shell getprop sys.boot_completed >"$stdout_file" 2>"$stderr_file"
if [[ "$(cat "$count_file")" != "3" ]]; then
  echo "Expected explicit transport failure to recover on attempt 3." >&2
  exit 1
fi
if [[ "$(cat "$stdout_file")" != "1" ]]; then
  echo "Recovered adb stdout must contain only the successful command output." >&2
  cat "$stdout_file" >&2
  exit 1
fi
if ! grep -q "transient transport failure" "$stderr_file" ||
   ! grep -q "device 'emulator-5554' not found" "$stderr_file"; then
  echo "Expected transport failure detail and bounded retry diagnostics on stderr." >&2
  exit 1
fi

printf '0' > "$count_file"
stdout_file="$tmp/silent255.stdout"
stderr_file="$tmp/silent255.stderr"
COUNT_FILE="$count_file" \
FAKE_ADB_MODE=silent255 \
MUXTV_REAL_ADB="$fake_adb" \
MUXTV_ADB_RETRY_ATTEMPTS=4 \
MUXTV_ADB_RETRY_DELAY_SECONDS=0 \
  "$shim" -s emulator-5554 shell input keyevent 82 >"$stdout_file" 2>"$stderr_file"
if [[ "$(cat "$count_file")" != "3" || "$(cat "$stdout_file")" != "ok" ]]; then
  echo "Expected safe bootstrap exit 255 to recover on attempt 3." >&2
  exit 1
fi

printf '0' > "$count_file"
set +e
COUNT_FILE="$count_file" \
FAKE_ADB_MODE=generic255 \
MUXTV_REAL_ADB="$fake_adb" \
MUXTV_ADB_RETRY_ATTEMPTS=4 \
MUXTV_ADB_RETRY_DELAY_SECONDS=0 \
  "$shim" -s emulator-5554 shell custom-side-effect >/dev/null 2>/dev/null
status=$?
set -e
if (( status != 255 )) || [[ "$(cat "$count_file")" != "1" ]]; then
  echo "Generic silent exit 255 must be preserved without retry." >&2
  exit 1
fi

printf '0' > "$count_file"
stdout_file="$tmp/permanent.stdout"
stderr_file="$tmp/permanent.stderr"
set +e
COUNT_FILE="$count_file" \
FAKE_ADB_MODE=permanent \
MUXTV_REAL_ADB="$fake_adb" \
MUXTV_ADB_RETRY_ATTEMPTS=4 \
MUXTV_ADB_RETRY_DELAY_SECONDS=0 \
  "$shim" version >"$stdout_file" 2>"$stderr_file"
status=$?
set -e
if (( status != 1 )) || [[ "$(cat "$count_file")" != "1" ]]; then
  echo "Permanent adb errors must preserve exit code and must not be retried." >&2
  exit 1
fi
if [[ -s "$stdout_file" ]] || ! grep -q "permission denied" "$stderr_file"; then
  echo "Permanent adb stdout/stderr channels must be preserved." >&2
  exit 1
fi

echo "Hosted ADB retry shim contract passed."
