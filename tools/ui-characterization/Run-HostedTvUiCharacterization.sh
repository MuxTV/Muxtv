#!/usr/bin/env bash
set -euo pipefail

readonly BASELINE_A="2302c11441c85b8b5752d7f03cc5bc13be8c6d92"
readonly BASELINE_B="515072022d11b218fcb20f43079f94098b3ea973"
readonly BASELINE_C="7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9"
readonly EXPECTED_AVD="MuxTV_TV_CURRENT_API36"
readonly APP_PACKAGE="app.muxtv.tv.debug"
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

clear_app_state_if_installed() {
  if adb -s "$ANDROID_SERIAL" shell pm path "$APP_PACKAGE" 2>/dev/null | grep -q '^package:'; then
    adb -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" | grep -qi 'success' || fail "Unable to clear installed package $APP_PACKAGE"
  else
    echo "Package $APP_PACKAGE is not installed yet; skipping pre-test pm clear."
  fi
  adb -s "$ANDROID_SERIAL" shell rm -rf "$REMOTE_EVIDENCE" >/dev/null 2>&1 || true
}

for item in "${COMPARISONS[@]}"; do
  comparison_id="${item%%:*}"
  source_commit="${item#*:}"
  worktree="${WORKTREE_ROOT}/${comparison_id}"

  rm -rf "$worktree"
  git worktree add --detach "$worktree" "$source_commit"
  [[ "$(git -C "$worktree" rev-parse HEAD)" == "$source_commit" ]] || fail "Worktree provenance mismatch for $comparison_id"

  mkdir -p "$(dirname "$worktree/$TARGET_PROBE")"
  cp "$PROBE_SOURCE" "$worktree/$TARGET_PROBE"
  [[ "$(sha256sum "$worktree/$TARGET_PROBE" | awk '{print $1}')" == "$probe_sha" ]] || fail "Probe identity mismatch for $comparison_id"
  chmod +x "$worktree/gradlew"

  for profile in "${PROFILES[@]}"; do
    IFS=':' read -r profile_id profile_label size width height density representative <<<"$profile"
    case_dir="${run_root}/${comparison_id}/${profile_id}"
    mkdir -p "$case_dir"
    manifest="$case_dir/case-manifest.json"
    write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "running"

    set_display "$size" "$density" "$case_dir"
    clear_app_state_if_installed

    echo "=== U0 ${comparison_id}/${profile_id}: ${source_commit} ${size}@${density} ==="
    set +e
    (
      cd "$worktree"
      ./gradlew \
        :app:tv:connectedDebugAndroidTest \
        --no-daemon \
        --stacktrace \
        --console=plain \
        --no-problems-report \
        -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.UiCharacterizationProbeTest \
        -Pandroid.testInstrumentationRunnerArguments.sourceCommit="$source_commit" \
        -Pandroid.testInstrumentationRunnerArguments.displayProfile="$profile_id" \
        -Pandroid.testInstrumentationRunnerArguments.displayWidthPx="$width" \
        -Pandroid.testInstrumentationRunnerArguments.displayHeightPx="$height" \
        -Pandroid.testInstrumentationRunnerArguments.displayDensityDpi="$density"
    ) 2>&1 | tee "$case_dir/gradle.log"
    gradle_status=${PIPESTATUS[0]}
    set -e

    if (( gradle_status != 0 )); then
      write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "failed"
      fail "UI characterization instrumentation failed for ${comparison_id}/${profile_id} with exit code $gradle_status"
    fi

    mkdir -p "$case_dir/device"
    adb -s "$ANDROID_SERIAL" pull "${REMOTE_EVIDENCE}/." "$case_dir/device" 2>&1 | tee "$case_dir/adb-pull.log"
    write_manifest "$manifest" "$source_commit" "$profile_id" "$profile_label" "$width" "$height" "$density" "$representative" "passed"
    reset_display
  done

done

echo "TV UI characterization evidence: $run_root"
echo "MUXTV_UI_RUN_ROOT=$run_root" >> "${GITHUB_ENV:-/dev/null}"
