#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_DEVICE_EVIDENCE:?MUXTV_DEVICE_EVIDENCE is required}"

readonly CURRENT_API36_AVD="MuxTV_TV_CURRENT_API36"
readonly APP_PACKAGE="app.muxtv.tv.debug"
readonly TEST_PACKAGE="app.muxtv.tv.debug.test"
readonly TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly INSTRUMENTATION_COMPONENT="${TEST_PACKAGE}/${TEST_RUNNER}"
readonly TARGETED_TEST_CLASS="app.muxtv.RailNavigationJourneyTest"
readonly PROFILE_GATE="${MUXTV_REPRESENTATIVE_TV_PROFILE_GATE:-false}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

mkdir -p "$MUXTV_DEVICE_EVIDENCE"
INVENTORY_PATH="$MUXTV_DEVICE_EVIDENCE/avd-inventory.txt"
avdmanager list avd -c | sed '/^[[:space:]]*$/d' | tee "$INVENTORY_PATH"
mapfile -t avds < "$INVENTORY_PATH"
if (( ${#avds[@]} != 1 )); then
  fail "Expected exactly one AVD, found ${#avds[@]}: ${avds[*]-<none>}"
fi
if [[ "${avds[0]}" != "$MUXTV_EXPECTED_AVD" ]]; then
  fail "Expected canonical AVD $MUXTV_EXPECTED_AVD, found ${avds[0]}."
fi

profile_gate_active=false
ANDROID_SERIAL="${ANDROID_SERIAL:-}"

reset_display() {
  if [[ -n "$ANDROID_SERIAL" ]]; then
    adb -s "$ANDROID_SERIAL" shell wm size reset >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" shell wm density reset >/dev/null 2>&1 || true
  fi
}

cleanup() {
  if [[ "$profile_gate_active" == "true" ]]; then
    reset_display
  fi
}
trap cleanup EXIT

effective_size() {
  local output="$1"
  local override physical
  override="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Override size:[[:space:]]*//p' | tail -n 1)"
  physical="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Physical size:[[:space:]]*//p' | tail -n 1)"
  printf '%s\n' "${override:-$physical}"
}

effective_density() {
  local output="$1"
  local override physical
  override="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Override density:[[:space:]]*//p' | tail -n 1)"
  physical="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Physical density:[[:space:]]*//p' | tail -n 1)"
  printf '%s\n' "${override:-$physical}"
}

record_display_state() {
  local evidence_path="$1" expected_size="$2" expected_density="$3"
  local size_output density_output actual_size actual_density
  size_output="$(adb -s "$ANDROID_SERIAL" shell wm size | tr -d '\r')"
  density_output="$(adb -s "$ANDROID_SERIAL" shell wm density | tr -d '\r')"
  actual_size="$(effective_size "$size_output")"
  actual_density="$(effective_density "$density_output")"
  {
    printf '%s\n' "$size_output"
    printf '%s\n' "$density_output"
    printf 'effectiveSize=%s\n' "$actual_size"
    printf 'effectiveDensityDpi=%s\n' "$actual_density"
  } | tee "$evidence_path"
  [[ "$actual_size" == "$expected_size" ]] || fail "Expected display size $expected_size, observed $actual_size."
  [[ "$actual_density" == "$expected_density" ]] || fail "Expected display density $expected_density, observed $actual_density."
}

set_display() {
  local size="$1" density="$2" evidence_path="$3"
  adb -s "$ANDROID_SERIAL" shell wm size reset >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm density reset >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm size "$size" >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm density "$density" >/dev/null

  local attempt size_output density_output actual_size actual_density
  for attempt in $(seq 1 40); do
    size_output="$(adb -s "$ANDROID_SERIAL" shell wm size | tr -d '\r')"
    density_output="$(adb -s "$ANDROID_SERIAL" shell wm density | tr -d '\r')"
    actual_size="$(effective_size "$size_output")"
    actual_density="$(effective_density "$density_output")"
    if [[ "$actual_size" == "$size" && "$actual_density" == "$density" ]]; then
      break
    fi
    sleep 0.25
  done
  record_display_state "$evidence_path" "$size" "$density"
}

find_single_apk() {
  local root="$1" description="$2"
  local -a apks=()
  mapfile -t apks < <(find "$root" -maxdepth 1 -type f -name '*.apk' | sort)
  (( ${#apks[@]} == 1 )) || fail "Expected exactly one $description APK under $root, found ${#apks[@]}: ${apks[*]-<none>}"
  printf '%s\n' "${apks[0]}"
}

run_targeted_profile() {
  local profile_id="$1" size="$2" density="$3" required="$4"
  local profile_dir="$MUXTV_DEVICE_EVIDENCE/profiles/$profile_id"
  local log_path="$profile_dir/instrumentation.log"
  local result_path="$profile_dir/profile-result.json"
  mkdir -p "$profile_dir"

  set_display "$size" "$density" "$profile_dir/display-state.txt"
  echo "=== Representative TV profile $profile_id: ${size}@${density}dpi required=${required} ==="

  set +e
  adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class "$TARGETED_TEST_CLASS" \
    "$INSTRUMENTATION_COMPONENT" \
    2>&1 | tee "$log_path"
  local instrumentation_status=${PIPESTATUS[0]}
  set -e

  local passed=true
  if (( instrumentation_status != 0 )); then
    passed=false
  fi
  if ! grep -Eq 'OK \([0-9]+ tests?\)' "$log_path"; then
    passed=false
  fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$log_path"; then
    passed=false
  fi

  cat >"$result_path" <<JSON
{
  "schemaVersion": 1,
  "profile": "${profile_id}",
  "size": "${size}",
  "densityDpi": ${density},
  "avdName": "${MUXTV_EXPECTED_AVD}",
  "testClass": "${TARGETED_TEST_CLASS}",
  "required": ${required},
  "passed": ${passed}
}
JSON

  if [[ "$passed" != "true" ]]; then
    if [[ "$required" == "true" ]]; then
      fail "Required representative TV profile failed: ${profile_id} (${size}@${density}dpi)."
    fi
    echo "::warning::Compact stress profile failed without invalidating the representative TV product gate: ${profile_id}."
  fi
}

if [[ "$PROFILE_GATE" == "true" ]]; then
  [[ "$MUXTV_EXPECTED_AVD" == "$CURRENT_API36_AVD" ]] || fail "Representative TV profile gate is restricted to $CURRENT_API36_AVD."
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  (( ${#devices[@]} == 1 )) || fail "Expected exactly one ready Android device for representative TV profiles, found ${#devices[@]}: ${devices[*]-<none>}"
  export ANDROID_SERIAL="${devices[0]}"
  [[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == "36" ]] || fail "Representative TV profile gate requires API36."
  profile_gate_active=true
  record_display_state "$MUXTV_DEVICE_EVIDENCE/display-state-1080p-tv.txt" "1920x1080" "320"
fi

chmod +x ./gradlew
./gradlew \
  :catalog:importer:connectedDebugAndroidTest \
  :catalog:refresh:connectedDebugAndroidTest \
  :core:credentials:connectedDebugAndroidTest \
  :core:database:connectedDebugAndroidTest \
  :player:media3:connectedDebugAndroidTest \
  :app:tv:connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report

modules=(
  catalog/importer
  catalog/refresh
  core/credentials
  core/database
  player/media3
  app/tv
)
for module in "${modules[@]}"; do
  slug="${module//\//-}"
  pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
    -ModulePaths "$module" \
    -OutputPath "$MUXTV_DEVICE_EVIDENCE/android-test-results-$slug.json"
done

if [[ "$PROFILE_GATE" == "true" ]]; then
  app_apk="$(find_single_apk 'app/tv/build/outputs/apk/debug' 'target debug')"
  test_apk="$(find_single_apk 'app/tv/build/outputs/apk/androidTest/debug' 'debug androidTest')"
  adb -s "$ANDROID_SERIAL" install -r -t "$app_apk" >/dev/null
  adb -s "$ANDROID_SERIAL" install -r -t "$test_apk" >/dev/null

  instrumentation_listing="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation | tr -d '\r')"
  printf '%s\n' "$instrumentation_listing" > "$MUXTV_DEVICE_EVIDENCE/instrumentation-list.txt"
  grep -Fq "instrumentation:${INSTRUMENTATION_COMPONENT} (target=${APP_PACKAGE})" "$MUXTV_DEVICE_EVIDENCE/instrumentation-list.txt" || \
    fail "Expected instrumentation component ${INSTRUMENTATION_COMPONENT} targeting ${APP_PACKAGE} is unavailable."

  run_targeted_profile "720p-tv" "1280x720" "213" "true"
  run_targeted_profile "compact-stress" "1280x720" "320" "false"

  reset_display
  record_display_state "$MUXTV_DEVICE_EVIDENCE/display-state-reset.txt" "1920x1080" "320"
  profile_gate_active=false
fi

echo "Hosted Android TV product tests passed on $MUXTV_EXPECTED_AVD."
