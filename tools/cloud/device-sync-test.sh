#!/usr/bin/env bash
# Dedicated test account only. Builds APKs and verifies two emulators + one phone.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${CLOUD_TEST_EMAIL:?Set CLOUD_TEST_EMAIL to a dedicated test account}"
: "${CLOUD_TEST_PASSWORD:?Set CLOUD_TEST_PASSWORD}"
first="${CLOUD_TEST_FIRST:-emulator-5554}"
second="${CLOUD_TEST_SECOND:-emulator-5556}"
phone="${CLOUD_TEST_PHONE:?Set CLOUD_TEST_PHONE to the real ADB serial}"
run="$(date +%s)"
./gradlew -PcloudDeviceTest=true assembleCloudDebug assembleCloudDebugAndroidTest
for device in "$first" "$second" "$phone"; do
  adb -s "$device" install -r app/build/outputs/apk/cloud/debug/app-cloud-debug.apk
  adb -s "$device" install -r app/build/outputs/apk/androidTest/cloud/debug/app-cloud-debug-androidTest.apk
  adb -s "$device" reverse tcp:3000 tcp:3000
done
phase() {
  local device="$1" action="$2" output
  printf "Testing %s on %s\n" "$action" "$device"
  output="$(adb -s "$device" shell am instrument -w -e class dev.lmnaide.calendar.sync.CloudDeviceTest -e phase "$action" -e run "$run" -e email "$CLOUD_TEST_EMAIL" -e password "$CLOUD_TEST_PASSWORD" dev.lmnaide.calendar.devicetest.test/androidx.test.runner.AndroidJUnitRunner)"
  printf '%s\n' "$output"
  [[ "$output" == *"OK (1 test)"* ]]
}
phase "$first" create
phase "$phone" edit
phase "$second" verify
phase "$first" verify
phase "$second" delete
phase "$first" verifyDeleted
phase "$phone" verifyDeleted
adb -s "$first" reverse --remove tcp:3000
trap 'adb -s "$first" reverse tcp:3000 tcp:3000 >/dev/null' EXIT
phase "$first" queueOffline
adb -s "$first" shell am force-stop dev.lmnaide.calendar.devicetest
adb -s "$first" reverse tcp:3000 tcp:3000
phase "$first" verifyOffline
phase "$phone" verifyOffline
phase "$second" verifyOffline
phase "$first" verifyDeletionRace
phase "$phone" verifyDeletionRace
printf 'Cross-device and offline restart checks passed on all three devices.\n'
