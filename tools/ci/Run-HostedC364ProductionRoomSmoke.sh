#!/usr/bin/env bash
set -euo pipefail

: "${MUXTV_EXPECTED_AVD:?MUXTV_EXPECTED_AVD is required}"

case "$MUXTV_EXPECTED_AVD" in
  MuxTV_TV_OLD_API26|MuxTV_TV_CURRENT_API36) ;;
  *)
    echo "Unsupported C364 smoke AVD: $MUXTV_EXPECTED_AVD" >&2
    exit 1
    ;;
esac

chmod +x ./gradlew
rm -rf core/database/build/outputs/androidTest-results/connected/debug

./gradlew \
  :core:database:connectedDebugAndroidTest \
  --no-daemon \
  --stacktrace \
  --console=plain \
  --no-problems-report \
  -PcatalogMeasurements=true \
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.database.C03ProductionRoomMeasurementTest

echo "C364 focused smoke passed on $MUXTV_EXPECTED_AVD."
