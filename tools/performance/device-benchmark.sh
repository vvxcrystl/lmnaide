#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: ANDROID_HOME=/path/to/sdk $0 ADB_SERIAL DEBUG_APK [all|search|contacts]" >&2
    exit 1
fi
serial=$1
apk=$2
scenario=${3:-all}
case "$scenario" in all|search|contacts) ;; *) echo 'Unknown scenario' >&2; exit 1 ;; esac
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" ]]; then
    echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' >&2
    exit 1
fi
script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
work_dir=$(mktemp -d)
remote_dir="/data/local/tmp/lmnaide-perf-$$"
trap 'rm -rf -- "$work_dir"; adb -s "$serial" shell rm -rf "$remote_dir" >/dev/null 2>&1 || true' EXIT
android_jar=$(find "$sdk/platforms" -name android.jar | sort -V | tail -1)
d8=$(find "$sdk/build-tools" -name d8 | sort -V | tail -1)
mkdir -p "$work_dir/classes" "$work_dir/dex"
javac --release 8 -Xlint:-options -cp "$android_jar" -d "$work_dir/classes" "$script_dir/DeviceBenchmark.java"
jar cf "$work_dir/runner.jar" -C "$work_dir/classes" .
"$d8" --lib "$android_jar" --min-api 26 --output "$work_dir/dex" "$work_dir/runner.jar"
adb -s "$serial" shell mkdir -p "$remote_dir"
adb -s "$serial" push "$work_dir/dex/classes.dex" "$remote_dir/runner.dex" >/dev/null
adb -s "$serial" push "$apk" "$remote_dir/app.apk" >/dev/null
adb -s "$serial" shell "CLASSPATH=$remote_dir/runner.dex:$remote_dir/app.apk" app_process /system/bin DeviceBenchmark "$scenario"
