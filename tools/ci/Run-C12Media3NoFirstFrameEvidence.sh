#!/usr/bin/env bash
set -uo pipefail

source_sha="${MUXTV_C12_SOURCE_SHA:-}"
evidence_dir="${MUXTV_C12_EVIDENCE_DIR:-}"

if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MUXTV_C12_SOURCE_SHA must be an exact lowercase 40-hex commit." >&2
  exit 2
fi
if [[ -z "$evidence_dir" ]]; then
  echo "MUXTV_C12_EVIDENCE_DIR is required." >&2
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
  -Pc12Media3NoFirstFrameEvidence=true \
  -Pc12SourceSha="$source_sha"
gradle_status=$?
set -e

adb logcat -d -v raw -s C12NoFrameEvidence:I '*:S' > "$raw_log" || true
grep '^C12_' "$raw_log" > "$evidence_tsv" || true

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
  env_count=$(grep -c '^C12_ENV' "$evidence_tsv" || true)
  sample_count=$(grep -c '^C12_SAMPLE' "$evidence_tsv" || true)
  surface_count=$(grep -c '^C12_SURFACE' "$evidence_tsv" || true)
  no_video_count=$(grep -c '^C12_NOVIDEO' "$evidence_tsv" || true)

  if [[ "$env_count" -ne 1 || "$sample_count" -ne 2 || "$surface_count" -ne 1 || "$no_video_count" -ne 1 ]]; then
    echo "C12 evidence cardinality mismatch: env=$env_count samples=$sample_count surface=$surface_count no_video=$no_video_count" >&2
    gradle_status=1
  fi
fi

if [[ ! -s "$evidence_tsv" ]]; then
  echo "C12 evidence log is empty." >&2
  gradle_status=1
fi

exit "$gradle_status"
