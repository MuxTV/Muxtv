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

# Repeated, bounded Gate E characterization (1 warmup + 3 measured rounds).
# This records noise and medians; it is NOT a before/after causal A/B benchmark,
# because the B schema is benchmark-only and the variants are not interleaved.
warmups=1
iterations=3
./gradlew :core:database:connectedDebugAndroidTest \
  --no-daemon --stacktrace --console=plain --no-problems-report \
  -PcatalogMeasurements=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.RefreshDeltaDatabaseMeasurementTest \
  -Pandroid.testInstrumentationRunnerArguments.c03SourceCommit="$MUXTV_SOURCE_COMMIT" \
  -Pandroid.testInstrumentationRunnerArguments.c03Warmups="$warmups" \
  -Pandroid.testInstrumentationRunnerArguments.c03Iterations="$iterations"

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
require(data.get("batchSize") == 250, "fixed batch size missing")
require(isinstance(data.get("fixtureManifestSha256"), str) and len(data["fixtureManifestSha256"]) == 64,
        "fixture provenance missing")
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
        require(var.get("previousGoodCount") == data["entryCount"],
                "previous-good retention changed")
        samples = var.get("samples", [])
        require(len(samples) == 3, "expected exactly three measured rounds")
        require([s.get("iteration") for s in samples] == [1, 2, 3],
                "measured round ordering/identity mismatch")
        for sample in samples:
            for field in ("refreshWallNanos", "stageTotalNanos", "activationTransactionNanos"):
                require(isinstance(sample.get(field), int) and sample[field] > 0,
                        "missing phase timing")
            for field in ("before", "afterStage", "afterActivation", "afterCheckpoint"):
                state = sample.get(field, {})
                require(all(isinstance(state.get(k), int) and state[k] >= 0
                            for k in ("databaseBytes", "walBytes", "shmBytes")),
                        "missing physical file-state evidence")
            require(isinstance(sample.get("walPeakBytes"), int) and sample["walPeakBytes"] >= 0,
                    "WAL accounting absent")
    production = variants["A_CURRENT_MUXTV"]
    mutations = production.get("rowMutationBreakdown", {})
    require(mutations.get("source_revision_memberships.insert") == expected,
            "v11 membership writes omitted or incorrect")
    # All seven fixed scenarios retain the same canonical display metadata.
    # The optimized publication path must skip unchanged SQL UPDATEs.
    require(mutations.get("canonical_channels.update", 0) == 0,
            "unchanged canonical metadata still incurs physical SQL UPDATEs")
    require(production.get("actualRowMutations") == sum(mutations.values()),
            "physical SQL mutation sum mismatch")
    require(not any(k.startswith("c03_b_") for k in mutations),
            "candidate mutations mixed into real production A")
# Immutable summary: only actual Room A; do not report synthetic B as
# equivalent production and do not compare across SHA/device runs as a causal gain.
import statistics

def five_point(values):
    require(len(values) == 3 and all(isinstance(x, int) and x >= 0 for x in values),
            "invalid repeated sample")
    return {
        "sampleCount": len(values),
        "minimum": min(values),
        "median": int(statistics.median(values)),
        "maximum": max(values),
    }

summary = {
    "schemaVersion": 1,
    "protocol": "c03-prod-a-real-room-v11-repeat-v1",
    "sourceCommit": sha,
    "fixtureManifestSha256": data["fixtureManifestSha256"],
    "methodVersion": data["methodVersion"],
    "environment": data["environment"],
    "entryCount": data["entryCount"],
    "warmupIterations": 1,
    "measuredIterations": 3,
    "thresholdApplied": False,
    "redactionPassed": True,
    "limitations": [
        "Three measured rounds provide a noise range, not a significance test.",
        "The earlier one-shot run from a different SHA is not a paired control.",
        "A_CURRENT_MUXTV and B_IMMUTABLE_COW differ structurally and are not comparable production alternatives.",
        "The variants run in separate blocks rather than seeded interleaved order.",
        "WAL file length does not identify which SQLite index or FTS pages were written.",
    ],
    "scenarios": [],
}
for sc in scenarios:
    production = next(v for v in sc["variants"] if v["variant"] == "A_CURRENT_MUXTV")
    samples = production["samples"]
    summary["scenarios"].append({
        "scenarioId": sc["scenarioId"],
        "expectedActiveCount": sc["expectedActiveCount"],
        "actualRowMutations": production["actualRowMutations"],
        "canonicalUpdateCount": production["rowMutationBreakdown"].get("canonical_channels.update", 0),
        "membershipInsertCount": production["rowMutationBreakdown"]["source_revision_memberships.insert"],
        "refreshWallNanos": five_point([s["refreshWallNanos"] for s in samples]),
        "stageTotalNanos": five_point([s["stageTotalNanos"] for s in samples]),
        "activationTransactionNanos": five_point([s["activationTransactionNanos"] for s in samples]),
        "walPeakBytes": five_point([s["walPeakBytes"] for s in samples]),
        "checkpointDatabaseGrowthBytes": five_point(
            [s["checkpointDatabaseGrowthBytes"] for s in samples]
        ),
    })
summary_path = report_path.with_name("c03-prod-a-v11-series-summary.json")
summary_path.write_text(
    json.dumps(summary, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
    encoding="utf-8",
)
print("C03 PROD-A v11 repeat evidence: 7 scenarios, 1 warmup, 3 rounds, exact SHA, audited v11 writes; PASS.")
PY
