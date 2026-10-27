#!/usr/bin/env bash
# Installs an isolated calendar Test app; requires the local server and a dedicated account.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${CLOUD_TEST_EMAIL:?Set CLOUD_TEST_EMAIL to a dedicated test account}"
: "${CLOUD_TEST_PASSWORD:?Set CLOUD_TEST_PASSWORD}"
first="${CLOUD_TEST_FIRST:-emulator-5554}"
second="${CLOUD_TEST_SECOND:-emulator-5556}"
run="$(date +%s)"
package="dev.lmnaide.calendar.devicetest"
./gradlew -PcloudDeviceTest=true assembleCloudDebug assembleCloudDebugAndroidTest
for device in "$first" "$second"; do
  adb -s "$device" install -r app/build/outputs/apk/cloud/debug/app-cloud-debug.apk
  adb -s "$device" install -r app/build/outputs/apk/androidTest/cloud/debug/app-cloud-debug-androidTest.apk
  adb -s "$device" reverse tcp:3000 tcp:3000
done
phase() {
  local device="$1" action="$2" output
  printf 'Testing event sharing %s on %s\n' "$action" "$device"
  output="$(adb -s "$device" shell am instrument -w -e class dev.lmnaide.calendar.sync.EventShareDeviceTest -e phase "$action" -e run "$run" -e email "$CLOUD_TEST_EMAIL" -e password "$CLOUD_TEST_PASSWORD" "$package.test/androidx.test.runner.AndroidJUnitRunner")"
  printf '%s\n' "$output"
  [[ "$output" == *"OK (1 test)"* ]]
}
phase "$first" create
phase "$second" receive
phase "$first" delete
printf 'Two-device creation, live edits, revocation, re-sharing and deletion checks passed.\n'
