#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_SOURCE_COMMIT:?MUXTV_SOURCE_COMMIT is required}"
: "${MUXTV_MEASUREMENT_EVIDENCE:?MUXTV_MEASUREMENT_EVIDENCE is required}"

if [[ "$MUXTV_EXPECTED_AVD" != "MuxTV_TV_CURRENT_API36" ]]; then
  echo "M0 catalog correctness is defined only on canonical API36, got $MUXTV_EXPECTED_AVD." >&2
  exit 1
fi
if [[ ! "$MUXTV_SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MUXTV_SOURCE_COMMIT must be an exact 40-character lowercase SHA." >&2
  exit 1
fi

mkdir -p "$MUXTV_MEASUREMENT_EVIDENCE"
INVENTORY_PATH="$MUXTV_MEASUREMENT_EVIDENCE/avd-inventory.txt"
avdmanager list avd -c | sed '/^[[:space:]]*$/d' | tee "$INVENTORY_PATH"
mapfile -t avds < "$INVENTORY_PATH"
if (( ${#avds[@]} != 1 )); then
  echo "Expected exactly one hosted AVD, found ${#avds[@]}: ${avds[*]-<none>}" >&2
  exit 1
fi
if [[ "${avds[0]}" != "$MUXTV_EXPECTED_AVD" ]]; then
  echo "Expected canonical AVD $MUXTV_EXPECTED_AVD, found ${avds[0]}." >&2
  exit 1
fi

chmod +x ./gradlew
rm -rf core/database/build/outputs/androidTest-results/connected/debug

./gradlew \
  :core:database:connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.CatalogDatabaseMeasurementCorrectnessTest

pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths core/database \
  -OutputPath "$MUXTV_MEASUREMENT_EVIDENCE/android-test-results-core-database.json"

cat > "$MUXTV_MEASUREMENT_EVIDENCE/m0-catalog-correctness-summary.txt" <<EOF
status=passed
sourceCommit=$MUXTV_SOURCE_COMMIT
avd=$MUXTV_EXPECTED_AVD
api=36
mode=correctness
entryCount=10000
measuredIterations=1
selectiveBoundary=published-result-set
broadBoundary=published-result-set
thresholdApplied=false
claimEligible=false
EOF

echo "Hosted M0 catalog correctness passed on $MUXTV_EXPECTED_AVD for 10k selective+broad published-result boundary coverage; thresholdApplied=false; claimEligible=false."
