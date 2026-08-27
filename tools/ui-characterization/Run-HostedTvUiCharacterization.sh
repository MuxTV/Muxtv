#!/usr/bin/env bash
set -euo pipefail

readonly BASELINE_A="2302c11441c85b8b5752d7f03cc5bc13be8c6d92"
readonly BASELINE_B="515072022d11b218fcb20f43079f94098b3ea973"
readonly BASELINE_C="7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9"
readonly EXPECTED_AVD="MuxTV_TV_CURRENT_API36"
readonly APP_PACKAGE="app.muxtv.tv.debug"
readonly TEST_PACKAGE="app.muxtv.tv.debug.test"
readonly TEST_RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly INSTRUMENTATION_COMPONENT="${TEST_PACKAGE}/${TEST_RUNNER}"
readonly REMOTE_EVIDENCE="/sdcard/Android/data/${APP_PACKAGE}/files/ui-characterization"
readonly PROBE_SOURCE="tools/ui-characterization/probe/UiCharacterizationProbeTest.kt"
readonly TARGET_PROBE="app/tv/src/androidTest/kotlin/app/muxtv/UiCharacterizationProbeTest.kt"
readonly WORKTREE_ROOT=".work/ui-characterization/worktrees"
readonly EVIDENCE_ROOT="${MUXTV_UI_EVIDENCE:-.work/evidence/ui-characterization}"

readonly -a COMPARISONS=(
  "A:${BASELINE_A}"
  "B:${BASELINE_B}"
  "C:${BASELINE_C}"
)
readonly -a PROFILES=(
  "1080p-tv:representative-1080p:1920x1080:1920:1080:320:true"
  "720p-tv:representative-720p-tv:1280x720:1280:720:213:true"
  "compact-stress:compact-stress:1280x720:1280:720:320:false"
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

reset_display() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" shell wm size reset >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" shell wm density reset >/dev/null 2>&1 || true
  fi
}

