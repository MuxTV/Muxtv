#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_DEVICE_EVIDENCE:?MUXTV_DEVICE_EVIDENCE is required}"

readonly CURRENT_API36_AVD="MuxTV_TV_CURRENT_API36"
readonly APP_PACKAGE="app.muxtv.tv.debug"
readonly TEST_PACKAGE="app.muxtv.tv.debug.test"
readonly TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly INSTRUMENTATION_COMPONENT="${TEST_PACKAGE}/${TEST_RUNNER}"
readonly JOURNEY_TEST_CLASS="app.muxtv.RailNavigationJourneyTest"
readonly HOME_PROBE_TEST_CLASS="app.muxtv.U1HomeGeometryProbeTest"
readonly REMOTE_HOME_EVIDENCE="/sdcard/Android/data/${APP_PACKAGE}/files/u1-home-geometry"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ "$MUXTV_EXPECTED_AVD" == "$CURRENT_API36_AVD" ]] ||
  fail "U1 representative TV profiles are restricted to $CURRENT_API36_AVD."

mkdir -p "$MUXTV_DEVICE_EVIDENCE/profiles"

mapfile -t avds < <(avdmanager list avd -c | sed '/^[[:space:]]*$/d')
(( ${#avds[@]} == 1 )) || fail "Expected exactly one AVD, found ${#avds[@]}: ${avds[*]-<none>}"
[[ "${avds[0]}" == "$CURRENT_API36_AVD" ]] || fail "Expected canonical AVD $CURRENT_API36_AVD, found ${avds[0]}."

mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
(( ${#devices[@]} == 1 )) || fail "Expected exactly one ready Android device, found ${#devices[@]}: ${devices[*]-<none>}"
export ANDROID_SERIAL="${devices[0]}"
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == "36" ]] || fail "U1 representative TV profiles require API36."
readonly SOURCE_COMMIT="$(git rev-parse HEAD)"

reset_display() {
  adb -s "$ANDROID_SERIAL" shell wm size reset >/dev/null 2>&1 || true
  adb -s "$ANDROID_SERIAL" shell wm density reset >/dev/null 2>&1 || true
}
trap reset_display EXIT

effective_size() {
  local output="$1" override physical
  override="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Override size:[[:space:]]*//p' | tail -n 1)"
  physical="$(printf '%s\n' "$output" | sed -n 's/^[[:space:]]*Physical size:[[:space:]]*//p' | tail -n 1)"
  printf '%s\n' "${override:-$physical}"
}

effective_density() {
  local output="$1" override physical
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
  reset_display
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

clear_test_state() {
  adb -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" | tr -d '\r' | grep -qi '^success$' || fail "Unable to clear $APP_PACKAGE"
  adb -s "$ANDROID_SERIAL" shell pm clear "$TEST_PACKAGE" | tr -d '\r' | grep -qi '^success$' || fail "Unable to clear $TEST_PACKAGE"
  adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE_HOME_EVIDENCE" >/dev/null 2>&1 || true
}

instrumentation_passed() {
  local status="$1" log_path="$2"
  (( status == 0 )) || return 1
  grep -Eq 'OK \([0-9]+ tests?\)' "$log_path" || return 1
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$log_path"; then
    return 1
  fi
  return 0
}

validate_home_evidence() {
  local json_path="$1" profile_id="$2" width="$3" height="$4" density="$5"
  python3 - "$json_path" "$SOURCE_COMMIT" "$profile_id" "$width" "$height" "$density" <<'PY'
import json
import sys

path, source_commit, profile_id, width, height, density = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    data = json.load(handle)

expected = {
    "schemaVersion": 2,
    "sourceCommit": source_commit,
    "displayProfile": profile_id,
    "displayWidthPx": int(width),
    "displayHeightPx": int(height),
    "displayDensityDpi": int(density),
    "destination": "home",
    "anchor": "tag:home-add-source",
    "contentOriginStableDuringRail": True,
    "contentOriginStableDuringBackRail": True,
    "contentOriginRestoredAfterBack": True,
    "contentOriginRestoredAfterRight": True,
    "backReachedExpectedRailItem": True,
    "rightReachedExpectedRailItem": True,
}
for key, value in expected.items():
    if data.get(key) != value:
        raise SystemExit(f"Home geometry evidence mismatch for {key}: expected {value!r}, got {data.get(key)!r}")
for key in ("beforeBounds", "duringBackRailBounds", "duringRightRailBounds", "afterBackBounds", "afterRightBounds", "railBounds"):
    bounds = data.get(key)
    if not isinstance(bounds, dict) or not all(field in bounds for field in ("left", "top", "right", "bottom", "width", "height")):
        raise SystemExit(f"Home geometry evidence is missing complete bounds: {key}")
PY
}

run_targeted_profile() {
  local profile_id="$1" size="$2" density="$3" required="$4"
  local width="${size%x*}" height="${size#*x}"
  local profile_dir="$MUXTV_DEVICE_EVIDENCE/profiles/$profile_id"
  local journey_log="$profile_dir/journey-instrumentation.log"
  local home_log="$profile_dir/home-instrumentation.log"
  local result_path="$profile_dir/profile-result.json"
  mkdir -p "$profile_dir/home-geometry"

  set_display "$size" "$density" "$profile_dir/display-state.txt"
  echo "=== U1 TV profile $profile_id: ${size}@${density}dpi required=${required} ==="

  clear_test_state
  set +e
  adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class "$JOURNEY_TEST_CLASS" \
    "$INSTRUMENTATION_COMPONENT" \
    2>&1 | tee "$journey_log"
  local journey_status=${PIPESTATUS[0]}
  set -e
  local journey_passed=true
  instrumentation_passed "$journey_status" "$journey_log" || journey_passed=false

  clear_test_state
  set +e
  adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class "$HOME_PROBE_TEST_CLASS" \
    -e sourceCommit "$SOURCE_COMMIT" \
    -e displayProfile "$profile_id" \
    -e displayWidthPx "$width" \
    -e displayHeightPx "$height" \
    -e displayDensityDpi "$density" \
    "$INSTRUMENTATION_COMPONENT" \
    2>&1 | tee "$home_log"
  local home_status=${PIPESTATUS[0]}
  set -e
  local home_passed=true
  instrumentation_passed "$home_status" "$home_log" || home_passed=false

  local home_json="$profile_dir/home-geometry/probe-result.json"
  if ! adb -s "$ANDROID_SERIAL" shell test -f "$REMOTE_HOME_EVIDENCE/probe-result.json"; then
    home_passed=false
    echo "Missing remote post-U1 Home geometry evidence for $profile_id" >&2
  else
    set +e
    adb -s "$ANDROID_SERIAL" pull "$REMOTE_HOME_EVIDENCE/probe-result.json" "$home_json" >/dev/null
    local pull_status=$?
    set -e
    if (( pull_status != 0 )); then
      home_passed=false
    elif ! validate_home_evidence "$home_json" "$profile_id" "$width" "$height" "$density"; then
      home_passed=false
    fi
  fi

  local passed=true
  if [[ "$journey_passed" != "true" || "$home_passed" != "true" ]]; then
    passed=false
  fi

  cat >"$result_path" <<JSON
{
  "schemaVersion": 2,
  "profile": "${profile_id}",
  "size": "${size}",
  "densityDpi": ${density},
  "avdName": "${CURRENT_API36_AVD}",
  "sourceCommit": "${SOURCE_COMMIT}",
  "journeyTestClass": "${JOURNEY_TEST_CLASS}",
  "homeProbeTestClass": "${HOME_PROBE_TEST_CLASS}",
  "required": ${required},
  "journeyInstrumentationStatus": ${journey_status},
  "homeInstrumentationStatus": ${home_status},
  "journeyPassed": ${journey_passed},
  "homeGeometryPassed": ${home_passed},
  "passed": ${passed}
}
JSON

  if [[ "$passed" != "true" ]]; then
    if [[ "$required" == "true" ]]; then
      fail "Required U1 TV profile failed: ${profile_id} (${size}@${density}dpi)."
    fi
    echo "::warning::Compact stress profile failed without invalidating the representative U1 product gate: ${profile_id}."
  fi
}

app_apk="$(find_single_apk 'app/tv/build/outputs/apk/debug' 'target debug')"
test_apk="$(find_single_apk 'app/tv/build/outputs/apk/androidTest/debug' 'debug androidTest')"
adb -s "$ANDROID_SERIAL" install -r -t "$app_apk" >/dev/null
adb -s "$ANDROID_SERIAL" install -r -t "$test_apk" >/dev/null

instrumentation_listing="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation | tr -d '\r')"
printf '%s\n' "$instrumentation_listing" > "$MUXTV_DEVICE_EVIDENCE/instrumentation-list.txt"
grep -Fq "instrumentation:${INSTRUMENTATION_COMPONENT} (target=${APP_PACKAGE})" "$MUXTV_DEVICE_EVIDENCE/instrumentation-list.txt" ||
  fail "Expected instrumentation component ${INSTRUMENTATION_COMPONENT} targeting ${APP_PACKAGE} is unavailable."

run_targeted_profile "1080p-tv" "1920x1080" "320" "true"
run_targeted_profile "720p-tv" "1280x720" "213" "true"
run_targeted_profile "compact-stress" "1280x720" "320" "false"

reset_display
record_display_state "$MUXTV_DEVICE_EVIDENCE/display-state-reset.txt" "1920x1080" "320"
trap - EXIT

echo "U1 shell profiles and post-shell Home geometry evidence passed on $CURRENT_API36_AVD."
