#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?expected hosted test AVD required}"
: "${MUXTV_CRYPTO_EVIDENCE:?evidence path required}"

mkdir -p "$MUXTV_CRYPTO_EVIDENCE"
avdmanager list avd -c | sed '/^[[:space:]]*$/d' > "$MUXTV_CRYPTO_EVIDENCE/avds.txt"
mapfile -t names < "$MUXTV_CRYPTO_EVIDENCE/avds.txt"
if (( ${#names[@]} != 1 )) || [[ "${names[0]}" != "$MUXTV_EXPECTED_AVD" ]]; then
  echo "Crypto provider test must execute on the exact expected AVD." >&2
  exit 1
fi

chmod +x ./gradlew
./gradlew :local-control:crypto-android-compat:connectedDebugAndroidTest \
  --no-daemon --stacktrace --console=plain --no-problems-report

pwsh -NoProfile -File ./tools/ci/Assert-AndroidTestResults.ps1 \
  -ModulePaths "local-control/crypto-android-compat" \
  -OutputPath "$MUXTV_CRYPTO_EVIDENCE/android-crypto-compat-tests.json"

echo "Android crypto device evidence accepted for $MUXTV_EXPECTED_AVD."