cleanup() {
  local status=$?
  reset_display
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    adb -s "$ANDROID_SERIAL" uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
  fi
  for item in "${COMPARISONS[@]}"; do
    local id="${item%%:*}"
    local worktree="${WORKTREE_ROOT}/${id}"
    if [[ -d "$worktree" ]]; then
      git worktree remove --force "$worktree" >/dev/null 2>&1 || true
    fi
  done
  git worktree prune >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

[[ -f "$PROBE_SOURCE" ]] || fail "Common characterization probe is missing: $PROBE_SOURCE"

mapfile -t avds < <(avdmanager list avd -c | sed '/^[[:space:]]*$/d')
(( ${#avds[@]} == 1 )) || fail "Expected exactly one hosted AVD, found ${#avds[@]}: ${avds[*]-<none>}"
[[ "${avds[0]}" == "$EXPECTED_AVD" ]] || fail "Expected canonical AVD $EXPECTED_AVD, found ${avds[0]}"

mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
(( ${#devices[@]} == 1 )) || fail "Expected exactly one ready Android device, found ${#devices[@]}: ${devices[*]-<none>}"
export ANDROID_SERIAL="${devices[0]}"

echo "Hosted U0 device: serial=$ANDROID_SERIAL avd=$EXPECTED_AVD"
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tee "${RUNNER_TEMP:-/tmp}/muxtv-u0-api.txt"
[[ "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" == "36" ]] || fail "Hosted U0 requires API36"

for sha in "$BASELINE_A" "$BASELINE_B" "$BASELINE_C"; do
  git cat-file -e "${sha}^{commit}" || fail "Immutable comparison commit is unavailable: $sha"
done

probe_sha="$(sha256sum "$PROBE_SOURCE" | awk '{print $1}')"
run_id="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-${GITHUB_SHA:-unknown}"
run_root="${EVIDENCE_ROOT}/${run_id}"
mkdir -p "$WORKTREE_ROOT" "$run_root"

write_manifest() {
  local path="$1" source_commit="$2" profile_id="$3" profile_label="$4"
  local width="$5" height="$6" density="$7" representative="$8" status="$9"
  cat >"$path" <<JSON
{
  "schemaVersion": 1,
  "sourceCommit": "${source_commit}",
  "displayProfile": "${profile_id}",
  "displayLabel": "${profile_label}",
  "displayWidthPx": ${width},
  "displayHeightPx": ${height},
  "displayDensityDpi": ${density},
  "representativeTvMode": ${representative},
  "probeSha256": "${probe_sha}",
  "avdName": "${EXPECTED_AVD}",
  "status": "${status}",
  "failure": null
}
JSON
}

set_display() {
  local size="$1" density="$2" evidence_dir="$3"
  adb -s "$ANDROID_SERIAL" shell wm size reset >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm density reset >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm size "$size" >/dev/null
  adb -s "$ANDROID_SERIAL" shell wm density "$density" >/dev/null
  sleep 2
  {
    adb -s "$ANDROID_SERIAL" shell wm size
    adb -s "$ANDROID_SERIAL" shell wm density
  } | tee "$evidence_dir/display-state.txt"
}

package_installed() {
  local package_name="$1"
  adb -s "$ANDROID_SERIAL" shell pm path "$package_name" 2>/dev/null | tr -d '\r' | grep -q '^package:'
}

uninstall_if_installed() {
  local package_name="$1"
  if package_installed "$package_name"; then
    adb -s "$ANDROID_SERIAL" uninstall "$package_name" | grep -q '^Success$' || fail "Unable to uninstall package $package_name"
  fi
}

clear_test_state() {
  package_installed "$APP_PACKAGE" || fail "Target package is not installed before characterization: $APP_PACKAGE"
  package_installed "$TEST_PACKAGE" || fail "Test package is not installed before characterization: $TEST_PACKAGE"
  adb -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" | tr -d '\r' | grep -qi '^success$' || fail "Unable to clear target package $APP_PACKAGE"
  adb -s "$ANDROID_SERIAL" shell pm clear "$TEST_PACKAGE" | tr -d '\r' | grep -qi '^success$' || fail "Unable to clear test package $TEST_PACKAGE"
  adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE_EVIDENCE" >/dev/null 2>&1 || true
}

find_single_apk() {
  local root="$1" description="$2"
  local -a apks=()
  mapfile -t apks < <(find "$root" -maxdepth 1 -type f -name '*.apk' | sort)
  (( ${#apks[@]} == 1 )) || fail "Expected exactly one $description APK under $root, found ${#apks[@]}: ${apks[*]-<none>}"
  printf '%s\n' "${apks[0]}"
}

for item in "${COMPARISONS[@]}"; do
  comparison_id="${item%%:*}"
  source_commit="${item#*:}"
  worktree="${WORKTREE_ROOT}/${comparison_id}"
  comparison_dir="${run_root}/${comparison_id}"

  rm -rf "$worktree"
  mkdir -p "$comparison_dir"
  git worktree add --detach "$worktree" "$source_commit"
  [[ "$(git -C "$worktree" rev-parse HEAD)" == "$source_commit" ]] || fail "Worktree provenance mismatch for $comparison_id"

  mkdir -p "$(dirname "$worktree/$TARGET_PROBE")"
  cp "$PROBE_SOURCE" "$worktree/$TARGET_PROBE"
  [[ "$(sha256sum "$worktree/$TARGET_PROBE" | awk '{print $1}')" == "$probe_sha" ]] || fail "Probe identity mismatch for $comparison_id"
  chmod +x "$worktree/gradlew"

  echo "=== U0 ${comparison_id}: build target and test APK once for all display profiles ==="
  set +e
  (
    cd "$worktree"
    ./gradlew \
      :app:tv:assembleDebug \
      :app:tv:assembleDebugAndroidTest \
      --no-daemon \
      --stacktrace \
      --console=plain \
      --no-problems-report
  ) 2>&1 | tee "$comparison_dir/assemble.log"
  assemble_status=${PIPESTATUS[0]}
  set -e
  (( assemble_status == 0 )) || fail "UI characterization APK build failed for $comparison_id with exit code $assemble_status"

  app_apk="$(find_single_apk "$worktree/app/tv/build/outputs/apk/debug" 'target debug')"
  test_apk="$(find_single_apk "$worktree/app/tv/build/outputs/apk/androidTest/debug" 'debug androidTest')"
  printf 'targetApk=%s\ntestApk=%s\n' "$app_apk" "$test_apk" > "$comparison_dir/apk-paths.txt"

  uninstall_if_installed "$TEST_PACKAGE"
  uninstall_if_installed "$APP_PACKAGE"
  adb -s "$ANDROID_SERIAL" install -r -t "$app_apk" | tee "$comparison_dir/install-target.log"
  adb -s "$ANDROID_SERIAL" install -r -t "$test_apk" | tee "$comparison_dir/install-test.log"
  package_installed "$APP_PACKAGE" || fail "Target package did not remain installed after adb install: $APP_PACKAGE"
  package_installed "$TEST_PACKAGE" || fail "Test package did not remain installed after adb install: $TEST_PACKAGE"

  instrumentation_listing="$(adb -s "$ANDROID_SERIAL" shell pm list instrumentation | tr -d '\r')"
  printf '%s\n' "$instrumentation_listing" > "$comparison_dir/instrumentation-list.txt"
  grep -Fq "instrumentation:${INSTRUMENTATION_COMPONENT} (target=${APP_PACKAGE})" "$comparison_dir/instrumentation-list.txt" || \
    fail "Expected instrumentation component ${INSTRUMENTATION_COMPONENT} targeting ${APP_PACKAGE} is unavailable"

  for profile in "${PROFILES[@]}"; do
    IFS=':' read -r profile_id profile_label size width height density representative <<<"$profile"
    case_dir="${comparison_dir}/${profile_id}"
    mkdir -p "$case_dir"
    manifest="$case_dir/case-manifest.json"
    write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "running"

    set_display "$size" "$density" "$case_dir"
    clear_test_state

    echo "=== U0 ${comparison_id}/${profile_id}: ${source_commit} ${size}@${density} ==="
    set +e
    adb -s "$ANDROID_SERIAL" shell am instrument -w \
      -e class app.muxtv.UiCharacterizationProbeTest \
      -e sourceCommit "$source_commit" \
      -e displayProfile "$profile_id" \
      -e displayWidthPx "$width" \
      -e displayHeightPx "$height" \
      -e displayDensityDpi "$density" \
      "$INSTRUMENTATION_COMPONENT" \
      2>&1 | tee "$case_dir/instrumentation.log"
    instrumentation_status=${PIPESTATUS[0]}
    set -e

    instrumentation_passed=true
    if (( instrumentation_status != 0 )); then
      instrumentation_passed=false
    fi
    if ! grep -Fq 'OK (1 test)' "$case_dir/instrumentation.log"; then
      instrumentation_passed=false
    fi
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed' "$case_dir/instrumentation.log"; then
      instrumentation_passed=false
    fi

    mkdir -p "$case_dir/device"
    remote_evidence_present=false
    if adb -s "$ANDROID_SERIAL" shell test -d "$REMOTE_EVIDENCE"; then
      remote_evidence_present=true
      adb -s "$ANDROID_SERIAL" shell ls -la "$REMOTE_EVIDENCE" 2>&1 | tee "$case_dir/device-evidence-list.txt"
      set +e
      adb -s "$ANDROID_SERIAL" pull "${REMOTE_EVIDENCE}/." "$case_dir/device" 2>&1 | tee "$case_dir/adb-pull.log"
      pull_status=${PIPESTATUS[0]}
      set -e
    else
      pull_status=1
      printf 'Remote evidence directory missing before teardown: %s\n' "$REMOTE_EVIDENCE" | tee "$case_dir/adb-pull.log"
    fi

    if [[ "$instrumentation_passed" != true ]]; then
      write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "failed"
      fail "UI characterization instrumentation failed for ${comparison_id}/${profile_id}; see $case_dir/instrumentation.log"
    fi
    if [[ "$remote_evidence_present" != true ]] || (( pull_status != 0 )); then
      write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "failed"
      fail "UI characterization evidence was not available before teardown for ${comparison_id}/${profile_id}"
    fi
    [[ -f "$case_dir/device/probe-result.json" ]] || fail "Missing pulled probe-result.json for ${comparison_id}/${profile_id}"
    [[ -f "$case_dir/device/semantics-tree.txt" ]] || fail "Missing pulled semantics-tree.txt for ${comparison_id}/${profile_id}"

    write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "passed"
    reset_display
  done

  uninstall_if_installed "$TEST_PACKAGE"
  uninstall_if_installed "$APP_PACKAGE"
done

echo "TV UI characterization evidence: $run_root"
echo "MUXTV_UI_RUN_ROOT=$run_root" >> "${GITHUB_ENV:-/dev/null}"
