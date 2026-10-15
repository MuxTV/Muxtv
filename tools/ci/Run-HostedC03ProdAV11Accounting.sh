#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_SOURCE_COMMIT:?Exact source SHA required}"
: "${MUXTV_EVIDENCE_DIR:?Evidence directory required}"
if [[ ! "$MUXTV_SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Expected an exact 40-character source SHA." >&2
  exit 1
fi
if [[ "$(git rev-parse HEAD)" != "$MUXTV_SOURCE_COMMIT" ]]; then
  echo "C03 PROD-A runner source SHA mismatch." >&2
  exit 1
fi

mkdir -p "$MUXTV_EVIDENCE_DIR"
rm -rf core/database/build/outputs/androidTest-results/connected/debug
chmod +x ./gradlew

# Threshold-free, bounded pilot: one measured round / no warmups, seven fixed 10k
# scenarios. Full statistical performance qualification needs more rounds.
./gradlew :core:database:connectedDebugAndroidTest \
  --no-daemon --stacktrace --console=plain --no-problems-report \
  -PcatalogMeasurements=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.RefreshDeltaDatabaseMeasurementTest \
  -Pandroid.testInstrumentationRunnerArguments.c03SourceCommit="$MUXTV_SOURCE_COMMIT" \
  -Pandroid.testInstrumentationRunnerArguments.c03Warmups=0 \
  -Pandroid.testInstrumentationRunnerArguments.c03Iterations=1

ROOT="core/database/build/outputs/androidTest-results/connected/debug"
mapfile -t logs < <(find "$ROOT" -type f -name test-results.log | sort)
if (( ${#logs[@]} != 1 )); then
  echo "Expected exactly one instrumentation log, found ${#logs[@]}." >&2
  exit 1
fi

prefix="INSTRUMENTATION_RESULT: c03RefreshDeltaDatabaseMeasurementReportBase64="
encoded="$(grep -F "$prefix" "${logs[0]}" | tail -n 1 | sed "s/^.*$prefix//")"
if [[ -z "$encoded" ]]; then
  echo "Missing C03 PROD-A measurement report from instrumentation." >&2
  exit 1
fi
report="$MUXTV_EVIDENCE_DIR/c03-prod-a-v11-accounting.json"
printf '%s' "$encoded" | base64 --decode > "$report"

python3 - "$report" "$MUXTV_SOURCE_COMMIT" <<'PY'
import json
import pathlib
import sys

report_path = pathlib.Path(sys.argv[1])
sha = sys.argv[2]
data = json.loads(report_path.read_text(encoding="utf-8"))

def require(condition, message):
    if not condition:
        raise SystemExit("C03 PROD-A v11 evidence: " + message)

require(data.get("schemaVersion") == 1, "unexpected report schema")
require(data.get("methodVersion") == "c03-room-delta-v2-v11-physical-audit", "wrong method")
require(data.get("sourceCommit") == sha, "source provenance mismatch")
require(data.get("entryCount") == 10000, "fixed corpus missing")
require(data.get("redactionPassed") is True, "redaction gate not passed")
require(data.get("thresholdApplied") is False, "pilot cannot apply performance thresholds")
require(data.get("environment", {}).get("apiLevel") == 36, "API36 evidence required")
required_scenarios = [
    "delta-0", "delta-1", "delta-10", "delta-100",
    "reorder", "remove-10", "token-churn",
]
scenarios = data.get("scenarios", [])
require([s.get("scenarioId") for s in scenarios] == required_scenarios, "scenario coverage incomplete")
for sc in scenarios:
    variants = {v.get("variant"): v for v in sc.get("variants", [])}
    require(set(variants) == {"A_CURRENT_MUXTV", "B_IMMUTABLE_COW"}, "variant mismatch")
    expected = sc.get("expectedActiveCount")
    require(isinstance(expected, int) and expected > 0, "missing expected count")
    for var in variants.values():
        require(var.get("correctnessCount") == expected, "incorrect active count")
        require(var.get("correctnessDigestSha256") == sc.get("expectedActiveDigestSha256"),
                "incorrect active digest")
        require(len(var.get("samples", [])) == 1, "not one measured round")
        sample = var["samples"][0]
        for field in ("refreshWallNanos", "stageTotalNanos", "activationTransactionNanos"):
            require(isinstance(sample.get(field), int) and sample[field] > 0,
                    "missing phase timing")
        for field in ("before", "afterStage", "afterActivation", "afterCheckpoint"):
            state = sample.get(field, {})
            require(all(isinstance(state.get(k), int) and state[k] >= 0
                        for k in ("databaseBytes", "walBytes", "shmBytes")),
                    "missing physical file-state evidence")
        require(isinstance(sample.get("walPeakBytes"), int), "WAL accounting absent")
    production = variants["A_CURRENT_MUXTV"]
    mutations = production.get("rowMutationBreakdown", {})
    require(mutations.get("source_revision_memberships.insert") == expected,
            "v11 membership writes omitted or incorrect")
    require(production.get("actualRowMutations") == sum(mutations.values()),
            "physical SQL mutation sum mismatch")
    require(not any(k.startswith("c03_b_") for k in mutations),
            "candidate mutations mixed into real production A")
# Report contains per-scenario evidence and limitations. Do not print raw channel data.
print("C03 PROD-A v11 file-backed evidence: 7 scenarios, exact SHA, correct v11 audit; PASS.")
PY
