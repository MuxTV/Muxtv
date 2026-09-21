#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_SOURCE_COMMIT:?MUXTV_SOURCE_COMMIT is required}"
: "${MUXTV_EVIDENCE_DIR:?MUXTV_EVIDENCE_DIR is required}"

if [[ "$MUXTV_EXPECTED_AVD" != "MuxTV_TV_CURRENT_API36" ]]; then
  echo "C364 canonical evidence is defined only on API36, got $MUXTV_EXPECTED_AVD." >&2
  exit 1
fi
if [[ ! "$MUXTV_SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MUXTV_SOURCE_COMMIT must be an exact lowercase 40-hex SHA." >&2
  exit 1
fi

mkdir -p "$MUXTV_EVIDENCE_DIR"
chmod +x ./gradlew
rm -rf core/database/build/outputs/androidTest-results/connected/debug

./gradlew \
  :core:database:connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report \
  -PcatalogMeasurements=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.C03ProductionRoomCanonicalEvidenceTest \
  -Pandroid.testInstrumentationRunnerArguments.c03ProductionRoomSourceCommit="$MUXTV_SOURCE_COMMIT" \
  -Pandroid.testInstrumentationRunnerArguments.c03ProductionRoomWarmups=1 \
  -Pandroid.testInstrumentationRunnerArguments.c03ProductionRoomIterations=5 \
  -Pandroid.testInstrumentationRunnerArguments.c03ProductionRoomEntryCount=10000 \
  -Pandroid.testInstrumentationRunnerArguments.c03ProductionRoomOutputName=c03-production-room-canonical.json

RESULT_ROOT="core/database/build/outputs/androidTest-results/connected/debug"
mapfile -t TEST_LOGS < <(find "$RESULT_ROOT" -type f -name test-results.log | sort)
if (( ${#TEST_LOGS[@]} != 1 )); then
  echo "Expected exactly one C364 test-results.log, found ${#TEST_LOGS[@]}." >&2
  exit 1
fi

RESULT_PREFIX="INSTRUMENTATION_RESULT: c03ProductionRoomEvidenceReportBase64="
ENCODED="$(grep -F "$RESULT_PREFIX" "${TEST_LOGS[0]}" | tail -n 1 | sed "s/^.*${RESULT_PREFIX}//")"
if [[ -z "$ENCODED" ]]; then
  echo "C364 instrumentation result payload is missing." >&2
  exit 1
fi

REPORT_PATH="$MUXTV_EVIDENCE_DIR/c03-production-room-canonical.json"
printf '%s' "$ENCODED" | base64 --decode > "$REPORT_PATH"

python3 - "$REPORT_PATH" "$MUXTV_SOURCE_COMMIT" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
expected_sha = sys.argv[2]
raw = path.read_text(encoding="utf-8")
report = json.loads(raw)

def require(condition, message):
    if not condition:
        raise SystemExit(message)

require(report.get("schemaVersion") == 1, "Unsupported C364 schemaVersion.")
require("physical-mutations" in report.get("methodVersion", ""), "C364 methodVersion lost mutation provenance.")
require(report.get("sourceCommit") == expected_sha, "C364 sourceCommit mismatch.")
require(report.get("warmupIterations") == 1, "C364 warmup contract mismatch.")
require(report.get("measuredIterations") == 5, "C364 iteration contract mismatch.")
require(report.get("entryCount") == 10000, "C364 entry-count contract mismatch.")
require(report.get("redactionPassed") is True, "C364 report redaction gate failed.")

expected_scenarios = [
    "delta-0", "delta-1", "delta-10", "delta-100",
    "reorder", "remove-10", "token-churn",
]
scenarios = report.get("scenarios", [])
require([s.get("scenarioId") for s in scenarios] == expected_scenarios, "C364 scenario matrix mismatch.")

for scenario in scenarios:
    variants = scenario.get("variants", [])
    require(
        [v.get("variant") for v in variants] == ["A_CURRENT_PRODUCTION", "B_IMMUTABLE_REUSE"],
        f"C364 variant matrix mismatch for {scenario.get('scenarioId')}.",
    )
    for variant in variants:
        samples = variant.get("samples", [])
        require(len(samples) == 5, "C364 measured sample count mismatch.")
        require(all(int(sample.get("searchNanos", 0)) > 0 for sample in samples), "C364 Search timing is missing.")
        require(
            variant.get("correctnessDigestSha256") == scenario.get("expectedCorrectnessDigestSha256"),
            "C364 correctness digest mismatch.",
        )
        require(
            int(variant.get("correctnessCount", -1)) == int(scenario.get("expectedCorrectnessCount", -2)),
            "C364 correctness count mismatch.",
        )
    candidate = variants[1]
    plans = candidate.get("queryPlans", [])
    require(plans, "C364 candidate query-plan evidence is missing.")
    require(all(plan.get("indexed") is True for plan in plans), "C364 candidate query plan is not indexed.")

repeated = report.get("repeatedRevisionStorage", [])
expected_repeated = [
    ("A_CURRENT_PRODUCTION", 5), ("B_IMMUTABLE_REUSE", 5),
    ("A_CURRENT_PRODUCTION", 10), ("B_IMMUTABLE_REUSE", 10),
    ("A_CURRENT_PRODUCTION", 20), ("B_IMMUTABLE_REUSE", 20),
]
require(
    [(row.get("variant"), row.get("revisionCount")) for row in repeated] == expected_repeated,
    "C364 repeated-revision matrix mismatch.",
)
for row in repeated:
    require(int(row.get("orphanPayloadRows", -1)) == 0, "C364 orphan payload rows remain.")
    require(int(row.get("orphanSearchPayloadRows", -1)) == 0, "C364 orphan search payload rows remain.")

safety = report.get("safety", [])
require(
    [row.get("variant") for row in safety] == ["A_CURRENT_PRODUCTION", "B_IMMUTABLE_REUSE"],
    "C364 safety variant matrix mismatch.",
)
for row in safety:
    require(row.get("previousGoodPreserved") is True, "C364 previous-good safety failed.")
    require(row.get("supersededRejected") is True, "C364 stale-owner safety failed.")
    require(row.get("partialFailurePublished") is False, "C364 partial failure published.")
    require(row.get("cancellationLatePublished") is False, "C364 cancellation late-published.")
    require(row.get("cleanupBounded") is True, "C364 cleanup bound failed.")
    require(int(row.get("cancellationCleanupNanos", 0)) > 0, "C364 cancellation cleanup timing is missing.")

for forbidden in ("https://stream.invalid", "token=", "session="):
    require(forbidden not in raw, f"C364 report leaked forbidden token: {forbidden}")

print("C364 canonical report validation passed.")
PY

cat > "$MUXTV_EVIDENCE_DIR/c03-production-room-canonical-summary.txt" <<EOF
status=passed
sourceCommit=$MUXTV_SOURCE_COMMIT
avd=$MUXTV_EXPECTED_AVD
entryCount=10000
warmupIterations=1
measuredIterations=5
scenarioCount=7
repeatedRevisionCounts=5,10,20
thresholdApplied=false
claimEligible=true
EOF

echo "C364 canonical production Room evidence passed."
