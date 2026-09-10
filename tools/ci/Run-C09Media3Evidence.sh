#!/usr/bin/env bash
set -uo pipefail

source_sha="${MUXTV_C09_SOURCE_SHA:-}"
evidence_dir="${MUXTV_C09_EVIDENCE_DIR:-}"

if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MUXTV_C09_SOURCE_SHA must be an exact lowercase 40-hex commit." >&2
  exit 2
fi
if [[ -z "$evidence_dir" ]]; then
  echo "MUXTV_C09_EVIDENCE_DIR is required." >&2
  exit 2
fi

mkdir -p "$evidence_dir"
raw_log="$evidence_dir/logcat-raw.txt"
evidence_tsv="$evidence_dir/raw.tsv"
environment_file="$evidence_dir/environment.txt"

adb logcat -c || true

set +e
./gradlew --no-daemon --stacktrace \
  :player:media3:connectedDebugAndroidTest \
  -Pc09Media3Evidence=true \
  -Pc09SourceSha="$source_sha"
gradle_status=$?
set -e

adb logcat -d -v raw -s C09Media3Evidence:I '*:S' > "$raw_log" || true
grep '^C09_' "$raw_log" > "$evidence_tsv" || true

{
  echo "source_sha=$source_sha"
  echo "expected_avd=${MUXTV_EXPECTED_AVD:-unknown}"
  echo "android_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "android_release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "brand=$(adb shell getprop ro.product.brand | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "product=$(adb shell getprop ro.product.name | tr -d '\r')"
  echo "hardware=$(adb shell getprop ro.hardware | tr -d '\r')"
  echo "build_fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$environment_file"

if [[ $gradle_status -eq 0 ]]; then
  env_count=$(grep -c '^C09_ENV' "$evidence_tsv" || true)
  correctness_count=$(grep -c $'^C09_SAMPLE\t.*phase=CORRECTNESS' "$evidence_tsv" || true)
  warmup_count=$(grep -c $'^C09_SAMPLE\t.*phase=WARMUP' "$evidence_tsv" || true)
  measured_count=$(grep -c $'^C09_SAMPLE\t.*phase=MEASURED' "$evidence_tsv" || true)
  report_count=$(grep -c '^C09_REPORT' "$evidence_tsv" || true)

  if [[ "$env_count" -ne 1 || "$correctness_count" -ne 18 || "$warmup_count" -ne 36 || "$measured_count" -ne 360 || "$report_count" -ne 18 ]]; then
    echo "C09 evidence cardinality mismatch: env=$env_count correctness=$correctness_count warmup=$warmup_count measured=$measured_count reports=$report_count" >&2
    gradle_status=1
  fi
fi

if [[ ! -s "$evidence_tsv" ]]; then
  echo "C09 evidence log is empty." >&2
  gradle_status=1
fi

exit "$gradle_status"
