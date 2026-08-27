#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_BENCHMARK_EVIDENCE:?MUXTV_BENCHMARK_EVIDENCE is required}"

mkdir -p "$MUXTV_BENCHMARK_EVIDENCE"
INVENTORY_PATH="$MUXTV_BENCHMARK_EVIDENCE/avd-inventory.txt"
avdmanager list avd -c | sed '/^[[:space:]]*$/d' | tee "$INVENTORY_PATH"
mapfile -t avds < "$INVENTORY_PATH"
if (( ${#avds[@]} != 1 )); then
  echo "Expected exactly one AVD, found ${#avds[@]}: ${avds[*]-<none>}" >&2
  exit 1
fi
if [[ "${avds[0]}" != "$MUXTV_EXPECTED_AVD" ]]; then
  echo "Expected canonical AVD $MUXTV_EXPECTED_AVD, found ${avds[0]}." >&2
  exit 1
fi

chmod +x ./gradlew
./gradlew \
  :benchmark:macrobenchmark:connectedBenchmarkReleaseAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

SUMMARY_PATH="$MUXTV_BENCHMARK_EVIDENCE/android-test-results.json"
pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths benchmark/macrobenchmark \
  -OutputPath "$SUMMARY_PATH"
export MUXTV_BENCHMARK_SUMMARY="$SUMMARY_PATH"
pwsh -NoProfile -Command '$summary = Get-Content -LiteralPath $env:MUXTV_BENCHMARK_SUMMARY -Raw | ConvertFrom-Json; if (([int]$summary.tests - [int]$summary.skipped) -lt 1) { throw "Macrobenchmark dry-run executed zero non-skipped tests." }'

echo "Hosted Macrobenchmark API36 dry-run passed with at least one non-skipped test."
