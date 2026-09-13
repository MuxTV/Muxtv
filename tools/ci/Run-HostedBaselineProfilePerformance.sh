#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_BENCHMARK_EVIDENCE:?MUXTV_BENCHMARK_EVIDENCE is required}"
: "${MUXTV_SOURCE_COMMIT:?MUXTV_SOURCE_COMMIT is required}"

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

pwsh -NoProfile -File ./tools/ci/Assert-EvidenceCommit.ps1 -ExpectedCommit "$MUXTV_SOURCE_COMMIT"

cat > "$MUXTV_BENCHMARK_EVIDENCE/run-metadata.json" <<EOF
{
  "source_commit": "$MUXTV_SOURCE_COMMIT",
  "avd": "$MUXTV_EXPECTED_AVD",
  "comparison": "cold-start-none-vs-baseline-profile-require",
  "iterations_per_mode": 10,
  "claim_scope": "LIMITED_EVIDENCE",
  "note": "GitHub-hosted emulator measurements are reproducibility diagnostics and are not physical-TV performance certification."
}
EOF

chmod +x ./gradlew
./gradlew \
  :benchmark:macrobenchmark:connectedBenchmarkReleaseAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.benchmark.BaselineProfilePerformanceComparison \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR

SUMMARY_PATH="$MUXTV_BENCHMARK_EVIDENCE/android-test-results.json"
pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths benchmark/macrobenchmark \
  -OutputPath "$SUMMARY_PATH"
export MUXTV_BENCHMARK_SUMMARY="$SUMMARY_PATH"
pwsh -NoProfile -Command '$summary = Get-Content -LiteralPath $env:MUXTV_BENCHMARK_SUMMARY -Raw | ConvertFrom-Json; $executed = [int]$summary.tests - [int]$summary.skipped; if ($executed -ne 2) { throw "Expected exactly two non-skipped Baseline Profile comparison tests, got $executed." }'

echo "Hosted Baseline Profile startup comparison passed with both real Macrobenchmark modes executed."
