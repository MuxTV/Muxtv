#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_SOURCE_COMMIT:?exact source commit required}"
: "${MUXTV_PHONE_API:?phone emulator API required}"
: "${MUXTV_EXPECTED_AVD:?phone AVD required}"
[[ "$MUXTV_PHONE_API" == 26 || "$MUXTV_PHONE_API" == 36 ]] || {
  echo "Only accepted phone API26/API36 matrix is allowed." >&2
  exit 1
}
test "$(git rev-parse HEAD)" = "$MUXTV_SOURCE_COMMIT"

evidence=".work/evidence/lc04-phone-native-api${MUXTV_PHONE_API}"
mkdir -p "$evidence"
printf 'source_commit=%s\napi=%s\navd=%s\n' \
  "$MUXTV_SOURCE_COMMIT" "$MUXTV_PHONE_API" "$MUXTV_EXPECTED_AVD" \
  > "$evidence/run-metadata.txt"

mapfile -t actual < <(avdmanager list avd -c | sed '/^[[:space:]]*$/d')
if (( ${#actual[@]} != 1 )) || [[ "${actual[0]-}" != "$MUXTV_EXPECTED_AVD" ]]; then
  echo "Unexpected emulator inventory for phone API${MUXTV_PHONE_API}" >&2
  exit 1
fi
printf '%s\n' "${actual[@]}" > "$evidence/avd-inventory.txt"

chmod +x ./gradlew
./gradlew :app:phone:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.phone.PhoneNativeProofDeviceTest \
  --no-daemon --stacktrace --console=plain --no-problems-report

pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths app/phone \
  -OutputPath "$evidence/android-test-results.json"

export MUXTV_PHONE_TEST_SUMMARY="$evidence/android-test-results.json"
pwsh -NoProfile -Command '$s = Get-Content -LiteralPath $env:MUXTV_PHONE_TEST_SUMMARY -Raw | ConvertFrom-Json; if (([int]$s.tests - [int]$s.skipped) -ne 3 -or [int]$s.failures -ne 0 -or [int]$s.errors -ne 0) { throw "Exactly 3 successful, non-skipped phone native UI tests required." }'
echo "Accepted Android phone native proof-only lifecycle API${MUXTV_PHONE_API}: 3/3 device tests."
