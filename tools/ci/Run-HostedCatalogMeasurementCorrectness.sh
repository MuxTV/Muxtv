#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_SOURCE_COMMIT:?MUXTV_SOURCE_COMMIT is required}"
: "${MUXTV_MEASUREMENT_EVIDENCE:?MUXTV_MEASUREMENT_EVIDENCE is required}"

if [[ "$MUXTV_EXPECTED_AVD" != "MuxTV_TV_CURRENT_API36" ]]; then
  echo "M0 catalog measurement correctness is defined only on canonical API36, got $MUXTV_EXPECTED_AVD." >&2
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
  -PcatalogMeasurements=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.CatalogDatabaseMeasurementTest \
  -Pandroid.testInstrumentationRunnerArguments.measurementSourceCommit="$MUXTV_SOURCE_COMMIT" \
  -Pandroid.testInstrumentationRunnerArguments.measurementRunnerLabel=github-hosted-linux-api36-m0-v2 \
  -Pandroid.testInstrumentationRunnerArguments.measurementWarmups=0 \
  -Pandroid.testInstrumentationRunnerArguments.measurementIterations=5 \
  -Pandroid.testInstrumentationRunnerArguments.measurementEntryCount=50000 \
  -Pandroid.testInstrumentationRunnerArguments.measurementOutputName=catalog-database-measurement-m0.json

pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths core/database \
  -OutputPath "$MUXTV_MEASUREMENT_EVIDENCE/android-test-results-core-database.json"

RESULT_ROOT="core/database/build/outputs/androidTest-results/connected/debug"
mapfile -t result_logs < <(find "$RESULT_ROOT" -type f -name test-results.log -print | sort)
if (( ${#result_logs[@]} != 1 )); then
  echo "Expected exactly one catalog measurement instrumentation result log, got ${#result_logs[@]}." >&2
  exit 1
fi

python3 - "${result_logs[0]}" "$MUXTV_MEASUREMENT_EVIDENCE/catalog-database-measurement-m0.json" "$MUXTV_SOURCE_COMMIT" <<'PY'
import base64
import json
import pathlib
import re
import sys

log_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
expected_sha = sys.argv[3]
text = log_path.read_text(encoding="utf-8", errors="replace")
match = re.search(
    r"^INSTRUMENTATION_RESULT: catalogDatabaseMeasurementReportBase64=(?P<value>[A-Za-z0-9+/=]+)\s*$",
    text,
    flags=re.MULTILINE,
)
if match is None:
    raise SystemExit("Catalog measurement instrumentation did not publish catalogDatabaseMeasurementReportBase64.")
try:
    payload = base64.b64decode(match.group("value"), validate=True)
    report = json.loads(payload.decode("utf-8"))
except Exception as exc:
    raise SystemExit(f"Catalog measurement report could not be decoded: {exc}") from exc

if report.get("schemaVersion") != 1 or report.get("methodVersion") != 4:
    raise SystemExit("Unsupported catalog measurement report schema/method version.")
if report.get("buildMode") != "debug-instrumentation":
    raise SystemExit("Catalog measurement did not run through debug instrumentation.")
if report.get("thresholdApplied") is not False:
    raise SystemExit("M0 correctness evidence must remain thresholdApplied=false.")
if report.get("sourceCommit") != expected_sha:
    raise SystemExit("Catalog measurement report sourceCommit does not match exact PR head.")
if report.get("runnerLabel") != "github-hosted-linux-api36-m0-v2":
    raise SystemExit("Catalog measurement report runnerLabel does not match M0 v4 methodology.")
if report.get("failureCount") != 0:
    raise SystemExit("Catalog measurement report contains failed samples.")
workload = report.get("workload") or {}
if workload.get("entryCount") != 50000 or workload.get("measuredIterations") != 5:
    raise SystemExit("Catalog measurement workload is not the bounded M0 50k/5-iteration contract.")
if workload.get("warmupIterations") != 0:
    raise SystemExit("Hosted M0 correctness evidence must use zero warmup iterations.")
operations = report.get("operations") or []
if len(operations) != 23:
    raise SystemExit(f"Catalog measurement operation count is invalid: {len(operations)}")
if not all(len(operation.get("rawSamples") or []) == 5 for operation in operations):
    raise SystemExit("Catalog measurement report does not contain five samples per operation.")

report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Catalog database M0 correctness report accepted.")
print(f"sourceCommit={expected_sha}")
print("methodVersion=4")
print("thresholdApplied=false")
print(f"operations={len(operations)}")
PY

cat > "$MUXTV_MEASUREMENT_EVIDENCE/m0-catalog-measurement-summary.txt" <<EOF
status=passed
sourceCommit=$MUXTV_SOURCE_COMMIT
avd=$MUXTV_EXPECTED_AVD
api=36
runnerLabel=github-hosted-linux-api36-m0-v2
methodVersion=4
entryCount=50000
warmups=0
iterations=5
thresholdApplied=false
claimEligible=false
EOF

# This lane proves M0 methodology/correctness only. Hosted emulator timing is not performance evidence.
echo "Hosted M0 catalog measurement correctness passed on $MUXTV_EXPECTED_AVD; methodVersion=4; thresholdApplied=false; claimEligible=false."
