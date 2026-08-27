#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"
: "${MUXTV_DEVICE_EVIDENCE:?MUXTV_DEVICE_EVIDENCE is required}"

mkdir -p "$MUXTV_DEVICE_EVIDENCE"
INVENTORY_PATH="$MUXTV_DEVICE_EVIDENCE/avd-inventory.txt"
avdmanager list avd -c | sed '/^[[:space:]]*$/d' | tee "$INVENTORY_PATH"
mapfile -t avds < "$INVENTORY_PATH"
if (( ${#avds[@]} != 1 )); then
  echo "Expected exactly one AVD, found ${#avds[@]}: ${avds[*]-<none>}" >&2
  exit 1
fi
if [[ "${avds[0]}" != "$MUXTV_EXPECTED_AVD" ]]; then
  echo "Expected canonical AVD $MUXTV_EXPECTED_AVD, found ${avds[0]}." >&2
  exit 1
fi

chmod +x ./gradlew
./gradlew \
  :core:database:connectedDebugAndroidTest \
  :catalog:importer:connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report

for module in core/database catalog/importer; do
  slug="${module//\//-}"
  pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
    -ModulePaths "$module" \
    -OutputPath "$MUXTV_DEVICE_EVIDENCE/android-test-results-$slug.json"
done

echo "Hosted database migration tests passed on $MUXTV_EXPECTED_AVD."
